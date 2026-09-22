package name.abuchen.portfolio.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;

import org.junit.Test;

import name.abuchen.portfolio.junit.TestCurrencyConverter;
import name.abuchen.portfolio.money.Money;
import name.abuchen.portfolio.money.Values;
import name.abuchen.portfolio.snapshot.ClientSnapshot;
import name.abuchen.portfolio.snapshot.PerformanceIndex;
import name.abuchen.portfolio.snapshot.PortfolioSnapshot;
import name.abuchen.portfolio.snapshot.security.LazySecurityPerformanceSnapshot;
import name.abuchen.portfolio.util.Interval;

@SuppressWarnings("nls")
public class PrivateEquityValuationTest
{
    private static final LocalDate START = LocalDate.of(2026, 1, 1);
    private final Client client = new Client();
    private final Security fund = new Security("PE fund", "EUR");
    private final Account cash = new Account("Cash");
    private final Portfolio portfolio = new Portfolio("PE holdings");
    private final TestCurrencyConverter converter = new TestCurrencyConverter();

    public PrivateEquityValuationTest()
    {
        client.setBaseCurrency("EUR");
        fund.setName("PE fund");
        cash.setName("Cash");
        portfolio.setName("PE holdings");
        client.addSecurity(fund);
        client.addAccount(cash);
        portfolio.setReferenceAccount(cash);
        client.addPortfolio(portfolio);
        fund.addPrice(new SecurityPrice(START, Values.Quote.factorize(100)));
        portfolio.addTransaction(new PortfolioTransaction(START.atStartOfDay(), "EUR", 1000000, fund,
                        Values.Share.factorize(100), PortfolioTransaction.Type.DELIVERY_INBOUND, 0, 0));
        cash.addTransaction(new AccountTransaction(START.atStartOfDay(), "EUR", 500000, null,
                        AccountTransaction.Type.DEPOSIT));
    }

    private AccountTransaction flow(int day, long amount, AccountTransaction.Type type)
    {
        var flow = new AccountTransaction(START.plusDays(day).atTime(12, 0), "EUR", amount, fund, type);
        PrivateEquityValuation.validate(client, flow, null);
        cash.addTransaction(flow);
        return flow;
    }

    private void example()
    {
        flow(1, 200000, AccountTransaction.Type.CAPITAL_CALL);
        flow(2, 50000, AccountTransaction.Type.DISTRIBUTION);
    }

    @Test
    public void initialForeignCurrencyDeliveryUsesFundCurrencyWhenNoNavExists()
    {
        fund.removePrice(fund.getPrices().get(0));
        fund.setCurrencyCode("USD");
        var initial = portfolio.getTransactions().get(0);
        initial.addUnit(new Transaction.Unit(Transaction.Unit.Type.GROSS_VALUE,
                        Money.of("EUR", 1000000), Money.of("USD", 1250000), new BigDecimal("0.8")));
        var call = new AccountTransaction(START.plusDays(1).atTime(12, 0), "EUR", 80000, fund,
                        AccountTransaction.Type.CAPITAL_CALL);
        call.addUnit(new Transaction.Unit(Transaction.Unit.Type.GROSS_VALUE,
                        Money.of("EUR", 80000), Money.of("USD", 100000), new BigDecimal("0.8")));
        PrivateEquityValuation.validate(client, call, null);
        cash.addTransaction(call);
        assertEquals(Values.Quote.factorize(135), fund.getSecurityPrice(START.plusDays(2)).getValue());
    }

    @Test
    public void newOperationsCanBeExportedAsJson()
    {
        example();
        for (var flow : cash.getTransactions())
        {
            if (flow.getType().isCapitalFlow())
                assertTrue(name.abuchen.portfolio.json.JTransaction.from(new TransactionPair<>(cash, flow))
                                .toJson().contains(flow.getType().name()));
        }
    }

    @Test
    public void cashFlowsAdjustNavAndPreserveUnitsAndTotalWealth()
    {
        example();
        assertEquals(Values.Quote.factorize(120), fund.getSecurityPrice(START.plusDays(1)).getValue());
        assertEquals(Values.Quote.factorize(115), fund.getSecurityPrice(START.plusDays(3)).getValue());
        var snapshot = PortfolioSnapshot.create(portfolio, converter, START.plusDays(3));
        assertEquals(Values.Share.factorize(100), snapshot.getPositions().get(0).getShares());
        assertEquals(1150000, snapshot.getValue().getAmount());
        assertEquals(350000, cash.getCurrentAmount(START.plusDays(3).atStartOfDay()));
        assertEquals(1500000, ClientSnapshot.create(client, converter, START.plusDays(3)).getMonetaryAssets().getAmount());
        assertEquals(1, fund.getPrices().size());
    }

    @Test
    public void flowsDoNotCreatePerformanceInClientSecurityPortfolioOrCashReports()
    {
        example();
        var period = Interval.of(START, START.plusDays(5));
        assertEquals(0, PerformanceIndex.forClient(client, converter, period, new ArrayList<>())
                        .getFinalAccumulatedPercentage(), 1e-10);
        assertEquals(0, PerformanceIndex.forInvestment(client, converter, fund, period, new ArrayList<>())
                        .getFinalAccumulatedPercentage(), 1e-10);
        assertEquals(0, PerformanceIndex.forPortfolio(client, converter, portfolio, period, new ArrayList<>())
                        .getFinalAccumulatedPercentage(), 1e-10);
        assertEquals(0, PerformanceIndex.forAccount(client, converter, cash, period, new ArrayList<>())
                        .getFinalAccumulatedPercentage(), 1e-10);
        var record = LazySecurityPerformanceSnapshot.create(client, converter, period).getRecords().get(0);
        assertEquals(0, record.getIrr(), 1e-8);
        assertEquals(0, record.getDelta().getAmount());
        for (var method : CostMethod.values())
        {
            assertEquals(1150000, record.getCost(method, TaxesAndFees.INCLUDED).getAmount());
            assertEquals(0, record.getCapitalGainsOnHoldings(method).getAmount());
            assertEquals(0, record.getUnrealizedCapitalGains(method).getCapitalGains().getAmount());
        }
    }

    @Test
    public void tradeReturnsIncludeCallsAndDistributions() throws Exception
    {
        example();
        var trade = new name.abuchen.portfolio.snapshot.trades.TradeCollector(client, converter).collect(fund).get(0);
        assertEquals(0, trade.getProfitLoss().getAmount());
        assertEquals(0, trade.getProfitLossMovingAverage().getAmount());
        assertEquals(0, trade.getIRR(), 1e-8);
    }

    @Test
    public void editsAndDeletionRecalculateWithoutLeavingSyntheticQuotes()
    {
        var call = flow(1, 200000, AccountTransaction.Type.CAPITAL_CALL);
        var distribution = flow(2, 50000, AccountTransaction.Type.DISTRIBUTION);
        call.setAmount(300000);
        assertEquals(Values.Quote.factorize(125), fund.getSecurityPrice(START.plusDays(3)).getValue());
        cash.deleteTransaction(distribution, client);
        assertEquals(Values.Quote.factorize(130), fund.getSecurityPrice(START.plusDays(3)).getValue());
        cash.deleteTransaction(call, client);
        assertEquals(Values.Quote.factorize(100), fund.getSecurityPrice(START.plusDays(3)).getValue());
    }

    @Test
    public void reportedNavReplacesEarlierAdjustmentsIncludingSameDayFlows()
    {
        example();
        fund.addPrice(new SecurityPrice(START.plusDays(2), Values.Quote.factorize(118)));
        flow(3, 10000, AccountTransaction.Type.DISTRIBUTION);
        assertEquals(Values.Quote.factorize(118), fund.getSecurityPrice(START.plusDays(2)).getValue());
        assertEquals(Values.Quote.factorize(117), fund.getSecurityPrice(START.plusDays(3)).getValue());
        assertEquals(Values.Quote.factorize(120), fund.getSecurityPrice(START.plusDays(1)).getValue());
    }

    @Test
    public void completeDistributionCanLeaveZeroNavWithUnitsStillHeld()
    {
        flow(1, 1000000, AccountTransaction.Type.DISTRIBUTION);
        assertEquals(0, PortfolioSnapshot.create(portfolio, converter, START.plusDays(2)).getValue().getAmount());
        assertEquals(0, PerformanceIndex.forClient(client, converter, Interval.of(START, START.plusDays(2)),
                        new ArrayList<>()).getFinalAccumulatedPercentage(), 1e-10);
    }

    @Test
    public void rejectsDistributionAboveNavAndCallsWithoutUnits()
    {
        var excessive = new AccountTransaction(START.plusDays(1).atStartOfDay(), "EUR", 1000001, fund,
                        AccountTransaction.Type.DISTRIBUTION);
        assertThrows(IllegalArgumentException.class, () -> PrivateEquityValuation.validate(client, excessive, null));
        var early = new AccountTransaction(START.minusDays(1).atStartOfDay(), "EUR", 1000, fund,
                        AccountTransaction.Type.CAPITAL_CALL);
        assertThrows(IllegalArgumentException.class, () -> PrivateEquityValuation.validate(client, early, null));
        assertEquals(1, cash.getTransactions().size());
    }

    @Test
    public void usesAllUnitsAcrossPortfolios()
    {
        var second = new Portfolio("Second holding");
        second.setReferenceAccount(cash);
        client.addPortfolio(second);
        second.addTransaction(new PortfolioTransaction(START.atStartOfDay(), "EUR", 1000000, fund,
                        Values.Share.factorize(100), PortfolioTransaction.Type.DELIVERY_INBOUND, 0, 0));
        flow(1, 200000, AccountTransaction.Type.CAPITAL_CALL);
        assertEquals(Values.Quote.factorize(110), fund.getSecurityPrice(START.plusDays(2)).getValue());
        assertEquals(2500000, ClientSnapshot.create(client, converter, START.plusDays(2)).getMonetaryAssets().getAmount());
        assertEquals(0, PerformanceIndex.forPortfolio(client, converter, portfolio,
                        Interval.of(START, START.plusDays(2)), new ArrayList<>()).getFinalAccumulatedPercentage(), 1e-10);
        assertEquals(0, PerformanceIndex.forPortfolio(client, converter, second,
                        Interval.of(START, START.plusDays(2)), new ArrayList<>()).getFinalAccumulatedPercentage(), 1e-10);
    }

    @Test
    public void multipleFlowsOnOneDayAccumulate()
    {
        flow(1, 200000, AccountTransaction.Type.CAPITAL_CALL);
        flow(1, 50000, AccountTransaction.Type.DISTRIBUTION);
        assertEquals(Values.Quote.factorize(115), fund.getSecurityPrice(START.plusDays(1)).getValue());
    }

    @Test
    public void recordedExchangeRateDeterminesFundCurrencyAmount()
    {
        var usd = new Account("USD cash");
        usd.setCurrencyCode("USD");
        client.addAccount(usd);
        var call = new AccountTransaction(START.plusDays(1).atStartOfDay(), "USD", 220000, fund,
                        AccountTransaction.Type.CAPITAL_CALL);
        call.addUnit(new Transaction.Unit(Transaction.Unit.Type.GROSS_VALUE, Money.of("USD", 220000),
                        Money.of("EUR", 200000), new BigDecimal("1.1")));
        PrivateEquityValuation.validate(client, call, null);
        usd.addTransaction(call);
        assertEquals(Values.Quote.factorize(120), fund.getSecurityPrice(START.plusDays(2)).getValue());
    }

    @Test
    public void binaryAndXmlRoundTripsPreserveFlowsAndDerivedValuations() throws Exception
    {
        example();
        var now = java.time.Instant.now();
        client.getSecurities().forEach(s -> s.setUpdatedAt(now));
        client.getAccounts().forEach(a -> { a.setUpdatedAt(now); a.getTransactions().forEach(t -> t.setUpdatedAt(now)); });
        client.getPortfolios().forEach(p -> { p.setUpdatedAt(now); p.getTransactions().forEach(t -> t.setUpdatedAt(now)); });
        var output = new ByteArrayOutputStream();
        var protobuf = new ProtobufWriter();
        protobuf.save(client, output);
        assertRoundTrip(protobuf.load(new ByteArrayInputStream(output.toByteArray())));
        output.reset();
        new ClientFactory.XmlSerialization(false).save(client, output);
        assertRoundTrip(ClientFactory.load(new ByteArrayInputStream(output.toByteArray())));
    }

    private void assertRoundTrip(Client restored)
    {
        assertEquals(Values.Quote.factorize(115), restored.getSecurities().get(0)
                        .getSecurityPrice(START.plusDays(3)).getValue());
        assertTrue(restored.getAccounts().get(0).getTransactions().stream()
                        .anyMatch(t -> t.getType() == AccountTransaction.Type.CAPITAL_CALL));
        assertTrue(restored.getAccounts().get(0).getTransactions().stream()
                        .anyMatch(t -> t.getType() == AccountTransaction.Type.DISTRIBUTION));
    }
}
