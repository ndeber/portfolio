package name.abuchen.portfolio.updates.equity;

import static org.junit.Assert.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.Test;

import name.abuchen.portfolio.model.*;
import name.abuchen.portfolio.model.Classification.Assignment;
import name.abuchen.portfolio.updates.equity.EquityComposition.*;

public class EquityAdjustmentTest
{
    private static Classification child(Classification parent, String name)
    {
        var c = new Classification(parent, java.util.UUID.randomUUID().toString(), name);
        parent.addChild(c);
        return c;
    }
    private static Taxonomy tax(Client client, String name)
    {
        var t = new Taxonomy(name);
        t.setRootNode(new Classification(name + "-root", name));
        client.addTaxonomy(t);
        return t;
    }
    private static Client fixture()
    {
        var client = new Client();
        var s = new Security("Example", "EUR");
        s.setIsin("IE00BK5BQT80");
        client.addSecurity(s);
        child(tax(client, "Classes d'actifs").getRoot(), "Actions").addAssignment(new Assignment(s));
        for (var f : Family.values())
        {
            var t = tax(client, f.title());
            child(t.getRoot(), "Other").addAssignment(new Assignment(s));
        }
        child(EquityAdjustment.taxonomy(client, Family.REGIONS).getRoot(), "France").setWeight(4500);
        child(EquityAdjustment.taxonomy(client, Family.SECTORS).getRoot(), "Technologie");
        child(tax(client, "Pilotage Global").getRoot(), "Moteur Dynamique").addAssignment(new Assignment(s));
        return client;
    }
    private static Slice slice(Item... items)
    {
        return new Slice(List.of(items), LocalDate.of(2026, 8, 31), "https://example.org/composition", false);
    }
    private static Item item(String name, String percent) { return new Item(name, new BigDecimal(percent)); }
    private static Outcome outcome(Client c, Family f, Slice s) { return new Outcome(c.getSecurities().getFirst().getUUID(), f, s, null); }
    private static int total(Taxonomy t, Security s)
    {
        return t.getAllClassifications().stream().flatMap(c -> c.getAssignments().stream())
                        .filter(a -> a.getInvestmentVehicle().equals(s)).mapToInt(Assignment::getWeight).sum();
    }
    @Test public void threeTaxonomiesPreserveTargetsOtherAssetsAndIdentityAndAreIdempotent()
    {
        var c = fixture(); var s = c.getSecurities().getFirst();
        var france = EquityAdjustment.taxonomy(c, Family.REGIONS).getAllClassifications().stream().filter(x -> x.getName().equals("France")).findFirst().orElseThrow();
        var other = new Security("Other asset", "EUR");c.addSecurity(other);france.addAssignment(new Assignment(other, 777));
        var outcomes = List.of(outcome(c, Family.REGIONS, slice(item("France", "75"))),
                        outcome(c, Family.SECTORS, slice(item("Technologie", "90"))),
                        outcome(c, Family.HOLDINGS, slice(item("Alphabet Inc Class A", "7"), item("Alphabet Inc Class C", "3"))));
        var plan = EquityAdjustment.prepare(c, outcomes);
        assertEquals(10000, total(EquityAdjustment.taxonomy(c, Family.REGIONS), s)); // preparation is read-only
        EquityAdjustment.apply(c, plan);
        for (var f : Family.values()) assertEquals(10000, total(EquityAdjustment.taxonomy(c, f), s));
        assertEquals(4500, france.getWeight());
        assertEquals(777, france.getAssignments().stream().filter(a -> a.getInvestmentVehicle()==other).findFirst().orElseThrow().getWeight());
        assertSame(france, EquityAdjustment.taxonomy(c, Family.REGIONS).getClassificationById(france.getId()));
        assertEquals(10000, total(c.getTaxonomies().getLast(), s));
        assertTrue(EquityAdjustment.prepare(c, outcomes).changes().isEmpty());
        assertTrue(c.getProperty(EquityAdjustment.AUDIT).contains("2026-08-31"));
    }
    @Test public void invalidSliceAndFetchFailuresLeaveExistingAssignments()
    {
        var c = fixture();
        var p = EquityAdjustment.prepare(c, List.of(outcome(c, Family.REGIONS, slice(item("Unknown country", "90"))),
                        new Outcome(c.getSecurities().getFirst().getUUID(), Family.SECTORS, null, "HTTP 503")));
        assertTrue(p.changes().isEmpty());assertEquals(2,p.notices().size());
        assertEquals(10000,total(EquityAdjustment.taxonomy(c,Family.REGIONS),c.getSecurities().getFirst()));
    }
    @Test public void stalePlanFailsBeforeAnyMutation()
    {
        var c=fixture();var p=EquityAdjustment.prepare(c,List.of(outcome(c,Family.REGIONS,slice(item("France","60")))));
        EquityAdjustment.taxonomy(c,Family.SECTORS).getRoot().setName("Edited");
        assertThrows(IllegalArgumentException.class,()->EquityAdjustment.apply(c,p));
        assertEquals(1,EquityAdjustment.taxonomy(c,Family.REGIONS).getAllClassifications().stream().flatMap(x->x.getAssignments().stream()).count());
    }
    @Test public void canonicalHoldingsExcludeFundsAndPreserveResidual()
    {
        var w=EquityComposition.weights(slice(item("ALPHABET A","3.12"),item("Alphabet Inc Class C","2.88"),item("Cash Money Market Fund","4")),Family.HOLDINGS,"Other");
        assertEquals(Map.of("ALPHABET",600,"OTHER",9400),w);
        assertEquals(EquityComposition.holdingKey("TSMC"),EquityComposition.holdingKey("Taiwan Semiconductor Manufacturing Co Ltd"));
    }
    @Test public void rejectInvalidSourceWeights()
    {
        assertThrows(IllegalArgumentException.class,()->slice(item("A","70"),item("B","31")));
        assertThrows(IllegalArgumentException.class,()->slice(item("A","0")));
        assertThrows(IllegalArgumentException.class,()->item("A","-1"));
        assertThrows(IllegalArgumentException.class,()->new Slice(List.of(item("A","1")),LocalDate.now().plusDays(1),"url",false));
    }
    @Test public void duplicateResultsAndAmbiguousCategoriesAreRejected()
    {
        var c=fixture();var result=outcome(c,Family.REGIONS,slice(item("France","90")));
        assertThrows(IllegalArgumentException.class,()->EquityAdjustment.prepare(c,List.of(result,result)));
        child(EquityAdjustment.taxonomy(c,Family.REGIONS).getRoot(),"France");
        assertTrue(EquityAdjustment.prepare(c,List.of(result)).changes().isEmpty());
    }
    @Test public void scopeRespectsHoldingsRetirementFutureTradesAndTransfers()
    {
        var c=fixture();var s=c.getSecurities().getFirst();s.setIsin("TEST");
        assertTrue(EquityAdjustment.scope(c,LocalDate.now()).isEmpty());
        var p=new Portfolio();c.addPortfolio(p);
        var buy=new PortfolioTransaction();buy.setType(PortfolioTransaction.Type.BUY);buy.setSecurity(s);buy.setShares(1000000);buy.setDateTime(LocalDate.now().atStartOfDay());p.addTransaction(buy);
        assertEquals(List.of(s),EquityAdjustment.scope(c,LocalDate.now()));
        buy.setDateTime(LocalDate.now().plusDays(1).atStartOfDay());assertTrue(EquityAdjustment.scope(c,LocalDate.now()).isEmpty());
        buy.setDateTime(LocalDate.now().atStartOfDay());s.setRetired(true);assertTrue(EquityAdjustment.scope(c,LocalDate.now()).isEmpty());
    }
    @Test public void serializeReloadKeepsNewCategoriesAndAmounts() throws Exception
    {
        var c=fixture();EquityAdjustment.apply(c,EquityAdjustment.prepare(c,List.of(outcome(c,Family.HOLDINGS,slice(item("Apple Inc","15"))))));
        var copy=ClientFactory.duplicate(c);
        assertEquals(10000,total(EquityAdjustment.taxonomy(copy,Family.HOLDINGS),copy.getSecurities().getFirst()));
        assertEquals(3,EquityAdjustment.taxonomy(copy,Family.HOLDINGS).getAllClassifications().size());
    }
}
