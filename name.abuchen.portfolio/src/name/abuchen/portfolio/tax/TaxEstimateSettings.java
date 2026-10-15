package name.abuchen.portfolio.tax;

import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.JsonParser;

import name.abuchen.portfolio.model.Client;

/** Explicit assumptions for an independent, signed estimate per holding. */
@SuppressWarnings("nls")
public final class TaxEstimateSettings
{
    public static final String PROPERTY = "fork.tax.position-estimates.v1";

    public enum Profile
    {
        CTO, LIFE_INSURANCE, HOLDING_ANNUAL, HOLDING_DISPOSAL, EXEMPT, CUSTOM, PEA
    }

    public enum LossTreatment
    {
        POTENTIAL_SAVING, ZERO_FLOOR
    }

    /** Amounts are EUR cents, shares use Values.Share, rate is in basis points. */
    public record Basis(Profile profile, int rateBps, LossTreatment losses, long amount, long shares,
                    String validatedOn, String reviewOn, String movements, String note)
    {
        public Basis
        {
            Objects.requireNonNull(profile);
            Objects.requireNonNull(losses);
            Objects.requireNonNull(note);
            var date = LocalDate.parse(validatedOn);
            if (rateBps < 0 || rateBps > 10_000 || amount < 0 || shares < 0 || (shares == 0 && amount != 0))
                throw new IllegalArgumentException("Base, quantité ou taux fiscal invalide.");
            if (profile == Profile.EXEMPT && rateBps != 0)
                throw new IllegalArgumentException("Un profil exonéré doit avoir un taux nul.");
            if (profile == Profile.HOLDING_ANNUAL && reviewOn == null)
                throw new IllegalArgumentException("Renseigner la prochaine clôture fiscale.");
            if (reviewOn != null && !LocalDate.parse(reviewOn).isAfter(date))
                throw new IllegalArgumentException("La prochaine révision doit suivre la date de validation.");
            if (movements == null || !movements.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Empreinte des opérations invalide.");
        }
    }

    public record Settings(int version, Map<String, Basis> positions)
    {
        public Settings
        {
            if (version != 1)
                throw new IllegalArgumentException("Version des paramètres fiscaux non prise en charge.");
            positions = Map.copyOf(positions);
            positions.forEach((key, basis) -> {
                var ids = key.split("/", -1);
                if (ids.length != 2)
                    throw new IllegalArgumentException("Identifiant de position invalide.");
                UUID.fromString(ids[0]);
                UUID.fromString(ids[1]);
                Objects.requireNonNull(basis);
            });
        }

        public Settings with(String key, Basis basis)
        {
            var copy = new TreeMap<>(positions);
            copy.put(key, basis);
            return new Settings(version, copy);
        }
    }

    private TaxEstimateSettings()
    {
    }

    public static String key(String portfolioId, String securityId)
    {
        return portfolioId + "/" + securityId;
    }

    public static Settings load(Client client)
    {
        var json = client.getProperty(PROPERTY);
        if (json == null)
            return new Settings(1, Map.of());
        try
        {
            // Gson defaults absent primitive fields to zero. A missing rate or
            // cost must never silently become a valid zero-rate/zero-cost basis.
            var root = JsonParser.parseString(json).getAsJsonObject();
            var entries = root.getAsJsonObject("positions");
            entries.entrySet().forEach(entry -> {
                var basis = entry.getValue().getAsJsonObject();
                for (String field : new String[] { "rateBps", "amount", "shares" })
                {
                    var value = basis.get(field);
                    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
                        throw new IllegalArgumentException("Paramètre fiscal manquant : " + field);
                    value.getAsBigDecimal().longValueExact();
                }
            });
            return Objects.requireNonNull(new Gson().fromJson(json, Settings.class));
        }
        catch (RuntimeException e)
        {
            throw new IllegalArgumentException("Paramètres fiscaux illisibles ; aucune donnée remplacée.", e);
        }
    }

    /** Optimistic check prevents a stale dialog from overwriting another edit. */
    public static void save(Client client, Settings settings, String expectedProperty)
    {
        Objects.requireNonNull(settings);
        load(client);
        if (!Objects.equals(expectedProperty, client.getProperty(PROPERTY)))
            throw new IllegalArgumentException("Les paramètres ont changé. Rouvrir le formulaire.");
        client.setProperty(PROPERTY, new Gson().toJson(settings));
        client.touch();
    }
}
