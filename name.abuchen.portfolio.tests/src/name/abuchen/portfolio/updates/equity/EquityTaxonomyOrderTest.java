package name.abuchen.portfolio.updates.equity;

import static org.junit.Assert.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.Test;

import name.abuchen.portfolio.model.Classification;
import name.abuchen.portfolio.model.Classification.Assignment;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.ClientFactory;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.model.Taxonomy;
import name.abuchen.portfolio.updates.equity.EquityComposition.Family;

public class EquityTaxonomyOrderTest
{
    private static Classification child(Classification parent, String name, Security security, int weight)
    {
        var c = new Classification(parent, UUID.randomUUID().toString(), name);
        c.setRank(parent.getChildren().size()); c.setWeight(1234); c.setColor("#123456");
        if (security != null) c.addAssignment(new Assignment(security, weight));
        parent.addChild(c);
        return c;
    }

    @Test public void sortsAllLevelsByWeightedAmountAndPersistsWithoutChangingAllocation() throws Exception
    {
        var client = new Client();
        var large = new Security("Large", "EUR"); var small = new Security("Small", "USD");
        client.addSecurity(large); client.addSecurity(small);
        for (var family : Family.values())
        {
            var t = new Taxonomy(family.title()); t.setRootNode(new Classification("root-" + family, "Root")); client.addTaxonomy(t);
            child(t.getRoot(), "Others", large, 10000);
            child(t.getRoot(), "Small slice", large, 100); // 1% of 10000 = 100
            var group = child(t.getRoot(), "Group", null, 0);
            child(group, "Other", large, 5000);
            child(group, "Low", small, 1000);
            child(group, "High", small, 9000);
            child(t.getRoot(), "Direct", small, 10000); // 1000, not security count or target
            group.addAssignment(new Assignment(small, 100));
        }
        var untouched = new Taxonomy("Pilotage Global"); untouched.setRootNode(new Classification("pilotage-root", "Pilotage")); client.addTaxonomy(untouched);
        child(untouched.getRoot(), "First", small, 100); child(untouched.getRoot(), "Second", large, 10000);
        var values = Map.of(large.getUUID(), 10000L, small.getUUID(), 1000L);
        EquityTaxonomyOrder.apply(client, values);
        for (var family : Family.values())
        {
            var root = EquityAdjustment.taxonomy(client, family).getRoot();
            assertEquals(List.of("Group", "Direct", "Small slice", "Others"), root.getChildren().stream().map(Classification::getName).toList());
            var group = root.getChildren().getFirst();
            assertEquals(List.of("High", "Low", "Other"), group.getChildren().stream().map(Classification::getName).toList());
            assertEquals(3, group.getAssignments().getFirst().getRank());
            assertEquals(100, group.getAssignments().getFirst().getWeight());
            for (int i = 0; i < root.getChildren().size(); i++)
            {
                var child = root.getChildren().get(i);
                assertEquals(i, child.getRank()); assertEquals(1234, child.getWeight()); assertEquals("#123456", child.getColor());
            }
        }
        assertEquals(List.of("First", "Second"), untouched.getRoot().getChildren().stream().map(Classification::getName).toList());
        var ids = EquityAdjustment.taxonomy(client, Family.HOLDINGS).getRoot().getChildren().stream().map(Classification::getId).toList();
        var copy = ClientFactory.duplicate(client); EquityTaxonomyOrder.apply(copy, values);
        assertEquals(ids, EquityAdjustment.taxonomy(copy, Family.HOLDINGS).getRoot().getChildren().stream().map(Classification::getId).toList());
    }

    @Test public void deterministicTiesZeroNegativeAndOtherAliases()
    {
        var client = new Client(); var s = new Security("Position", "EUR"); client.addSecurity(s);
        for (var family : Family.values())
        {
            var t = new Taxonomy(family.title()); t.setRootNode(new Classification("root-" + family, "Root")); client.addTaxonomy(t);
            child(t.getRoot(), "  oThErS ", null, 0);
            child(t.getRoot(), "Zéro B", null, 0); child(t.getRoot(), "Zéro A", null, 0);
            child(t.getRoot(), "Negative", s, 10000);
        }
        EquityTaxonomyOrder.apply(client, Map.of(s.getUUID(), -500L));
        for (var family : Family.values())
            assertEquals(List.of("Zéro A", "Zéro B", "Negative", "  oThErS "), EquityAdjustment.taxonomy(client, family).getRoot()
                            .getChildren().stream().map(Classification::getName).toList());
    }
}
