package name.abuchen.portfolio.tax;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.JsonParser;

import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Portfolio;
import name.abuchen.portfolio.model.Transaction;
import name.abuchen.portfolio.tax.TaxEstimateSettings.Basis;
import name.abuchen.portfolio.tax.TaxEstimateSettings.LossTreatment;
import name.abuchen.portfolio.tax.TaxEstimateSettings.Profile;

/** Shared envelope assumptions. Derived opening bases are never persisted as manual overrides. */
@SuppressWarnings("nls")
public final class TaxEnvelopeProfiles
{
    public static final String PROPERTY = "fork.tax.envelope-profiles.v1";

    /** PEA scenario: French resident, complete history, withdrawal after five years, constant estimated rate. */
    public record Pea(String name, String portfolio, String cashAccount, String openedOn, int socialRateBps,
                    boolean completeHistory, boolean frenchResident, String assumptionDate, String note)
    {
        public Pea
        {
            if (name == null || name.isBlank() || note == null)
                throw new IllegalArgumentException("Nom et justification du profil requis.");
            UUID.fromString(portfolio);
            UUID.fromString(cashAccount);
            var opening = LocalDate.parse(openedOn);
            LocalDate.parse(assumptionDate);
            if (opening.isBefore(LocalDate.of(2018, 1, 1)))
                throw new IllegalArgumentException("PEA antérieur à 2018 : les taux historiques nécessitent un profil spécifique.");
            if (socialRateBps < 0 || socialRateBps > 10_000)
                throw new IllegalArgumentException("Taux estimatif attendu entre 0 et 100 %.");
            if (!completeHistory || !frenchResident)
                throw new IllegalArgumentException("Ce profil requiert un historique complet et une résidence fiscale française confirmés.");
        }

        public LocalDate maturity()
        {
            return LocalDate.parse(openedOn).plusYears(5);
        }
    }

    public record Settings(int version, Map<String, Pea> profiles)
    {
        public Settings
        {
            if (version != 1)
                throw new IllegalArgumentException("Version des profils d'enveloppe inconnue.");
            profiles = Map.copyOf(profiles);
            profiles.forEach((id, profile) -> {
                if (!id.equals(profile.portfolio()))
                    throw new IllegalArgumentException("Compte-titres du profil incohérent.");
            });
        }

        public Settings with(Pea profile)
        {
            var copy = new HashMap<>(profiles);
            copy.put(profile.portfolio(), profile);
            return new Settings(version, copy);
        }
    }

    private TaxEnvelopeProfiles()
    {
    }

    public static Settings load(Client client)
    {
        var json = client.getProperty(PROPERTY);
        if (json == null)
            return new Settings(1, Map.of());
        try
        {
            var root = JsonParser.parseString(json).getAsJsonObject();
            root.get("version").getAsBigDecimal().intValueExact();
            for (var item : root.getAsJsonObject("profiles").entrySet())
                item.getValue().getAsJsonObject().get("socialRateBps").getAsBigDecimal().intValueExact();
            return Objects.requireNonNull(new Gson().fromJson(json, Settings.class));
        }
        catch (RuntimeException e)
        {
            throw new IllegalArgumentException("Profils d'enveloppe illisibles ; aucune donnée remplacée.", e);
        }
    }

    public static void save(Client client, Settings settings, String expected)
    {
        load(client);
        if (!Objects.equals(expected, client.getProperty(PROPERTY)))
            throw new IllegalArgumentException("Les profils ont changé. Rouvrir le formulaire.");
        for (var profile : settings.profiles().values())
            validateHistory(client, profile, LocalDate.now());
        client.setProperty(PROPERTY, new Gson().toJson(settings));
        client.touch();
    }

    public static Pea forPortfolio(Client client, Portfolio portfolio)
    {
        return load(client).profiles().get(portfolio.getUUID());
    }

    public static boolean inherited(Client client, Portfolio portfolio, String positionKey)
    {
        return forPortfolio(client, portfolio) != null && !TaxEstimateSettings.load(client).positions().containsKey(positionKey);
    }

    public static TaxEstimateSettings.Settings effectiveSettings(Client client)
    {
        var manual = TaxEstimateSettings.load(client);
        var positions = new HashMap<>(manual.positions());
        for (var profile : load(client).profiles().values())
        {
            var portfolio = client.getPortfolios().stream().filter(p -> p.getUUID().equals(profile.portfolio()))
                            .findFirst().orElseThrow(() -> new IllegalArgumentException("Compte-titres du profil absent : " + profile.name()));
            var start = LocalDate.parse(profile.openedOn()).minusDays(1);
            for (var security : portfolio.getTransactions().stream().map(Transaction::getSecurity).distinct().toList())
            {
                String key = TaxEstimateSettings.key(portfolio.getUUID(), security.getUUID());
                positions.putIfAbsent(key, new Basis(Profile.PEA, profile.socialRateBps(), LossTreatment.POTENTIAL_SAVING,
                                0, 0, start.toString(), null, PositionTaxEstimates.movements(client, portfolio, security, start),
                                "Hérité de « " + profile.name() + " ». Retrait après le " + profile.maturity()
                                                + ". Historique complet ; taux de scénario constant, hypothèse du " + profile.assumptionDate() + "."));
            }
        }
        return new TaxEstimateSettings.Settings(1, positions);
    }

    /** No inferred continuity across missing history, outside transfers or withdrawals. */
    public static void validateHistory(Client client, Pea profile, LocalDate end)
    {
        var portfolio = client.getPortfolios().stream().filter(p -> p.getUUID().equals(profile.portfolio())).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Compte-titres du profil absent."));
        var account = client.getAccounts().stream().filter(a -> a.getUUID().equals(profile.cashAccount())).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Compte espèces du PEA absent."));
        if (portfolio.getReferenceAccount() != account || !"EUR".equals(account.getCurrencyCode())
                        || client.getPortfolios().stream().filter(p -> p.getReferenceAccount() == account).count() != 1)
            throw new IllegalArgumentException("Le PEA doit avoir son compte espèces EUR dédié et correctement rattaché.");
        var opening = LocalDate.parse(profile.openedOn());
        for (var t : portfolio.getTransactions())
        {
            var date = t.getDateTime().toLocalDate();
            if (date.isAfter(end))
                continue;
            if (date.isBefore(opening))
                throw new IllegalArgumentException("Une opération du PEA précède sa date d'ouverture.");
            if (t.getType() != name.abuchen.portfolio.model.PortfolioTransaction.Type.BUY && t.getType() != name.abuchen.portfolio.model.PortfolioTransaction.Type.SELL)
                throw new IllegalArgumentException("Livraison ou transfert de titres du PEA : vérifier la reprise de son historique.");
            if (t.getCrossEntry() == null || t.getCrossEntry().getCrossOwner(t) != account)
                throw new IllegalArgumentException("Chaque achat et vente du PEA doit utiliser son compte espèces dédié.");
        }
        for (var t : account.getTransactions())
        {
            var date = t.getDateTime().toLocalDate();
            if (date.isAfter(end))
                continue;
            if (date.isBefore(opening))
                throw new IllegalArgumentException("Un mouvement espèces du PEA précède sa date d'ouverture.");
            switch (t.getType())
            {
                case DEPOSIT, BUY, SELL, FEES, FEES_REFUND -> { }
                case DIVIDENDS -> {
                    if (t.getSecurity() == null || portfolio.getTransactions().stream().noneMatch(p -> p.getSecurity() == t.getSecurity()))
                        throw new IllegalArgumentException("Revenu PEA sans titre identifiable.");
                }
                default -> throw new IllegalArgumentException("Mouvement " + t.getType() + " du " + date
                                + " : le profil PEA doit être complété pour un retrait, transfert ou impôt payé.");
            }
        }
    }
}
