package name.abuchen.portfolio.updates.bondallocation;

import static org.junit.Assert.*;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.Test;
import name.abuchen.portfolio.model.*;
import name.abuchen.portfolio.model.Classification.Assignment;
import name.abuchen.portfolio.updates.bondallocation.BondComposition.*;
import name.abuchen.portfolio.updates.equity.EquityTaxonomyOrder;

public class BondAllocationAdjustmentTest
{
    private static Classification child(Classification parent, String name)
    {
        var c = new Classification(parent, UUID.randomUUID().toString(), name); c.setWeight(3456); c.setRank(parent.getChildren().size()); parent.addChild(c); return c;
    }
    private static Taxonomy tax(Client c, String name)
    {
        var t = new Taxonomy(name); t.setRootNode(new Classification(UUID.randomUUID().toString(), name)); c.addTaxonomy(t); return t;
    }
    private static Client fixture()
    {
        var c = new Client(); var s = new Security("Bund", "EUR"); s.setIsin("DE000BU2Z072"); c.addSecurity(s);
        child(tax(c, "Classes d'actifs").getRoot(), "Obligations").addAssignment(new Assignment(s));
        for (var f : Family.values())
        {
            var t = tax(c, f.title()); child(t.getRoot(), "Old").addAssignment(new Assignment(s));
            switch (f)
            {
                case RATING -> child(t.getRoot(), "AAA");
                case MATURITY -> child(t.getRoot(), "7-10 ans");
                case REGIONS -> child(child(t.getRoot(), "Europe"), "Allemagne");
                case ISSUER -> child(t.getRoot(), "Gouvernement");
            }
        }
        child(tax(c, "Pilotage Global").getRoot(), "Keep").addAssignment(new Assignment(s));
        return c;
    }
    private static List<Outcome> outcomes(Client c)
    {
        return java.util.Arrays.stream(Family.values()).map(f -> new Outcome(c.getSecurities().getFirst().getUUID(), f,
                        BondAllocationSources.staticSlice("DE000BU2Z072", f, LocalDate.of(2026, 9, 24)), null)).toList();
    }
    @Test public void updatesAllFourPreservesIdentityTargetsOtherPositionsAndReloads() throws Exception
    {
        var c = fixture(); var s = c.getSecurities().getFirst(); var t = BondAllocationAdjustment.taxonomy(c, Family.RATING);
        var aaa = t.getAllClassifications().stream().filter(x -> x.getName().equals("AAA")).findFirst().orElseThrow();
        var other = new Security("Other asset", "EUR"); c.addSecurity(other); aaa.addAssignment(new Assignment(other, 777));
        var p = BondAllocationAdjustment.prepare(c, outcomes(c)); assertEquals(8, p.changes().size());
        assertEquals(1, aaa.getAssignments().size());
        BondAllocationAdjustment.apply(c, p);
        assertSame(aaa, t.getClassificationById(aaa.getId())); assertEquals(3456, aaa.getWeight());
        assertEquals(777, aaa.getAssignments().stream().filter(a -> a.getInvestmentVehicle() == other).findFirst().orElseThrow().getWeight());
        for (var f : Family.values()) assertEquals(10000, BondAllocationAdjustment.taxonomy(c, f).getAllClassifications().stream()
                        .flatMap(x -> x.getAssignments().stream()).filter(a -> a.getInvestmentVehicle() == s).mapToInt(Assignment::getWeight).sum());
        assertEquals(1, c.getTaxonomies().getLast().getRoot().getChildren().getFirst().getAssignments().size());
        assertTrue(BondAllocationAdjustment.prepare(c, outcomes(c)).isEmpty());
        var copy = ClientFactory.duplicate(c); assertTrue(BondAllocationAdjustment.prepare(copy, outcomes(copy)).isEmpty());
    }
    @Test public void failuresMissingOrAmbiguousCategoryPreserveEntireSlice()
    {
        var c = fixture(); var t = BondAllocationAdjustment.taxonomy(c, Family.RATING); child(t.getRoot(), "AAA");
        var p = BondAllocationAdjustment.prepare(c, outcomes(c));
        assertFalse(p.changes().stream().anyMatch(x -> x.taxonomyId().equals(t.getId())));
        var error = new Outcome(c.getSecurities().getFirst().getUUID(), Family.MATURITY, null, "unavailable");
        assertTrue(BondAllocationAdjustment.prepare(c, List.of(error)).isEmpty());
        var absent = new Outcome(c.getSecurities().getFirst().getUUID(), Family.REGIONS, new Slice(Map.of("Missing", 10000), null, "source", "note"), null);
        assertTrue(BondAllocationAdjustment.prepare(c, List.of(absent)).isEmpty());
    }
    @Test public void stalePlansAndDuplicateSourcesFailBeforeMutation()
    {
        var c = fixture(); var sources = outcomes(c); var plan = BondAllocationAdjustment.prepare(c, sources);
        assertThrows(IllegalArgumentException.class, () -> BondAllocationAdjustment.prepare(c, List.of(sources.getFirst(), sources.getFirst())));
        BondAllocationAdjustment.taxonomy(c, Family.MATURITY).getRoot().setName("Changed");
        assertThrows(IllegalArgumentException.class, () -> BondAllocationAdjustment.apply(c, plan));
        assertEquals(10000, BondAllocationAdjustment.taxonomy(c, Family.RATING).getRoot().getChildren().getFirst().getAssignments().getFirst().getWeight());
    }
    @Test public void aliasesScopeAndForcedBondsRespectRetirementFutureTradesAndTransfers()
    {
        var c = fixture(); var s = c.getSecurities().getFirst();
        assertEquals(List.of(s), BondAllocationAdjustment.scope(c, LocalDate.now()));
        s.setRetired(true); assertTrue(BondAllocationAdjustment.scope(c, LocalDate.now()).isEmpty()); s.setRetired(false); s.setIsin("OTHER");
        assertTrue(BondAllocationAdjustment.scope(c, LocalDate.now()).isEmpty());
        var p = new Portfolio(); c.addPortfolio(p); var tx = new PortfolioTransaction(); tx.setSecurity(s); tx.setShares(1000000);
        tx.setType(PortfolioTransaction.Type.TRANSFER_IN); tx.setDateTime(LocalDate.now().plusDays(1).atStartOfDay()); p.addTransaction(tx);
        assertTrue(BondAllocationAdjustment.scope(c, LocalDate.now()).isEmpty()); tx.setDateTime(LocalDate.now().atStartOfDay());
        assertEquals(List.of(s), BondAllocationAdjustment.scope(c, LocalDate.now()));
        assertTrue(Family.MATURITY.matches("Obligations - Echeance")); assertTrue(Family.ISSUER.matches("Obligations - Type d’emetteur"));
    }
    @Test public void sortingTargetsOnlyBondTaxonomiesAndPutsRegionalOtherLast()
    {
        var c = fixture(); var region = BondAllocationAdjustment.taxonomy(c, Family.REGIONS);
        var europe = region.getAllClassifications().stream().filter(x -> x.getName().equals("Europe")).findFirst().orElseThrow();
        var other = child(europe, "Europe - Autres"); other.addAssignment(new Assignment(c.getSecurities().getFirst()));
        EquityTaxonomyOrder.apply(c, java.util.Arrays.stream(Family.values()).map(f -> BondAllocationAdjustment.taxonomy(c, f)).toList(), Map.of(c.getSecurities().getFirst().getUUID(), 10000L));
        assertEquals("Europe - Autres", europe.getChildren().getLast().getName());
        assertEquals(3456, c.getTaxonomies().getLast().getRoot().getChildren().getFirst().getWeight());
    }
}
