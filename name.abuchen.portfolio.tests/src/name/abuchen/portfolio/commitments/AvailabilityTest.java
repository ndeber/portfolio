package name.abuchen.portfolio.commitments;

import static org.junit.Assert.*;
import java.time.LocalDate;
import java.util.List;
import org.junit.Test;
import name.abuchen.portfolio.junit.TestCurrencyConverter;
import name.abuchen.portfolio.model.*;
import name.abuchen.portfolio.money.Values;

public class AvailabilityTest
{
    private final LocalDate date = LocalDate.of(2026, 9, 25);
    private final Client client = new Client();
    private final Taxonomy taxonomy = new Taxonomy(Availability.NAME);
    private final TestCurrencyConverter converter = new TestCurrencyConverter();
    public AvailabilityTest()
    {
        client.setBaseCurrency("EUR"); taxonomy.setRootNode(new Classification("root", Availability.NAME)); client.addTaxonomy(taxonomy);
    }
    private Account account(String name, long amount)
    {
        var account = new Account(name); account.setCurrencyCode("EUR"); client.addAccount(account);
        account.addTransaction(new AccountTransaction(date.atStartOfDay(), "EUR", amount, null, AccountTransaction.Type.DEPOSIT)); return account;
    }
    private void holding(String name, Security security, long shares)
    {
        var portfolio = new Portfolio(); portfolio.setName(name); client.addPortfolio(portfolio);
        var tx = new PortfolioTransaction(); tx.setType(PortfolioTransaction.Type.BUY); tx.setDateTime(date.atStartOfDay());
        tx.setSecurity(security); tx.setCurrencyCode("EUR"); tx.setShares(shares * Values.Share.factor()); tx.setAmount(shares * 10000);
        portfolio.addTransaction(tx);
    }
    private Classification assign(String name, InvestmentVehicle vehicle, int weight)
    {
        var category = new Classification(taxonomy.getRoot(), java.util.UUID.randomUUID().toString(), name);
        taxonomy.getRoot().addChild(category); category.addAssignment(new Classification.Assignment(vehicle, weight)); return category;
    }
    @Test public void onlyXapaPositionsAreCountedEvenWhenSecurityIsShared()
    {
        var cash = account("Xapa - Bank", 100000); account("Perso - Bank", 900000);
        var security = new Security("Listed", "EUR"); client.addSecurity(security);
        holding("Xapa - Shares", security, 10); holding("Perso - Shares", security, 30);
        assign("Immédiate", cash, 10000); assign("2028", security, 6000); assign("2030", security, 4000);
        var result = Availability.calculate(client, taxonomy, converter, date);
        assertEquals(200000, result.total()); assertEquals(100000, result.amount(0)); assertEquals(60000, result.amount(2028)); assertEquals(40000, result.amount(2030));
        assertEquals(0, result.amount(-1));
        assertEquals(4, client.getPortfolios().size() + client.getAccounts().size());
    }
    @Test public void phacetExcludedAndIncompleteWeightsRemainUnplanned()
    {
        account("Xapa - Bank", 0);
        var phacet = new Security("Phacet - Chris", "EUR"); client.addSecurity(phacet); holding("Xapa - Direct", phacet, 10); assign("2034", phacet, 10000);
        var fund = new Security("Fund", "EUR"); client.addSecurity(fund); holding("Xapa - PE", fund, 10); assign("2031", fund, 2500);
        var result = Availability.calculate(client, taxonomy, converter, date);
        assertEquals(100000, result.total()); assertEquals(25000, result.amount(2031)); assertEquals(75000, result.amount(-1)); assertEquals(0, result.amount(2034));
        assign("2032", fund, 8000);
        assertThrows(IllegalArgumentException.class, () -> Availability.calculate(client, taxonomy, converter, date));
    }
    @Test public void amountsRevalueAndRoundingPreservesTotalAndSurvivesSerialization() throws Exception
    {
        var cash = account("Xapa - Bank", 101); assign("2028", cash, 3333); assign("2029", cash, 3333); assign("2030", cash, 3334);
        var result = Availability.calculate(client, taxonomy, converter, date);
        assertEquals(101, result.amounts().values().stream().mapToLong(Long::longValue).sum());
        var copy = ClientFactory.duplicate(client);
        assertEquals(result.amounts(), Availability.calculate(copy, Availability.taxonomy(copy, null), converter, date).amounts());
        cash.addTransaction(new AccountTransaction(date.atStartOfDay(), "EUR", 101, null, AccountTransaction.Type.DEPOSIT));
        assertEquals(202, Availability.calculate(client, taxonomy, converter, date).total());
    }
    @Test public void commitmentsUseOnlyXapaTransactionsAndNeverIncludePhacet()
    {
        var cash = account("Xapa - Bank", 500000);
        var fund = new Security("Fund", "EUR"); client.addSecurity(fund); holding("Xapa - PE", fund, 10);
        cash.addTransaction(new AccountTransaction(date.atStartOfDay(), "EUR", 20000, fund, AccountTransaction.Type.CAPITAL_CALL));
        Commitments.save(client, fund, 200000L, List.of(0L, 30000L, 50000L, 0L));
        var other = new Security("Personal PE", "EUR"); client.addSecurity(other); holding("Perso - PE", other, 20);
        Commitments.save(client, other, 900000L, List.of(0L, 0L, 0L, 700000L));
        var result = Availability.calculate(client, taxonomy, converter, date);
        assertEquals(1, result.commitments().rows().size()); assertEquals(120000, result.commitments().paid()); assertEquals(80000, result.commitments().remaining());
        assertEquals(List.of(0L, 30000L, 50000L, 0L), result.commitments().forecast());
    }
    @Test public void realConverterFallbackIsRejectedForMissingForeignRates()
    {
        var factory = new name.abuchen.portfolio.money.ExchangeRateProviderFactory(client)
        {
            @Override public name.abuchen.portfolio.money.ExchangeRateTimeSeries getTimeSeries(String base, String term)
            { return new name.abuchen.portfolio.money.impl.ExchangeRateTimeSeriesImpl(null, base, term); }
        };
        var real = new name.abuchen.portfolio.money.CurrencyConverterImpl(factory, "EUR");
        assertThrows(name.abuchen.portfolio.money.MonetaryException.class,
                        () -> Commitments.strictEur(real).getRate(date, "USD"));
        assertEquals(java.math.BigDecimal.ONE, Commitments.strictEur(real).getRate(date, "EUR").getValue());
    }
}
