package name.abuchen.portfolio.supportcosts;

import static org.junit.Assert.*;
import java.time.LocalDate;
import org.junit.Test;
import name.abuchen.portfolio.junit.TestCurrencyConverter;
import name.abuchen.portfolio.model.*;
import name.abuchen.portfolio.money.Values;
import name.abuchen.portfolio.snapshot.filter.ClientFilter;
import name.abuchen.portfolio.snapshot.ReportingPeriod;
import name.abuchen.portfolio.tax.TaxScope;
import name.abuchen.portfolio.util.Interval;

@SuppressWarnings("nls")
public class SupportCostsTest
{
    private final Client client = new Client();
    private final Portfolio portfolio = new Portfolio("Test");
    private final LocalDate start = LocalDate.of(2024, 12, 31);
    public SupportCostsTest() { client.setBaseCurrency("EUR"); client.addPortfolio(portfolio); }
    private Security add(String name, Double rate)
    {
        var s = new Security(name, "EUR"); client.addSecurity(s);
        s.addPrice(new SecurityPrice(start, Values.Quote.factorize(100)));
        portfolio.addTransaction(new PortfolioTransaction(start.atStartOfDay(), "EUR", 100_00,
                        s, Values.Share.factorize(1), PortfolioTransaction.Type.DELIVERY_INBOUND, 0, 0));
        if (rate != null) s.getAttributes().put(SupportCosts.ter(client), rate);
        return s;
    }
    private SupportCosts.Result result(LocalDate end)
    {
        return SupportCosts.calculate(client, TaxScope.of(client, ClientFilter.NO_FILTER, null),
                        new TestCurrencyConverter(), Interval.of(start, end));
    }
    @Test public void fullYearAndNoFinancialMutation()
    {
        var s = add("ETF", .01); var r = result(LocalDate.of(2025,12,31));
        assertEquals(100, r.estimatedCost()); assertEquals(.01, r.annualRate(), 1e-10);
        assertEquals(.01, r.periodRate(), 1e-10); assertTrue(r.complete());
        assertEquals(1, portfolio.getTransactions().size()); assertEquals(1, s.getPrices().size());
    }
    @Test public void missingRateIsNotZero()
    {
        add("Known", .01); add("Unknown", null); var r = result(LocalDate.of(2025,12,31));
        assertEquals(100, r.estimatedCost()); assertEquals(.5, r.coverage(), 1e-10);
        assertEquals(.01, r.annualRate(), 1e-10); assertNull(r.scopeAnnualRate()); assertFalse(r.complete());
    }
    @Test public void datedSaleStopsDailyFees()
    {
        var s = add("Sold", .01);
        portfolio.addTransaction(new PortfolioTransaction(LocalDate.of(2025,1,11).atStartOfDay(), "EUR", 100_00,
                        s, Values.Share.factorize(1), PortfolioTransaction.Type.DELIVERY_OUTBOUND, 0, 0));
        var r = result(LocalDate.of(2025,12,31));
        assertEquals(10, r.rows().getFirst().days()); assertEquals(3, r.estimatedCost());
    }
    @Test public void historicalPricesAndWeighting()
    {
        var s = add("Price change", .02); add("Zero", 0d);
        s.addPrice(new SecurityPrice(LocalDate.of(2025,1,2), Values.Quote.factorize(200)));
        var r = result(LocalDate.of(2025,1,2));
        assertEquals(.012, r.annualRate(), 1e-10); assertEquals(2, r.estimatedCost());
        assertEquals(250_00, r.averageAssets());
    }
    @Test public void leapYearAndSupplement()
    {
        assertEquals(1, SupportCosts.dailyCost(36600, .01, LocalDate.of(2024,2,29)), 1e-10);
        assertEquals(1, SupportCosts.dailyCost(36500, .01, LocalDate.of(2025,2,28)), 1e-10);
        var s = add("All-in", .01); SupportCosts.installAttributes(client);
        var extra = client.getSettings().getAttributeTypes().filter(a -> a.getId().equals(SupportCosts.EXTRA)).findFirst().orElseThrow();
        s.getAttributes().put(extra, .002); assertEquals(.012, SupportCosts.annualRate(client, s), 1e-10);
    }
    @Test public void futureQuoteIsExcluded()
    {
        var s = add("Future only", .01); s.removePrice(s.getPrices().getFirst());
        s.addPrice(new SecurityPrice(LocalDate.of(2025,1,5), Values.Quote.factorize(100)));
        var r = result(LocalDate.of(2025,1,2)); assertEquals(0, r.estimatedCost());
        assertEquals(2, r.unavailableValuations()); assertFalse(r.complete());
    }
    @Test public void fractionalClassification()
    {
        var s = add("Fractional", .01); var cl = new Classification("partial", "Partial");
        cl.addAssignment(new Classification.Assignment(s, Classification.ONE_HUNDRED_PERCENT / 2));
        var r = SupportCosts.calculate(client, TaxScope.of(client, ClientFilter.NO_FILTER, cl),
                        new TestCurrencyConverter(), Interval.of(start, LocalDate.of(2025,12,31)));
        assertEquals(50, r.estimatedCost()); assertEquals(.01, r.annualRate(), 1e-10);
    }
    @Test public void foreignSupportAndCashUseHistoricalFx()
    {
        var day = LocalDate.of(2015,1,2);
        var s = new Security("USD fund", "USD"); client.addSecurity(s);
        s.addPrice(new SecurityPrice(day, Values.Quote.factorize(120.43)));
        s.getAttributes().put(SupportCosts.ter(client), .365);
        portfolio.addTransaction(new PortfolioTransaction(day.atStartOfDay(), "USD", 12043, s,
                        Values.Share.factorize(1), PortfolioTransaction.Type.DELIVERY_INBOUND, 0, 0));
        var cash = new Account("USD cash"); cash.setCurrencyCode("USD"); client.addAccount(cash);
        cash.addTransaction(new AccountTransaction(day.atStartOfDay(), "USD", 12043, null, AccountTransaction.Type.DEPOSIT));
        var r = SupportCosts.calculate(client, TaxScope.of(client, ClientFilter.NO_FILTER, null),
                        new TestCurrencyConverter(), Interval.of(day.minusDays(1), day));
        assertEquals(10, r.estimatedCost()); assertEquals(20000, r.averageAssets());
        assertEquals(.365, r.annualRate(), 1e-10); assertEquals(.1825, r.scopeAnnualRate(), 1e-10);
    }
    @Test public void negativeHistoricalPositionDoesNotCreateNegativeFees()
    {
        var s = add("Negative history", .01);
        portfolio.addTransaction(new PortfolioTransaction(start.plusDays(1).atStartOfDay(), "EUR", 200_00,
                        s, Values.Share.factorize(2), PortfolioTransaction.Type.DELIVERY_OUTBOUND, 0, 0));
        var r = result(start.plusDays(2)); assertEquals(0, r.estimatedCost());
        assertEquals(2, r.unavailableValuations()); assertFalse(r.complete());
        assertTrue(r.rows().getFirst().note().contains("position négative"));
    }
    @Test public void currentQuarterStopsAtCalculationDate()
    {
        var today = LocalDate.of(2026, 10, 13);
        var requested = new ReportingPeriod.CurrentQuarter().toInterval(today);
        var elapsed = SupportCosts.effectivePeriod(requested, today);
        assertEquals(LocalDate.of(2026, 9, 30), elapsed.getStart());
        assertEquals(today, elapsed.getEnd());
        assertEquals(13, elapsed.getDays());
        add("ETF", .01);
        var actualToday = LocalDate.now();
        var r = SupportCosts.calculate(client, TaxScope.of(client, ClientFilter.NO_FILTER, null),
                        new TestCurrencyConverter(), new ReportingPeriod.CurrentQuarter().toInterval(actualToday));
        assertEquals(actualToday, r.period().getEnd());
        assertEquals(r.period().getDays(), r.rows().getFirst().days());
        double expected = 0;
        for (var day = r.period().getStart().plusDays(1); !day.isAfter(actualToday); day = day.plusDays(1))
            expected += SupportCosts.dailyCost(100_00, .01, day);
        assertEquals(Math.round(expected), r.estimatedCost());
    }
    @Test public void calendarPeriodsAndPastPeriods()
    {
        var today = LocalDate.of(2026, 10, 13);
        assertEquals(today, SupportCosts.effectivePeriod(new ReportingPeriod.CurrentMonth().toInterval(today), today).getEnd());
        assertEquals(today, SupportCosts.effectivePeriod(new ReportingPeriod.CurrentWeek().toInterval(today), today).getEnd());
        var past = Interval.of(start, LocalDate.of(2025, 12, 31));
        assertEquals(past, SupportCosts.effectivePeriod(past, today));
    }
    @Test(expected = IllegalArgumentException.class) public void futurePeriodHasNoEstimate()
    {
        var today = LocalDate.of(2026, 10, 13);
        SupportCosts.effectivePeriod(Interval.of(today, today.plusDays(10)), today);
    }
}
