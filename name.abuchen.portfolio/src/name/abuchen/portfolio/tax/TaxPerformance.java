package name.abuchen.portfolio.tax;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import name.abuchen.portfolio.model.Account;
import name.abuchen.portfolio.model.AccountTransaction;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Transaction;
import name.abuchen.portfolio.money.CurrencyConverter;
import name.abuchen.portfolio.snapshot.PerformanceIndex;
import name.abuchen.portfolio.snapshot.filter.WithoutTaxesFilter;
import name.abuchen.portfolio.util.Interval;

/** One performance engine for positions, classifications and complete investable scopes. */
@SuppressWarnings("nls")
public final class TaxPerformance
{
    public record Result(AfterTaxReturns.Result before, AfterTaxReturns.Result after, Long latent, Long debt,
                    Long netValue, List<TaxPositionLedger.Event> events, String detail)
    {
        public static Result unavailable(String reason)
        {
            return new Result(AfterTaxReturns.Result.unavailable(reason), AfterTaxReturns.Result.unavailable(reason),
                            null, null, null, List.of(), reason);
        }
    }

    private record TaxFlows(long payments, long refunds)
    {
        static TaxFlows of(long signed)
        {
            return new TaxFlows(Math.max(0, signed), Math.max(0, -signed));
        }

        TaxFlows plus(TaxFlows other)
        {
            return new TaxFlows(Math.addExact(payments, other.payments), Math.addExact(refunds, other.refunds));
        }
    }

    private TaxPerformance()
    {
    }

    /** Scoped cache lifetime: discard on quotes, transactions or fiscal settings updates. */
    public static final class Session
    {
        private final Client client;
        private final CurrencyConverter converter;
        private final Map<String, TaxPositionLedger> ledgers = new HashMap<>();

        public Session(Client client, CurrencyConverter converter)
        {
            this.client = client;
            this.converter = converter.with("EUR");
        }

        public Result calculate(TaxScope scope, Interval interval)
        {
            try
            {
                return calculateChecked(client, scope, converter, interval, ledgers);
            }
            catch (RuntimeException e)
            {
                return Result.unavailable(e.getMessage() == null ? "Calcul fiscal indisponible." : e.getMessage());
            }
        }
    }

    public static Result calculate(Client client, TaxScope scope, CurrencyConverter converter, Interval interval)
    {
        return new Session(client, converter).calculate(scope, interval);
    }

    private static Result calculateChecked(Client client, TaxScope scope, CurrencyConverter converter, Interval interval, Map<String, TaxPositionLedger> ledgers)
    {
        if (interval.getStart().equals(LocalDate.MIN) || interval.getDays() > 365L * 100 || interval.getDays() < 0)
            throw new IllegalArgumentException("Choisir une période fiscale datée (100 ans maximum).");
        var settings = TaxEnvelopeProfiles.effectiveSettings(client);
        var links = TaxPaymentLinks.load(client);
        var grossClient = new WithoutTaxesFilter().filter(scope.filter().filter(client));
        var warnings = new ArrayList<Exception>();
        var index = PerformanceIndex.forClient(grossClient, converter, interval, warnings);
        if (!warnings.isEmpty())
            throw new IllegalArgumentException("Valorisation du périmètre à vérifier : " + warnings.getFirst().getMessage());
        var dates = index.getDates();
        var values = index.getTotals().clone();
        var in = index.getInboundTransferals().clone();
        var out = index.getOutboundTransferals().clone();
        for (var account : client.getAccounts())
            if (scope.accounts().getOrDefault(account.getUUID(), 0d) > 0)
                for (var day : dates)
                    TaxPositionLedger.euros(converter, day, name.abuchen.portfolio.money.Money.of(account.getCurrencyCode(), 1));
        var before = AfterTaxReturns.calculate(dates, values, in, out);
        try
        {
            var fullPea = new HashMap<String, long[]>();
            var envelopeProfiles = TaxEnvelopeProfiles.load(client);
            for (var profile : envelopeProfiles.profiles().values())
            {
                double weight = scope.accounts().getOrDefault(profile.cashAccount(), 0d);
                var positions = settings.positions().entrySet().stream()
                                .filter(e -> e.getKey().startsWith(profile.portfolio() + "/")).toList();
                if (weight > 0 && positions.stream().allMatch(e -> e.getValue().profile() == TaxEstimateSettings.Profile.PEA
                                && Math.abs(scope.positions().getOrDefault(e.getKey(), 0d) - weight) < 1e-9))
                {
                    TaxEnvelopeProfiles.validateHistory(client, profile, dates[dates.length - 1]);
                    fullPea.put(profile.portfolio(), new long[dates.length]);
                }
            }
            var events = new ArrayList<TaxPositionLedger.Event>();
            long[] latent = new long[dates.length];
            long[] debt = new long[dates.length];
            long[] expenseOutside = new long[dates.length];
            var selected = new HashMap<String, TaxPositionLedger>();
            LocalDate validationStart = dates[0];
            for (var portfolio : client.getPortfolios())
                for (var security : portfolio.getTransactions().stream().map(Transaction::getSecurity).distinct().toList())
                {
                    String key = TaxEstimateSettings.key(portfolio.getUUID(), security.getUUID());
                    double weight = scope.positions().getOrDefault(key, 0d);
                    if (weight <= 0)
                        continue;
                    var seed = settings.positions().get(key);
                    boolean active = PositionTaxEstimates.sharesAt(portfolio, security, dates[0]) != 0
                                    || portfolio.getTransactions().stream().anyMatch(t -> t.getSecurity() == security
                                                    && interval.contains(t.getDateTime()));
                    if (seed == null)
                    {
                        if (active)
                            throw new IllegalArgumentException("Fiscalité à paramétrer : " + portfolio.getName() + " / " + security.getName());
                        continue;
                    }
                    var seedDate = LocalDate.parse(seed.validatedOn());
                    if (seedDate.isAfter(dates[dates.length - 1]) && !active)
                        continue;
                    if (seedDate.isAfter(dates[0]) && (links.openingDebt().getOrDefault(key, 0L) != 0
                                    || portfolio.getTransactions().stream().anyMatch(t -> t.getSecurity() == security
                                                    && t.getDateTime().toLocalDate().isBefore(seedDate))))
                        throw new IllegalArgumentException("La base de " + security.getName() + " est datée du " + seedDate
                                        + ". Choisir une période couverte ou renseigner une base historique.");
                    validationStart = seedDate.isBefore(validationStart) ? seedDate : validationStart;
                    var ledger = ledgers.computeIfAbsent(key + "/" + dates[dates.length - 1], ignored ->
                                    new TaxPositionLedger(client, portfolio, security, seed, links, converter, dates[dates.length - 1]));
                    selected.put(key, ledger);
                    double retained = portfolio.getReferenceAccount() == null ? 0
                                    : Math.min(weight, scope.accounts().getOrDefault(portfolio.getReferenceAccount().getUUID(), 0d));
                    for (var payment : links.links())
                        if (payment.position().equals(key))
                        {
                            double payerWeight = scope.accounts().getOrDefault(payment.account(), 0d);
                            retained = Math.max(retained, Math.min(weight, payerWeight));
                        }
                    for (var payment : links.links())
                        if (payment.position().equals(key) && retained > 0
                                        && Math.abs(scope.accounts().getOrDefault(payment.account(), 0d) - retained) > 1e-9)
                            throw new IllegalArgumentException("Inclure le compte payeur de l'impôt avec la même pondération que la position.");
                    for (int i = 0; i < dates.length; i++)
                    {
                        var day = ledger.at(dates[i]);
                        latent[i] = Math.addExact(latent[i], weighted(day.latent(), weight));
                        if (seed.profile() == TaxEstimateSettings.Profile.LIFE_INSURANCE || seed.profile() == TaxEstimateSettings.Profile.PEA)
                            latent[i] = Math.addExact(latent[i], weighted(day.debt(), retained));
                        else
                            debt[i] = Math.addExact(debt[i], weighted(day.debt(), retained));
                        expenseOutside[i] = Math.addExact(expenseOutside[i], weighted(day.charge(), weight - retained));
                        var plan = fullPea.get(portfolio.getUUID());
                        if (plan != null)
                            plan[i] = Math.addExact(plan[i], weighted(Math.addExact(day.latent(), day.debt()), weight));
                    }
                    ledger.events().stream().filter(e -> interval.contains(e.date())).forEach(e -> events.add(
                                    new TaxPositionLedger.Event(e.date(), portfolio.getName() + " / " + security.getName() + " — " + e.kind(),
                                                    weighted(e.taxableGain(), weight), weighted(e.tax(), weight))));
                }
            // A negative title contribution can offset PEA gains, but the complete plan never creates a tax receivable.
            for (var entry : fullPea.entrySet())
            {
                var profile = envelopeProfiles.profiles().get(entry.getKey());
                var account = client.getAccounts().stream().filter(a -> a.getUUID().equals(profile.cashAccount())).findFirst().orElseThrow();
                double weight = scope.accounts().get(profile.cashAccount());
                var fees = new TreeMap<LocalDate, Long>();
                for (var transaction : account.getTransactions())
                    if (transaction.getType() == AccountTransaction.Type.FEES || transaction.getType() == AccountTransaction.Type.FEES_REFUND)
                        fees.merge(transaction.getDateTime().toLocalDate(), transaction.getType() == AccountTransaction.Type.FEES
                                        ? -transaction.getAmount() : transaction.getAmount(), Math::addExact);
                long runningFees = 0;
                var feeIterator = fees.entrySet().iterator();
                var fee = feeIterator.hasNext() ? feeIterator.next() : null;
                for (int i = 0; i < dates.length; i++)
                {
                    while (fee != null && !fee.getKey().isAfter(dates[i]))
                    {
                        runningFees = Math.addExact(runningFees, fee.getValue());
                        fee = feeIterator.hasNext() ? feeIterator.next() : null;
                    }
                    long feeTax = weighted(runningFees, weight * profile.socialRateBps() / 10_000d);
                    long capped = Math.max(0, Math.addExact(entry.getValue()[i], feeTax));
                    latent[i] = Math.addExact(latent[i], Math.subtractExact(capped, entry.getValue()[i]));
                }
            }
            // Taxes neutralized as withdrawals by the gross index become expenses again.
            // Matched payments simultaneously reduce the debt in the ledger, hence no second charge.
            var actual = actualTaxes(client, scope, converter, validationStart, dates[dates.length - 1], links, selected);
            for (int i = 0; i < dates.length; i++)
            {
                values[i] = Math.subtractExact(values[i], Math.addExact(latent[i], debt[i]));
                if (i == 0)
                    continue;
                var flows = actual.getOrDefault(dates[i], new TaxFlows(0, 0));
                long recorded = flows.payments();
                if (flows.refunds() > 0)
                {
                    long refunded = flows.refunds();
                    long cancelled = Math.min(in[i], refunded);
                    in[i] -= cancelled;
                    out[i] = Math.addExact(out[i], refunded - cancelled);
                }
                long expense = Math.addExact(expenseOutside[i], recorded);
                long adjustedOut = Math.subtractExact(out[i], expense);
                if (adjustedOut < 0)
                {
                    in[i] = Math.subtractExact(in[i], adjustedOut);
                    out[i] = 0;
                }
                else
                    out[i] = adjustedOut;
            }
            var after = AfterTaxReturns.calculate(dates, values, in, out);
            int last = dates.length - 1;
            String detail = after.detail() + " Périmètre complet, espèces incluses selon la sélection. "
                            + "Taux et économies de pertes conventionnels ; aucun taux légal implicite. "
                            + "Le TRI reste vide si aucune solution stable ou plusieurs solutions sont détectées."
                            + (fullPea.isEmpty() ? "" : " PEA complet : scénario de retrait après cinq ans, au taux estimatif constant du profil ; provision totale plafonnée à zéro en cas de perte.");
            return new Result(before, after, latent[last], debt[last], values[last], List.copyOf(events), detail);
        }
        catch (RuntimeException e)
        {
            String reason = e.getMessage() == null ? "Calcul fiscal indisponible." : e.getMessage();
            return new Result(before, AfterTaxReturns.Result.unavailable(reason), null, null, null, List.of(), reason);
        }
    }

    private static Map<LocalDate, TaxFlows> actualTaxes(Client client, TaxScope scope, CurrencyConverter converter,
                    LocalDate start, LocalDate end, TaxPaymentLinks.Settings links, Map<String, TaxPositionLedger> positions)
    {
        Map<LocalDate, TaxFlows> amounts = new TreeMap<>();
        for (var account : client.getAccounts())
        {
            double weight = scope.accounts().getOrDefault(account.getUUID(), 0d);
            if (weight == 0)
                continue;
            for (var t : account.getTransactions())
            {
                var date = t.getDateTime().toLocalDate();
                if (!date.isAfter(start) || date.isAfter(end))
                    continue;
                long tax = 0;
                if ((t.getType() == AccountTransaction.Type.TAXES || t.getType() == AccountTransaction.Type.TAX_REFUND))
                {
                    var matches = links.links().stream().filter(l -> l.account().equals(account.getUUID()) && l.transaction().equals(t.getUUID())).toList();
                    long reconciled = matches.stream().mapToLong(TaxPaymentLinks.Link::euros).reduce(0, Math::addExact);
                    tax = TaxPositionLedger.euros(converter, date, t.getMonetaryAmount());
                    if (reconciled != tax)
                        throw new IllegalArgumentException("Paiement d'impôt du " + date + " à rapprocher intégralement (" + account.getName() + ").");
                    if (t.getType() == AccountTransaction.Type.TAX_REFUND)
                        tax = -tax;
                    for (var match : matches)
                    {
                        if (!positions.containsKey(match.position()))
                            throw new IllegalArgumentException("Le périmètre inclut un paiement fiscal sans sa position : élargir le périmètre.");
                        double positionWeight = scope.positions().getOrDefault(match.position(), 0d);
                        if (Math.abs(positionWeight - weight) > 1e-9)
                            throw new IllegalArgumentException("Paiement fiscal et position doivent avoir la même pondération dans ce périmètre.");
                    }
                }
                else if (t.getType() == AccountTransaction.Type.DIVIDENDS || t.getType() == AccountTransaction.Type.INTEREST)
                {
                    tax = TaxPositionLedger.euros(converter, date, t.getUnitSum(Transaction.Unit.Type.TAX));
                    if (t.getType() == AccountTransaction.Type.INTEREST && tax == 0 && t.getAmount() != 0)
                        throw new IllegalArgumentException("Intérêts sans imposition renseignée : compléter le flux du " + date);
                }
                if (tax != 0)
                    amounts.merge(date, TaxFlows.of(weighted(tax, weight)), TaxFlows::plus);
            }
        }
        for (var portfolio : client.getPortfolios())
            for (var t : portfolio.getTransactions())
            {
                var date = t.getDateTime().toLocalDate();
                if (!date.isAfter(start) || date.isAfter(end))
                    continue;
                Account account = t.getCrossEntry() != null && t.getCrossEntry().getCrossOwner(t) instanceof Account payer
                                ? payer : portfolio.getReferenceAccount();
                double weight = account == null ? 0 : scope.accounts().getOrDefault(account.getUUID(), 0d);
                long tax = TaxPositionLedger.euros(converter, date, t.getUnitSum(Transaction.Unit.Type.TAX));
                if (tax != 0 && weight > 0)
                    amounts.merge(date, TaxFlows.of(weighted(tax, weight)), TaxFlows::plus);
            }
        return amounts;
    }

    private static long weighted(long amount, double weight)
    {
        return java.math.BigDecimal.valueOf(amount).multiply(java.math.BigDecimal.valueOf(weight))
                        .setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
    }
}
