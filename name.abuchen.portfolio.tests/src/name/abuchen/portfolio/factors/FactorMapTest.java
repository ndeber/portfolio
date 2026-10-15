package name.abuchen.portfolio.factors;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;

import org.junit.Test;

import name.abuchen.portfolio.junit.TestCurrencyConverter;
import name.abuchen.portfolio.model.Account;
import name.abuchen.portfolio.model.AccountTransaction;
import name.abuchen.portfolio.model.Classification;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Portfolio;
import name.abuchen.portfolio.model.PortfolioTransaction;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.model.SecurityPrice;
import name.abuchen.portfolio.model.Taxonomy;
import name.abuchen.portfolio.money.Values;

@SuppressWarnings("nls")
public class FactorMapTest
{
    private final LocalDate today = LocalDate.of(2026, 9, 30);
    private final Client client = new Client();
    private final Account cash = new Account("Cash EUR");
    private final Portfolio portfolio = new Portfolio();

    public FactorMapTest()
    {
        client.setBaseCurrency("EUR");
        cash.setCurrencyCode("EUR");
        client.addAccount(cash);
        portfolio.setName("CTO");
        portfolio.setReferenceAccount(cash);
        client.addPortfolio(portfolio);
    }

    private Security holding(String name, String isin, long eur)
    {
        var security = new Security(name, "EUR");
        security.setIsin(isin);
        security.addPrice(new SecurityPrice(today.minusDays(1), Values.Quote.factor()));
        client.addSecurity(security);
        var transaction = new PortfolioTransaction();
        transaction.setSecurity(security);
        transaction.setDateTime(today.minusDays(5).atStartOfDay());
        transaction.setType(PortfolioTransaction.Type.DELIVERY_INBOUND);
        transaction.setCurrencyCode("EUR");
        transaction.setShares(eur * Values.Share.factor());
        transaction.setAmount(eur * 100);
        portfolio.addTransaction(transaction);
        return security;
    }

    private void deposit(long eur)
    {
        var t = new AccountTransaction();
        t.setDateTime(today.minusDays(5).atStartOfDay());
        t.setType(AccountTransaction.Type.DEPOSIT);
        t.setCurrencyCode("EUR");
        t.setAmount(eur * 100);
        cash.addTransaction(t);
    }

    private FactorMap.Result result(boolean proxy, boolean va)
    {
        return FactorMap.calculate(client, new TestCurrencyConverter(), today, false, proxy, va, DbiData.bundled());
    }

    private FactorMap.Row row(FactorMap.Result result, Factor factor)
    {
        return result.rows().stream().filter(r -> r.factor() == factor).findFirst().orElseThrow();
    }

    private Taxonomy taxonomy(String name, String classification, Security security)
    {
        var tax = new Taxonomy(name);
        var root = new Classification("root-" + name, name);
        tax.setRootNode(root);
        var child = new Classification(root, "child-" + classification, classification);
        root.addChild(child);
        child.addAssignment(new Classification.Assignment(security));
        client.addTaxonomy(tax);
        return tax;
    }

    @Test
    public void ucitsIsNotSilentlyDbmfAndDenominatorIncludesDbi()
    {
        holding("Gold", "IE00B4ND3602", 100);
        holding("DBi", FactorMap.UCITS_HEDGED, 50);
        deposit(850);
        var result = result(false, false);
        assertEquals(100000, result.assets());
        assertEquals(.05, result.dbiWeight(), 1e-10);
        var gold = row(result, Factor.GOLD);
        assertEquals(.1, gold.baseline(), 1e-10);
        assertNull(gold.overlay());
        assertNull(gold.total());
    }

    @Test
    public void proxyIsExplicitSignedAndUsesActualWeight()
    {
        holding("Gold", "IE00B4ND3602", 100);
        holding("DBi", FactorMap.UCITS_HEDGED, 50);
        deposit(850);
        var r = result(true, false);
        var gold = row(r, Factor.GOLD);
        double expected = .05 * 299291120 / 5207645294.86;
        assertEquals(expected, gold.overlay(), 1e-10);
        assertEquals(.1 + expected, gold.total(), 1e-10);
        assertTrue(row(r, Factor.US_2Y).overlay() < 0);
        assertTrue(r.issues().stream().anyMatch(i -> i.startsWith("SIMULATION")));
    }

    @Test
    public void vaNeverAddsToNotionalBaseline()
    {
        holding("Gold", "IE00B4ND3602", 100);
        holding("DBi", FactorMap.UCITS_HEDGED, 50);
        deposit(850);
        var gold = row(result(true, true), Factor.GOLD);
        assertEquals(.008, gold.overlay(), 1e-10);
        assertNull(gold.baseline());
        assertNull(gold.knownAfter());
        assertNull(gold.total());
    }

    @Test
    public void dbmfHeldDirectlyNeedsNoProxy()
    {
        holding("DBMF", FactorMap.DBMF, 50);
        deposit(950);
        assertNotNull(row(result(false, false), Factor.US_EQUITY).overlay());
    }

    @Test
    public void unknownBaselinePreventsFalseTotal()
    {
        holding("Unknown fund", "UNKNOWN", 100);
        deposit(900);
        var gold = row(result(false, false), Factor.GOLD);
        assertEquals(.9, gold.coverage(), 1e-10);
        assertEquals(0d, gold.baseline(), 1e-10);
        assertEquals(0d, gold.knownAfter(), 1e-10);
        assertNull(gold.total());
    }

    @Test
    public void geographyDoesNotInventFxAndBondsDoNotUseMaturityAsDuration()
    {
        var equity = holding("US equity", "EQ", 100);
        taxonomy("Classes d'actifs", "Actions", equity);
        taxonomy("Actions - Régions (MSCI)", "États-Unis", equity);
        var r = result(false, false);
        assertEquals(1d, row(r, Factor.US_EQUITY).baseline(), 1e-10);
        assertNull(row(r, Factor.USD_EUR).baseline());
        var bondClient = new Client();
        var bonds = new Taxonomy("Classes d'actifs");
        var root = new Classification("root", "root");
        bonds.setRootNode(root);
        var c = new Classification(root, "bond", "Obligations");
        root.addChild(c);
        c.addAssignment(new Classification.Assignment(equity));
        bondClient.addTaxonomy(bonds);
        assertFalse(FactorMap.automatic(equity, bonds, null).containsKey(Factor.US_10Y));
    }

    @Test
    public void koreaIsEmergingNotEafeAndCanadaRemainsOutsideAxes()
    {
        var equity = holding("Korea equity", "EQ", 100);
        var assets = taxonomy("Classes d'actifs", "Actions", equity);
        var regions = taxonomy("Actions - Régions (MSCI)", "Corée du Sud", equity);
        assertEquals(1d, FactorMap.automatic(equity, assets, regions).get(Factor.EMERGING), 1e-10);
        assertEquals(0d, FactorMap.automatic(equity, assets, regions).get(Factor.EAFE), 1e-10);
        regions.getRoot().getChildren().getFirst().setName("Canada");
        assertEquals(0d, FactorMap.automatic(equity, assets, regions).get(Factor.US_EQUITY), 1e-10);
    }

    @Test
    public void incompleteOrOverallocatedGeographyIsNotNormalized()
    {
        var equity = holding("World", "EQ", 100);
        var assets = taxonomy("Classes d'actifs", "Actions", equity);
        var regions = taxonomy("Actions - Régions (MSCI)", "États-Unis", equity);
        regions.getRoot().getChildren().getFirst().getAssignments().getFirst().setWeight(6000);
        assertFalse(FactorMap.automatic(equity, assets, regions).containsKey(Factor.US_EQUITY));
        var partial = row(result(false, false), Factor.US_EQUITY);
        assertEquals(.6, partial.baseline(), 1e-10);
        assertEquals(.6, partial.coverage(), 1e-10);
        assertNull(partial.total());
        regions.getRoot().getChildren().getFirst().getAssignments().getFirst().setWeight(11000);
        assertFalse(FactorMap.automatic(equity, assets, regions).containsKey(Factor.US_EQUITY));
    }

    @Test
    public void scopeUsesAssignmentsNotTargetsAndRejectsOverAllocation()
    {
        var gold = holding("Gold", "JE00B588CD74", 100);
        holding("DBi", FactorMap.UCITS_HEDGED, 50);
        deposit(850);
        var scope = taxonomy("Pilotage Global", "Gold allocation", gold);
        scope.getRoot().getChildren().getFirst().setWeight(100);
        var result = FactorMap.calculate(client, new TestCurrencyConverter(), today, true, false, false, DbiData.bundled());
        assertEquals(10000, result.assets());
        assertEquals(1d, row(result, Factor.GOLD).baseline(), 1e-10);
        scope.getRoot().getChildren().getFirst().getAssignments().getFirst().setWeight(12000);
        assertThrows(IllegalArgumentException.class, () -> FactorMap.calculate(client, new TestCurrencyConverter(), today,
                        true, false, false, DbiData.bundled()));
    }

    @Test
    public void fxCashHasExplicitPairSigns()
    {
        cash.setCurrencyCode("USD");
        assertEquals(1d, FactorMap.automatic(cash, null, null).get(Factor.USD_EUR), 1e-10);
        cash.setCurrencyCode("JPY");
        assertEquals(0d, FactorMap.automatic(cash, null, null).get(Factor.USD_EUR), 1e-10);
        assertEquals(1d, FactorMap.automatic(cash, null, null).get(Factor.JPY_EUR), 1e-10);
    }

    @Test
    public void futureQuoteAndMissingValuationDisableWeights()
    {
        var s = holding("Future quote", "EQ", 100);
        s.removeAllPrices();
        s.addPrice(new SecurityPrice(today.plusDays(1), Values.Quote.factor()));
        var r = result(false, false);
        assertFalse(r.valued());
        assertNull(r.dbiWeight());
        assertNull(row(r, Factor.GOLD).baseline());
    }

    @Test
    public void explicitMappingPersistsMissingAndSignedValues()
    {
        var s = holding("Mixed fund", "EQ", 100);
        var mapping = new FactorSettings.Mapping(today.toString(), "Issuer factsheet", Map.of(Factor.GOLD, .05, Factor.US_10Y, -.2));
        var settings = FactorSettings.load(client).with(s.getUUID(), mapping);
        FactorSettings.save(client, settings, null);
        assertEquals(mapping, FactorSettings.load(client).mappings().get(s.getUUID()));
        assertEquals(.05, row(result(false, false), Factor.GOLD).baseline(), 1e-10);
        assertEquals(-.2, row(result(false, false), Factor.US_10Y).baseline(), 1e-10);
        assertNull(row(result(false, false), Factor.USD_EUR).baseline());
        assertThrows(IllegalArgumentException.class, () -> FactorSettings.save(client, settings, null));
        assertThrows(IllegalArgumentException.class, () -> new FactorSettings.Mapping(today.toString(), "source", Map.of(Factor.GOLD, Double.NaN)));
    }

    @Test
    public void futureSnapshotsAndAbsentFactorsRemainUnknown()
    {
        var data = DbiData.bundled();
        assertNull(data.exposure(Factor.GOLD, false, LocalDate.of(2026, 9, 28)));
        assertNull(data.exposure(Factor.GOLD, true, LocalDate.of(2026, 8, 30)));
        var partial = new DbiData.Snapshot(data.isin(), data.date(), data.source(), data.nav(),
                        data.holdings().stream().filter(h -> h.factor() != Factor.GOLD).toList(), null, null, Map.of());
        assertNull(partial.exposure(Factor.GOLD, false, today));
    }

    private String fixture() throws Exception
    {
        try (var stream = getClass().getResourceAsStream("dbmf-holdings.html"))
        {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    public void officialParserUsesDollarNotionalOverExactNavNotRoundedWeight() throws Exception
    {
        var parsed = DbiData.parse(fixture(), today);
        assertEquals(10, parsed.holdings().size());
        assertEquals(5207645294.86, parsed.nav(), .00001);
        assertEquals(-5162578619.16 / parsed.nav(), parsed.exposure(Factor.US_2Y, false, today), 1e-12);
        assertEquals(.36, DbiData.bundled().exposure(Factor.US_EQUITY, true, today), 1e-12);
    }

    @Test
    public void ucitsQuantitiesAndAccountingNavAreNotNotionalExposures()
    {
        var holdings = DbiData.bundledUcits().positions();
        assertEquals(17, holdings.size());
        assertEquals(5, holdings.stream().filter(h -> h.group().equals("Future")).count());
        var euro = holdings.stream().filter(h -> h.identifier().equals("ECU6")).findFirst().orElseThrow();
        assertEquals("-500", euro.quantity());
        assertNull(euro.navWeight());
        var structured = holdings.stream().filter(h -> h.identifier().equals("XS2578574761")).findFirst().orElseThrow();
        assertEquals(.0646, structured.navWeight(), 1e-12);
    }

    @Test
    public void parserRejectsMixedFutureDatesMissingNavAndUnknownInstruments() throws Exception
    {
        String html = fixture();
        assertThrows(IllegalArgumentException.class, () -> DbiData.parse(html.replaceFirst("09/29/2026", "09/28/2026"), today));
        assertThrows(IllegalArgumentException.class, () -> DbiData.parse(html, today.minusDays(2)));
        assertThrows(IllegalArgumentException.class, () -> DbiData.parse(html.replace("TOTAL NET ASSETS", "MISSING"), today));
        assertThrows(IllegalArgumentException.class, () -> DbiData.parse(html.replace("WTI CRUDEFUTURE", "UNKNOWN FUTURE"), today));
    }

    @Test
    public void noDbiMeansZeroOverlayAndNoProxyNeeded()
    {
        deposit(1000);
        var gold = row(result(false, false), Factor.GOLD);
        assertEquals(0d, gold.overlay(), 1e-12);
        assertEquals(0d, gold.total(), 1e-12);
    }
}
