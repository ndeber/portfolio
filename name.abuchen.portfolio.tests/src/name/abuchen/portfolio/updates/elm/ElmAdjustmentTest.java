package name.abuchen.portfolio.updates.elm;

import static org.junit.Assert.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.Test;

import name.abuchen.portfolio.model.Classification;
import name.abuchen.portfolio.model.Classification.Assignment;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.ClientFactory;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.model.Taxonomy;
import name.abuchen.portfolio.updates.elm.ElmAdjustment.Mapping;
import name.abuchen.portfolio.updates.elm.ElmAdjustment.Options;
import name.abuchen.portfolio.updates.elm.ElmAdjustment.Pilotage;

public class ElmAdjustmentTest
{
    private final Client client = new Client();
    private final Security elm = new Security("Elm Market Navigator ETF", "USD");
    private final Security other = new Security("Other asset", "EUR");
    private final Taxonomy allocation = taxonomy("Allocation");
    private final Classification cash = child(allocation.getRoot(), "Cash");
    private final Classification bonds = child(allocation.getRoot(), "Bonds");
    private final Classification equities = child(allocation.getRoot(), "Equities");
    private final ElmAllocation published = new ElmAllocation(LocalDate.of(2026, 9, 18), 2883, 1235, 5882);

    public ElmAdjustmentTest()
    {
        client.addSecurity(elm);
        client.addSecurity(other);
        client.addTaxonomy(allocation);
        equities.addAssignment(new Assignment(elm, 10000));
        equities.addAssignment(new Assignment(other, 10000));
        cash.setWeight(2000);
        bonds.setWeight(3000);
        equities.setWeight(5000);
    }

    private static Taxonomy taxonomy(String name)
    {
        var taxonomy = new Taxonomy(name);
        taxonomy.setRootNode(new Classification(UUID.randomUUID().toString(), name));
        return taxonomy;
    }

    private static Classification child(Classification parent, String name)
    {
        var child = new Classification(parent, UUID.randomUUID().toString(), name);
        parent.addChild(child);
        return child;
    }

    private Options options(Pilotage pilotage)
    {
        return new Options(elm.getUUID(), List.of(new Mapping(allocation.getId(), cash.getId(), bonds.getId(), equities.getId())), pilotage);
    }

    private int weight(Classification category)
    {
        return category.getAssignments().stream().filter(a -> a.getInvestmentVehicle().equals(elm)).mapToInt(Assignment::getWeight).sum();
    }

    @Test
    public void preservesIdentifiersTargetsOtherSecuritiesAndUnselectedTaxonomies()
    {
        var ids = allocation.getAllClassifications().stream().map(Classification::getId).toList();
        var otherAssignment = equities.getAssignments().get(1);
        var unchanged = taxonomy("Unselected");
        unchanged.getRoot().addAssignment(new Assignment(elm));
        client.addTaxonomy(unchanged);
        var plan = ElmAdjustment.prepare(client, options(null), published);
        assertEquals(10000, weight(equities)); // Preview is read-only.
        ElmAdjustment.apply(client, plan);
        assertEquals(List.of(2883, 1235, 5882), List.of(weight(cash), weight(bonds), weight(equities)));
        assertEquals(ids, allocation.getAllClassifications().stream().map(Classification::getId).toList());
        assertEquals(List.of(2000, 3000, 5000), List.of(cash.getWeight(), bonds.getWeight(), equities.getWeight()));
        assertSame(otherAssignment, equities.getAssignments().stream().filter(a -> a.getInvestmentVehicle().equals(other)).findFirst().get());
        assertEquals(10000, weight(unchanged.getRoot()));
        assertTrue(ElmAdjustment.prepare(client, options(null), published).changes().isEmpty());
    }

    @Test
    public void pilotageMovesOnlyElmAndPreservesExistingCategoryIdsAndTargets()
    {
        var pilotage = taxonomy("Pilotage");
        client.addTaxonomy(pilotage);
        var statique = child(pilotage.getRoot(), "Moteur Statique");
        var dynamic = child(pilotage.getRoot(), "Moteur Dynamique");
        var otherCategory = child(pilotage.getRoot(), "Other");
        statique.setWeight(6500);
        dynamic.setWeight(2500);
        otherCategory.setWeight(1000);
        statique.addAssignment(new Assignment(elm, 5000));
        statique.addAssignment(new Assignment(other, 10000));
        dynamic.addAssignment(new Assignment(elm, 5000));
        var ids = pilotage.getAllClassifications().stream().map(Classification::getId).toList();
        var plan = ElmAdjustment.prepare(client, options(new Pilotage(pilotage.getId(), dynamic.getId())), published);
        ElmAdjustment.apply(client, plan);
        assertEquals(0, weight(statique));
        assertEquals(10000, weight(dynamic));
        assertEquals(1, statique.getAssignments().size());
        assertEquals(ids, pilotage.getAllClassifications().stream().map(Classification::getId).toList());
        assertEquals(List.of(6500, 2500, 1000), List.of(statique.getWeight(), dynamic.getWeight(), otherCategory.getWeight()));
    }

    @Test
    public void cleansDuplicateAndRootAssignmentsWithoutCreatingZeroWeightEntries()
    {
        equities.addAssignment(new Assignment(elm, 500));
        allocation.getRoot().addAssignment(new Assignment(elm, 1000));
        var allBonds = new ElmAllocation(published.date(), 0, 10000, 0);
        ElmAdjustment.apply(client, ElmAdjustment.prepare(client, options(null), allBonds));
        assertEquals(0, weight(allocation.getRoot()));
        assertEquals(0, weight(equities));
        assertEquals(10000, weight(bonds));
        assertTrue(cash.getAssignments().isEmpty());
        assertEquals(1, equities.getAssignments().size()); // Other security survives.
    }

    @Test
    public void rejectsInvalidMappingsBeforeChangingAnything()
    {
        var duplicate = new Options(elm.getUUID(), List.of(new Mapping(allocation.getId(), cash.getId(), cash.getId(), equities.getId())), null);
        assertThrows(IllegalArgumentException.class, () -> ElmAdjustment.prepare(client, duplicate, published));
        var missing = new Options(elm.getUUID(), List.of(new Mapping(allocation.getId(), "removed", bonds.getId(), equities.getId())), null);
        assertThrows(IllegalArgumentException.class, () -> ElmAdjustment.prepare(client, missing, published));
        assertThrows(IllegalArgumentException.class, () -> ElmAdjustment.prepare(client, options(new Pilotage(allocation.getId(), cash.getId())), published));
        assertEquals(10000, weight(equities));
    }

    @Test
    public void rejectsAnOutdatedPreview()
    {
        var plan = ElmAdjustment.prepare(client, options(null), published);
        equities.getAssignments().getFirst().setWeight(9000);
        assertThrows(IllegalArgumentException.class, () -> ElmAdjustment.apply(client, plan));
        assertEquals(0, weight(cash));
    }

    @Test
    public void snapshotIncludesUnsavedEditsWithoutMutatingSourceAndKeepsWidgetReferences() throws Exception
    {
        client.setProperty("widget.taxonomy", allocation.getId());
        client.setProperty("widget.category", cash.getId());
        elm.setNote("An unsaved note");
        var copy = ClientFactory.duplicate(client);
        ElmAdjustment.apply(copy, ElmAdjustment.prepare(copy, options(null), published));
        assertEquals(10000, weight(equities));
        assertEquals(0, weight(cash));
        assertEquals("An unsaved note", copy.getSecurities().getFirst().getNote());
        assertEquals(allocation.getId(), copy.getProperty("widget.taxonomy"));
        assertNotNull(copy.getTaxonomies().getFirst().getClassificationById(copy.getProperty("widget.category")));
        assertNotNull(copy.getProperty(ElmAdjustment.SETTINGS));
        assertNotNull(copy.getProperty(ElmAdjustment.AUDIT));
        assertNull(client.getProperty(ElmAdjustment.AUDIT));
    }
}
