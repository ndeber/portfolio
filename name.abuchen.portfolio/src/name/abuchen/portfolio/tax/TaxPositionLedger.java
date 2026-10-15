package name.abuchen.portfolio.tax;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;

import name.abuchen.portfolio.model.Account;
import name.abuchen.portfolio.model.AccountTransaction;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Portfolio;
import name.abuchen.portfolio.model.PortfolioTransaction;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.model.Transaction;
import name.abuchen.portfolio.money.CurrencyConverter;
import name.abuchen.portfolio.money.CurrencyConverterImpl;
import name.abuchen.portfolio.money.Money;
import name.abuchen.portfolio.money.Values;
import name.abuchen.portfolio.tax.TaxEstimateSettings.Basis;
import name.abuchen.portfolio.tax.TaxEstimateSettings.Profile;

/** Daily fiscal ledger, from an explicit opening basis. Never writes financial transactions. */
@SuppressWarnings("nls")
public final class TaxPositionLedger
{
    public record Day(long latent, long debt, long charge, long paid, long basis, long shares)
    {
    }

    public record Event(LocalDate date, String kind, long taxableGain, long tax)
    {
    }

    private final TreeMap<LocalDate, Day> days = new TreeMap<>();
    private final List<Event> events = new ArrayList<>();

    public Day at(LocalDate date)
    {
        return date.isBefore(days.firstKey()) ? new Day(0, 0, 0, 0, 0, 0) : days.get(date);
    }

    public List<Event> events()
    {
        return List.copyOf(events);
    }

    public TaxPositionLedger(Client client, Portfolio portfolio, Security security, Basis seed,
                    TaxPaymentLinks.Settings links, CurrencyConverter converter, LocalDate end)
    {
        boolean pea = seed.profile() == Profile.PEA;
        if (pea)
        {
            var profile = TaxEnvelopeProfiles.forPortfolio(client, portfolio);
            if (profile == null)
                throw new IllegalArgumentException("Configurer le PEA dans les profils d'enveloppe.");
            TaxEnvelopeProfiles.validateHistory(client, profile, end);
        }
        LocalDate start = LocalDate.parse(seed.validatedOn());
        if (start.isAfter(end))
            throw new IllegalArgumentException("Base fiscale postérieure à la période : " + security.getName());
        if (seed.shares() != PositionTaxEstimates.sharesAt(portfolio, security, start)
                        || !seed.movements().equals(PositionTaxEstimates.movements(client, portfolio, security, start)))
            throw new IllegalArgumentException("Historique initial modifié : revalider " + security.getName());
        String key = TaxEstimateSettings.key(portfolio.getUUID(), security.getUUID());
        var transactions = portfolio.getTransactions().stream().filter(t -> t.getSecurity() == security)
                        .filter(t -> t.getDateTime().toLocalDate().isAfter(start) && !t.getDateTime().toLocalDate().isAfter(end))
                        .sorted(Comparator.comparing(Transaction::getDateTime).thenComparing(Transaction::getUUID)).toList();
        for (int i = 1; i < transactions.size(); i++)
            if (transactions.get(i).getDateTime().equals(transactions.get(i - 1).getDateTime())
                            && transactions.get(i).getType() != transactions.get(i - 1).getType())
                throw new IllegalArgumentException("Préciser les heures des achats et ventes simultanés : " + security.getName());
        var income = new ArrayList<AccountTransaction>();
        Account account = portfolio.getReferenceAccount();
        if (account != null)
            for (var t : account.getTransactions())
                if (t.getSecurity() == security && t.getDateTime().toLocalDate().isAfter(start)
                                && !t.getDateTime().toLocalDate().isAfter(end)
                                && (t.getType() == AccountTransaction.Type.DIVIDENDS || t.getType().isCapitalFlow()))
                {
                    long owners = client.getPortfolios().stream().filter(p -> p.getReferenceAccount() == account)
                                    .filter(p -> PositionTaxEstimates.sharesAt(p, security, t.getDateTime().toLocalDate()) > 0).count();
                    if (owners != 1 && !pea)
                        throw new IllegalArgumentException("Revenu partagé entre plusieurs comptes-titres : attribution requise pour " + security.getName());
                    income.add(t);
                }
        income.sort(Comparator.comparing(Transaction::getDateTime));
        var payments = new TreeMap<LocalDate, Long>();
        for (var link : links.links())
        {
            if (!link.position().equals(key))
                continue;
            var payer = client.getAccounts().stream().filter(a -> a.getUUID().equals(link.account())).findFirst().orElseThrow();
            var transaction = payer.getTransactions().stream().filter(t -> t.getUUID().equals(link.transaction())).findFirst().orElseThrow();
            if (transaction.getType() != AccountTransaction.Type.TAXES && transaction.getType() != AccountTransaction.Type.TAX_REFUND)
                throw new IllegalArgumentException("Le rapprochement doit désigner un paiement d'impôt.");
            var date = transaction.getDateTime().toLocalDate();
            long allocated = links.links().stream().filter(l -> l.account().equals(link.account()) && l.transaction().equals(link.transaction()))
                            .mapToLong(TaxPaymentLinks.Link::euros).reduce(0, Math::addExact);
            if (allocated > euros(converter, date, transaction.getMonetaryAmount()))
                throw new IllegalArgumentException("Le montant rapproché dépasse le paiement réel.");
            if (!date.isAfter(start))
                continue; // Earlier settlements are already represented by the explicit opening debt.
            payments.merge(date, transaction.getType() == AccountTransaction.Type.TAXES ? link.euros() : -link.euros(), Math::addExact);
        }
        var splits = TaxDistributionSplits.load(client);
        long shares = seed.shares();
        long cost = seed.amount();
        long debt = links.openingDebt().getOrDefault(key, 0L);
        LocalDate close = seed.reviewOn() == null ? null : LocalDate.parse(seed.reviewOn());
        int txIndex = 0;
        int incomeIndex = 0;
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1))
        {
            long charge = 0;
            long paid = payments.getOrDefault(date, 0L);
            while (txIndex < transactions.size() && transactions.get(txIndex).getDateTime().toLocalDate().equals(date))
            {
                var t = transactions.get(txIndex++);
                long withheld = euros(converter, date, t.getUnitSum(Transaction.Unit.Type.TAX));
                long amount = euros(converter, date, t.getMonetaryAmount());
                switch (t.getType())
                {
                    case BUY, DELIVERY_INBOUND -> {
                        shares = Math.addExact(shares, t.getShares());
                        cost = Math.addExact(cost, pea ? amount : Math.subtractExact(amount, withheld));
                        // Transaction taxes on acquisition are an actual cost, not a capital-gain provision.
                        charge = Math.addExact(charge, withheld);
                        paid = Math.addExact(paid, withheld);
                        if (withheld != 0)
                            events.add(new Event(date, "Impôt sur acquisition", 0, withheld));
                    }
                    case SELL -> {
                        if (t.getShares() <= 0 || t.getShares() > shares)
                            throw new IllegalArgumentException("Quantité cédée incohérente : " + security.getName());
                        long released = prorata(cost, t.getShares(), shares);
                        long gain = Math.subtractExact(pea ? amount : Math.addExact(amount, withheld), released);
                        long tax = Math.max(withheld, tax(gain, seed));
                        if (withheld == 0)
                            tax = tax(gain, seed);
                        if (pea)
                            tax = Math.addExact(tax(gain, seed), withheld);
                        charge = Math.addExact(charge, tax);
                        paid = Math.addExact(paid, withheld);
                        cost = Math.subtractExact(cost, released);
                        shares = Math.subtractExact(shares, t.getShares());
                        events.add(new Event(date, pea ? "Vente interne PEA — réserve virtuelle" : seed.profile() == Profile.LIFE_INSURANCE ? "Sortie du support (convention AV)" : "Cession", gain, tax));
                    }
                    default -> throw new IllegalArgumentException("Transfert ou livraison sortante : base fiscale à reprendre pour " + security.getName());
                }
            }
            while (incomeIndex < income.size() && income.get(incomeIndex).getDateTime().toLocalDate().equals(date))
            {
                var t = income.get(incomeIndex++);
                long withheld = euros(converter, date, t.getUnitSum(Transaction.Unit.Type.TAX));
                long gross = Math.addExact(euros(converter, date, t.getMonetaryAmount()), withheld);
                if (t.getType().isCapitalFlow() && (withheld != 0 || !t.getUnitSum(Transaction.Unit.Type.FEE).isZero()))
                    throw new IllegalArgumentException("Appel / distribution avec retenue ou frais : ventiler les opérations séparément pour " + security.getName());
                if (t.getType() == AccountTransaction.Type.DIVIDENDS)
                    gross = Math.addExact(gross, euros(converter, date, t.getUnitSum(Transaction.Unit.Type.FEE)));
                if (t.getType() == AccountTransaction.Type.CAPITAL_CALL)
                {
                    cost = Math.addExact(cost, gross);
                    events.add(new Event(date, "Appel de fonds (capital fiscal)", 0, 0));
                }
                else
                {
                    long taxable = pea ? euros(converter, date, t.getMonetaryAmount()) : gross;
                    if (t.getType() == AccountTransaction.Type.DISTRIBUTION)
                    {
                        var configured = splits.get(t.getUUID());
                        if (configured == null)
                            throw new IllegalArgumentException("Ventiler la distribution du " + date + " de " + security.getName()
                                            + " entre capital remboursé et part imposable.");
                        if (configured > gross || gross - configured > cost)
                            throw new IllegalArgumentException("Ventilation de distribution incompatible avec la base fiscale : " + security.getName());
                        taxable = configured;
                        cost = Math.subtractExact(cost, gross - taxable);
                    }
                    long tax = pea ? Math.addExact(tax(taxable, seed), withheld) : Math.max(withheld, tax(taxable, seed));
                    charge = Math.addExact(charge, tax);
                    paid = Math.addExact(paid, withheld);
                    events.add(new Event(date, "Revenu / distribution imposable", taxable, tax));
                }
            }
            long value = market(converter, security, shares, date);
            if (close != null && !date.isBefore(close))
            {
                if (seed.profile() != Profile.HOLDING_ANNUAL)
                    throw new IllegalArgumentException("Révision fiscale atteinte : " + security.getName());
                long gain = Math.subtractExact(value, cost);
                long tax = tax(gain, seed);
                charge = Math.addExact(charge, tax);
                events.add(new Event(date, "Clôture annuelle estimée", gain, tax));
                cost = value;
                close = close.plusYears(1);
            }
            debt = Math.subtractExact(Math.addExact(debt, charge), paid);
            if (paid > 0 && debt < 0 && seed.losses() == TaxEstimateSettings.LossTreatment.ZERO_FLOOR)
                throw new IllegalArgumentException("Paiement supérieur à la dette estimée : renseigner la dette initiale ou vérifier le rapprochement.");
            days.put(date, new Day(tax(Math.subtractExact(value, cost), seed), debt, charge, paid, cost, shares));
        }
    }

    private static long tax(long gain, Basis seed)
    {
        if (seed.losses() == TaxEstimateSettings.LossTreatment.ZERO_FLOOR)
            gain = Math.max(0, gain);
        return BigDecimal.valueOf(gain).multiply(BigDecimal.valueOf(seed.rateBps())).movePointLeft(4)
                        .setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    static long prorata(long amount, long part, long whole)
    {
        return BigDecimal.valueOf(amount).multiply(BigDecimal.valueOf(part))
                        .divide(BigDecimal.valueOf(whole), 0, RoundingMode.HALF_UP).longValueExact();
    }

    public static long euros(CurrencyConverter converter, LocalDate date, Money money)
    {
        if (money.getAmount() == 0 || "EUR".equals(money.getCurrencyCode()))
            return money.getAmount();
        var eur = converter.with("EUR");
        var rate = eur instanceof CurrencyConverterImpl impl ? impl.getRateStrict(date, money.getCurrencyCode())
                        : eur.getRate(date, money.getCurrencyCode());
        if (rate.getTime().equals(LocalDate.MIN) || rate.getTime().isAfter(date) || rate.getValue().signum() <= 0)
            throw new IllegalArgumentException("Change EUR indisponible au " + date);
        return BigDecimal.valueOf(money.getAmount()).multiply(rate.getValue()).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    static long market(CurrencyConverter converter, Security security, long shares, LocalDate date)
    {
        if (shares == 0)
            return 0;
        if (shares < 0)
            throw new IllegalArgumentException("Position vendeuse non prise en charge.");
        var price = security.getSecurityPrice(date);
        if (price.getValue() < 0 || price.getDate().isAfter(date) || price.getDate().equals(LocalDate.MIN)
                        || (security.getPricesIncludingLatest().stream().noneMatch(p -> !p.getDate().isAfter(date))
                                        && !security.hasCapitalFlowsAt(date)))
            throw new IllegalArgumentException("Valorisation manquante : " + security.getName() + " au " + date);
        long amount = BigDecimal.valueOf(shares).multiply(BigDecimal.valueOf(price.getValue()))
                        .movePointLeft(Values.Share.precision() + Values.Quote.precisionDeltaToMoney())
                        .setScale(0, RoundingMode.HALF_UP).longValueExact();
        return euros(converter, date, Money.of(security.getCurrencyCode(), amount));
    }
}
