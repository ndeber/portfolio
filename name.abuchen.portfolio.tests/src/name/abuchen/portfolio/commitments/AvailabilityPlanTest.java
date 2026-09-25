package name.abuchen.portfolio.commitments;

import static org.junit.Assert.*;
import java.time.LocalDate;
import java.util.*;
import org.junit.Test;
import name.abuchen.portfolio.model.*;
import name.abuchen.portfolio.junit.TestCurrencyConverter;
import name.abuchen.portfolio.money.Values;

public class AvailabilityPlanTest
{
    private final Client client = new Client();
    private final Security fund = new Security("Fund", "EUR");
    private final Account account = new Account("Xapa - Cash");
    private final LocalDate date = LocalDate.of(2026, 9, 25);
    public AvailabilityPlanTest()
    {
        client.setBaseCurrency("EUR"); client.addSecurity(fund); account.setCurrencyCode("EUR"); client.addAccount(account);
        account.addTransaction(new AccountTransaction(date.atStartOfDay(), "EUR", 50000, null, AccountTransaction.Type.DEPOSIT));
        var p = new Portfolio(); p.setName("Xapa - Direct"); p.setReferenceAccount(account); client.addPortfolio(p);
        var tx = new PortfolioTransaction(); tx.setDateTime(date.atStartOfDay()); tx.setType(PortfolioTransaction.Type.BUY); tx.setSecurity(fund); tx.setCurrencyCode("EUR"); tx.setAmount(100000); tx.setShares(10 * Values.Share.factor()); p.addTransaction(tx);
    }
    private AvailabilityPlan.Plan plan(Map<Integer, Integer> weights)
    { return new AvailabilityPlan.Plan(1, null, Map.of(fund.getUUID(), weights)); }
    @Test public void fiveTranchesPersistAndTaxonomyCanBeRebuiltWithoutChangingIds() throws Exception
    {
        var weights = Map.of(2027, 3000, 2028, 3000, 2029, 1500, 2030, 1500, 2031, 1000);
        var tax = AvailabilityPlan.apply(client, plan(weights)); var id = tax.getId();
        var categoryIds = tax.getAllClassifications().stream().map(Classification::getId).toList();
        var stored = AvailabilityPlan.load(client); assertEquals(weights, stored.allocations().get(fund.getUUID()));
        var copy = ClientFactory.duplicate(client); assertEquals(stored, AvailabilityPlan.load(copy));
        var converter = new TestCurrencyConverter(); var expected = Availability.calculate(client, tax, converter, date);
        assertEquals(30000, expected.amount(2027)); assertEquals(15000, expected.amount(2030)); assertEquals(10000, expected.amount(2031));
        tax.getAllClassifications().forEach(c -> c.getAssignments().clear());
        // Even before rebuilding, the widget still uses the authoritative table.
        assertEquals(expected.amounts(), Availability.calculate(client, tax, converter, date).amounts());
        AvailabilityPlan.apply(client, AvailabilityPlan.load(client));
        assertEquals(id, tax.getId()); assertEquals(categoryIds, tax.getAllClassifications().stream().map(Classification::getId).toList());
        assertEquals(expected.amounts(), Availability.calculate(client, tax, converter, date).amounts());
        assertEquals(1, client.getPortfolios().getFirst().getTransactions().size());
    }
    @Test public void initialImportIsReadOnlyAndUnknownPercentagesStayUnplanned()
    {
        var tax = new Taxonomy(Availability.NAME); var root = new Classification("root", Availability.NAME); tax.setRootNode(root); client.addTaxonomy(tax);
        var year = new Classification(root, "year", "2028"); root.addChild(year); year.addAssignment(new Classification.Assignment(fund, 4000));
        var imported = AvailabilityPlan.load(client); assertNull(client.getProperty(AvailabilityPlan.PROPERTY));
        assertEquals(Map.of(2028, 4000), imported.allocations().get(fund.getUUID()));
        AvailabilityPlan.apply(client, imported);
        var result = Availability.calculate(client, tax, new TestCurrencyConverter(), date);
        assertEquals(40000, result.amount(2028)); assertEquals(60000, result.amount(-1)); assertEquals(50000, result.amount(0));
    }
    @Test public void invalidInputIsAtomicAndNeverReplacesExistingTaxonomy()
    {
        var tax = AvailabilityPlan.apply(client, plan(Map.of(2031, 10000))); var before = client.getProperty(AvailabilityPlan.PROPERTY);
        var invalid = new AvailabilityPlan.Plan(1, tax.getId(), Map.of(fund.getUUID(), Map.of(2027, 6000, 2028, 6000)));
        assertThrows(IllegalArgumentException.class, () -> AvailabilityPlan.apply(client, invalid));
        assertEquals(before, client.getProperty(AvailabilityPlan.PROPERTY)); assertEquals(100000, Availability.calculate(client, tax, new TestCurrencyConverter(), date).amount(2031));
        client.removeTaxonomy(tax);
        assertThrows(IllegalArgumentException.class, () -> AvailabilityPlan.apply(client, AvailabilityPlan.load(client)));
    }
    @Test public void phacetIsNeverProjected()
    {
        var tax = AvailabilityPlan.apply(client, plan(Map.of(2027, 10000))); fund.setName("Phacet - Chris");
        AvailabilityPlan.apply(client, AvailabilityPlan.load(client));
        assertTrue(tax.getAllClassifications().stream().flatMap(c -> c.getAssignments().stream()).noneMatch(a -> a.getInvestmentVehicle() == fund));
        assertEquals(50000, Availability.calculate(client, tax, new TestCurrencyConverter(), date).total());
    }
    @Test public void malformedImportedTaxonomyDoesNotBecomeSilentSourceData()
    {
        var tax = new Taxonomy(Availability.NAME); var root = new Classification("root", Availability.NAME); tax.setRootNode(root); client.addTaxonomy(tax);
        root.addAssignment(new Classification.Assignment(fund, 7000));
        var year = new Classification(root, "year", "2028"); root.addChild(year); year.addAssignment(new Classification.Assignment(fund, 4000));
        assertThrows(IllegalArgumentException.class, () -> AvailabilityPlan.load(client));
        assertNull(client.getProperty(AvailabilityPlan.PROPERTY));
        assertEquals(2, tax.getAllClassifications().size());
    }
}
