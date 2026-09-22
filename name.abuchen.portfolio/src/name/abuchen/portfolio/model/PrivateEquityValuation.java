package name.abuchen.portfolio.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import name.abuchen.portfolio.money.Values;

/**
 * Derives PE/VC NAVs without overwriting the fund's reported historical quotes.
 * Reported quotes are end-of-day NAVs and include cash flows on that date.
 * Each subsequent cash flow changes NAV per unit by amount / units held.
 */
public final class PrivateEquityValuation
{
    public static final int PROTO_CAPITAL_CALL = 1001;
    public static final int PROTO_DISTRIBUTION = 1002;

    private PrivateEquityValuation()
    {
    }

    public static long sharesAt(Client client, Security security, LocalDateTime date)
    {
        return client.getPortfolios().stream().flatMap(p -> p.getTransactions().stream())
                        .filter(t -> t.getSecurity() == security && !t.getDateTime().isAfter(date))
                        .mapToLong(t -> t.getType().isPurchase() ? t.getShares() : -t.getShares()).sum();
    }

    public static name.abuchen.portfolio.money.Money convert(AccountTransaction flow,
                    name.abuchen.portfolio.money.CurrencyConverter converter)
    {
        if (converter.getTermCurrency().equals(flow.getSecurity().getCurrencyCode()))
            return name.abuchen.portfolio.money.Money.of(converter.getTermCurrency(), amountInSecurityCurrency(flow));
        return converter.convert(flow.getDateTime(), flow.getMonetaryAmount());
    }

    public static long amountInSecurityCurrency(AccountTransaction flow)
    {
        if (flow.getCurrencyCode().equals(flow.getSecurity().getCurrencyCode()))
            return flow.getAmount();
        return flow.getUnit(Transaction.Unit.Type.GROSS_VALUE)
                        .filter(u -> u.getForex() != null
                                        && u.getForex().getCurrencyCode().equals(flow.getSecurity().getCurrencyCode()))
                        .orElseThrow(() -> new IllegalArgumentException("Missing capital flow exchange rate"))
                        .getForex().getAmount();
    }

    public static SecurityPrice price(Client client, Security security, LocalDate date)
    {
        return price(client, security, date, null, null);
    }

    private static SecurityPrice price(Client client, Security security, LocalDate date,
                    AccountTransaction exclude, AccountTransaction include)
    {
        List<AccountTransaction> flows = new ArrayList<>();
        client.getAccounts().stream().flatMap(a -> a.getTransactions().stream())
                        .filter(t -> t != exclude && t.getSecurity() == security && t.getType() != null
                                        && t.getType().isCapitalFlow() && !t.getDateTime().toLocalDate().isAfter(date))
                        .forEach(flows::add);
        if (include != null)
            flows.add(include);
        if (flows.isEmpty())
            return security.getUnadjustedSecurityPrice(date);
        flows.sort(Comparator.comparing(AccountTransaction::getDateTime));

        var base = security.getPricesIncludingLatest().stream().filter(p -> !p.getDate().isAfter(date))
                        .max(Comparator.comparing(SecurityPrice::getDate)).orElse(null);
        if (base == null)
        {
            // An initial delivery can establish units at zero NAV before the
            // first call. Never use a future fund NAV for a past valuation.
            var initial = client.getPortfolios().stream().flatMap(p -> p.getTransactions().stream())
                            .filter(t -> t.getSecurity() == security && t.getType().isPurchase() && t.getShares() > 0
                                            && !t.getDateTime().isAfter(flows.get(0).getDateTime()))
                            .min(Transaction.BY_DATE).orElse(null);
            long initialQuote = initial == null ? 0 : initialQuote(initial, security);
            base = new SecurityPrice(LocalDate.MIN, initialQuote);
        }
        BigDecimal value = BigDecimal.valueOf(base.getValue());
        LocalDate effectiveDate = base.getDate();
        for (var flow : flows)
        {
            if (!flow.getDateTime().toLocalDate().isAfter(base.getDate()))
                continue;
            long units = sharesAt(client, security, flow.getDateTime());
            if (units <= 0)
                throw new IllegalArgumentException("Capital flows require existing positive holdings: "
                                + security.getName());
            BigDecimal delta = BigDecimal.valueOf(amountInSecurityCurrency(flow))
                            .movePointRight(Values.Quote.precisionDeltaToMoney() + Values.Share.precision())
                            .divide(BigDecimal.valueOf(units), Values.MC);
            value = flow.getType() == AccountTransaction.Type.CAPITAL_CALL ? value.add(delta) : value.subtract(delta);
            if (value.signum() < 0)
                throw new IllegalArgumentException("Distribution exceeds the fund valuation: " + security.getName());
            effectiveDate = flow.getDateTime().toLocalDate();
        }
        return new SecurityPrice(effectiveDate, value.setScale(0, RoundingMode.HALF_EVEN).longValueExact());
    }

    private static long initialQuote(PortfolioTransaction initial, Security security)
    {
        if (initial.getCurrencyCode().equals(security.getCurrencyCode()) || initial.getGrossValueAmount() == 0)
            return initial.getGrossPricePerShare().getAmount();
        var forex = initial.getUnit(Transaction.Unit.Type.GROSS_VALUE)
                        .filter(u -> u.getForex() != null
                                        && u.getForex().getCurrencyCode().equals(security.getCurrencyCode()))
                        .orElseThrow(() -> new IllegalArgumentException("Enter an initial NAV in the fund's currency."))
                        .getForex();
        return BigDecimal.valueOf(forex.getAmount())
                        .movePointRight(Values.Quote.precisionDeltaToMoney() + Values.Share.precision())
                        .divide(BigDecimal.valueOf(initial.getShares()), Values.MC)
                        .setScale(0, RoundingMode.HALF_EVEN).longValueExact();
    }

    /** Validate before inserting/replacing a transaction, including later NAVs. */
    public static void validate(Client client, AccountTransaction candidate, AccountTransaction original)
    {
        if (candidate.getSecurity() == null || candidate.getAmount() <= 0 || candidate.getShares() != 0)
            throw new IllegalArgumentException("Select a fund and enter a positive capital flow amount.");
        if (sharesAt(client, candidate.getSecurity(), candidate.getDateTime()) <= 0)
            throw new IllegalArgumentException("Create the fund's units before recording a capital flow.");
        amountInSecurityCurrency(candidate);
        var dates = client.getAccounts().stream().flatMap(a -> a.getTransactions().stream())
                        .filter(t -> t != original && t.getSecurity() == candidate.getSecurity()
                                        && t.getType().isCapitalFlow())
                        .map(t -> t.getDateTime().toLocalDate()).collect(java.util.stream.Collectors.toSet());
        dates.add(candidate.getDateTime().toLocalDate());
        for (var date : dates)
            price(client, candidate.getSecurity(), date, original,
                            candidate.getDateTime().toLocalDate().isAfter(date) ? null : candidate);
    }
}
