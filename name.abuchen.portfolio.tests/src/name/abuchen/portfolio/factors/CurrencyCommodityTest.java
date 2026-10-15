package name.abuchen.portfolio.factors;

import static org.junit.Assert.*;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Map;

import com.google.gson.JsonParser;
import org.junit.Test;

import name.abuchen.portfolio.junit.TestCurrencyConverter;
import name.abuchen.portfolio.model.Account;
import name.abuchen.portfolio.model.Classification;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Portfolio;
import name.abuchen.portfolio.model.PortfolioTransaction;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.model.SecurityPrice;
import name.abuchen.portfolio.model.Taxonomy;
import name.abuchen.portfolio.money.Values;

@SuppressWarnings("nls")
public class CurrencyCommodityTest
{
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 30);

    private Classification currency(Taxonomy tax, String code)
    {
        return tax.getAllClassifications().stream().filter(c -> c.getKey().equals("factor.fx." + code)).findFirst().orElseThrow();
    }

    @Test
    public void taxonomyIsIdempotentAndDoesNotInferFundCurrency()
    {
        var client = new Client();
        var cash = new Account("USD cash"); cash.setCurrencyCode("USD"); client.addAccount(cash);
        var fund = new Security("World EUR listing", "EUR"); client.addSecurity(fund);
        var dbi = new Security("DBi", "EUR"); dbi.setIsin(FactorMap.UCITS_HEDGED); client.addSecurity(dbi);
        var tax = CurrencyExposure.ensure(client);
        assertSame(tax, CurrencyExposure.ensure(client));
        assertEquals(1, client.getTaxonomies().size());
        assertEquals(cash, currency(tax, "USD").getAssignments().getFirst().getInvestmentVehicle());
        assertEquals(fund, currency(tax, "UNKNOWN").getAssignments().getFirst().getInvestmentVehicle());
        assertEquals(dbi, currency(tax, "DBI").getAssignments().getFirst().getInvestmentVehicle());
        tax.setName("Devises renommées");
        assertSame(tax, CurrencyExposure.find(client));
        var values = new EnumMap<Factor, Double>(Factor.class);
        var covered = new EnumMap<Factor, Double>(Factor.class);
        CurrencyExposure.apply(tax, cash, values, covered);
        assertEquals(1, values.get(Factor.USD_EUR), 1e-10);
        CurrencyExposure.apply(tax, fund, values, covered);
        assertTrue(values.isEmpty());
    }

    @Test
    public void partialCurrencyMappingKeepsUnknownAndUsesStableKeys()
    {
        var client = new Client();
        var tax = CurrencyExposure.ensure(client);
        var fund = new Security("Mixed fund", "EUR"); client.addSecurity(fund);
        int one = Classification.ONE_HUNDRED_PERCENT;
        currency(tax, "USD").setName("Dollar américain");
        currency(tax, "USD").addAssignment(new Classification.Assignment(fund, one / 2));
        currency(tax, "JPY").addAssignment(new Classification.Assignment(fund, one / 5));
        currency(tax, "UNKNOWN").addAssignment(new Classification.Assignment(fund, one * 3 / 10));
        var values = new EnumMap<Factor, Double>(Factor.class);
        var covered = new EnumMap<Factor, Double>(Factor.class);
        CurrencyExposure.apply(tax, fund, values, covered);
        assertEquals(.5, values.get(Factor.USD_EUR), 1e-10);
        assertEquals(.2, values.get(Factor.JPY_EUR), 1e-10);
        assertEquals(.7, covered.get(Factor.USD_EUR), 1e-10);
        currency(tax, "EUR").addAssignment(new Classification.Assignment(fund, one / 10));
        assertTrue(CurrencyExposure.apply(tax, fund, values, covered).contains("invalide"));
        assertTrue(values.isEmpty());
        assertTrue(covered.isEmpty());
    }

    @Test
    public void negativeAndOtherCurrencyAssignmentsNeverBecomeCompleteZeros()
    {
        var client = new Client();
        var tax = CurrencyExposure.ensure(client);
        var fund = new Security("CHF fund", "EUR");
        currency(tax, "CHF").addAssignment(new Classification.Assignment(fund));
        var values = new EnumMap<Factor, Double>(Factor.class);
        var covered = new EnumMap<Factor, Double>(Factor.class);
        CurrencyExposure.apply(tax, fund, values, covered);
        assertTrue(values.isEmpty());
        currency(tax, "CHF").getAssignments().getFirst().setWeight(-1);
        assertTrue(CurrencyExposure.apply(tax, fund, values, covered).contains("invalide"));
    }

    private String officialJson()
    {
        var s = InvescoData.bundled();
        var object = new com.google.gson.JsonObject();
        object.addProperty("effectiveDate", s.date());
        var array = new com.google.gson.JsonArray();
        for (var h : s.holdings())
        {
            var row = new com.google.gson.JsonObject();
            row.addProperty("name", h.name()); row.addProperty("weight", h.weight() * 100); array.add(row);
        }
        object.add("holdings", array);
        return object.toString();
    }

    @Test
    public void officialIndexHasSeparateWtiAndBrentAndDatedUnscaledWeights()
    {
        var data = InvescoData.parse(officialJson(), TODAY);
        assertEquals("2026-04-30", data.date());
        assertEquals(25, data.holdings().size());
        assertEquals(.124622, data.exposure(Factor.GOLD, TODAY), 1e-10);
        assertEquals(.094964, data.exposure(Factor.OIL, TODAY), 1e-10);
        assertEquals(.124133, data.holdings().stream().filter(h -> h.label().equals("Pétrole Brent")).findFirst().orElseThrow().weight(), 1e-10);
        assertNull(data.exposure(Factor.GOLD, LocalDate.of(2026, 4, 29)));
    }

    @Test
    public void malformedTruncatedCollateralFutureAndDuplicateSourcesAreRejected()
    {
        var source = JsonParser.parseString(officialJson()).getAsJsonObject();
        source.addProperty("effectiveDate", "2026-10-01");
        assertThrows(IllegalArgumentException.class, () -> InvescoData.parse(source.toString(), TODAY));
        source.addProperty("effectiveDate", "2026-04-30");
        var rows = source.getAsJsonArray("holdings");
        rows.add(rows.get(0));
        assertThrows(IllegalArgumentException.class, () -> InvescoData.parse(source.toString(), TODAY));
        rows.remove(rows.size() - 1);
        rows.remove(rows.size() - 1);
        assertThrows(IllegalArgumentException.class, () -> InvescoData.parse(source.toString(), TODAY));
        assertThrows(IllegalArgumentException.class, () -> new InvescoData.Holding("US Treasury Bill", .1));
        assertThrows(IllegalArgumentException.class, () -> new InvescoData.Holding("Gold Future", Double.NaN));
    }

    @Test
    public void savedSourcesRoundTripAndGuardConcurrentUpdates()
    {
        var client = new Client();
        var data = InvescoData.bundled();
        InvescoData.save(client, data, null);
        assertEquals(data, InvescoData.load(client));
        assertThrows(IllegalArgumentException.class, () -> InvescoData.save(client, data, null));
    }

    @Test
    public void actualHoldingsUseBothSourcesAndExplicitOverridesWin()
    {
        var client = new Client(); client.setBaseCurrency("EUR");
        var portfolio = new Portfolio(); client.addPortfolio(portfolio);
        var fund = new Security("Invesco", "EUR"); fund.setIsin(InvescoData.ISIN);
        fund.addPrice(new SecurityPrice(TODAY, Values.Quote.factor())); client.addSecurity(fund);
        var transaction = new PortfolioTransaction(); transaction.setSecurity(fund);
        transaction.setType(PortfolioTransaction.Type.DELIVERY_INBOUND); transaction.setDateTime(TODAY.atStartOfDay());
        transaction.setCurrencyCode("EUR"); transaction.setAmount(10000); transaction.setShares(100 * Values.Share.factor());
        portfolio.addTransaction(transaction);
        var tax = CurrencyExposure.ensure(client);
        currency(tax, "UNKNOWN").removeAssignment(currency(tax, "UNKNOWN").getAssignments().getFirst());
        currency(tax, "USD").addAssignment(new Classification.Assignment(fund));
        var result = FactorMap.calculate(client, new TestCurrencyConverter(), TODAY, false, false, false, DbiData.bundled());
        var position = result.positions().getFirst();
        assertEquals(.124622, position.exposures().get(Factor.GOLD), 1e-10);
        assertEquals(.094964, position.exposures().get(Factor.OIL), 1e-10);
        assertEquals(1, position.exposures().get(Factor.USD_EUR), 1e-10);
        assertEquals(1, position.coverage().get(Factor.OIL), 1e-10);
        assertNull(position.exposures().get(Factor.US_2Y));
        var mapping = new FactorSettings.Mapping(TODAY.toString(), "Explicit scenario", Map.of(Factor.GOLD, .2));
        FactorSettings.save(client, FactorSettings.load(client).with(fund.getUUID(), mapping), null);
        result = FactorMap.calculate(client, new TestCurrencyConverter(), TODAY, false, false, false, DbiData.bundled());
        assertEquals(.2, result.positions().getFirst().exposures().get(Factor.GOLD), 1e-10);
        assertNull(result.positions().getFirst().exposures().get(Factor.OIL));
    }
}
