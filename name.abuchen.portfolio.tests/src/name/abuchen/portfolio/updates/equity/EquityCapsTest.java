package name.abuchen.portfolio.updates.equity;

import static org.junit.Assert.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.junit.Test;
import name.abuchen.portfolio.model.*;
import name.abuchen.portfolio.model.Classification.Assignment;
import com.google.gson.JsonParser;

public class EquityCapsTest
{
    private String json() throws Exception
    {
        try (var in = getClass().getResourceAsStream("vanguard-caps.json"))
        { return new String(in.readAllBytes(), StandardCharsets.UTF_8); }
    }
    private EquityComposition.Slice slice() throws Exception
    { return EquityCapSources.parse(json(), "9679", LocalDate.of(2026, 9, 29)); }
    private static Classification child(Classification p, String name)
    {
        var c = new Classification(p, java.util.UUID.randomUUID().toString(), name);
        p.addChild(c); return c;
    }
    private Client fixture()
    {
        var c = new Client(); var s = new Security("All-World", "EUR"); c.addSecurity(s);
        for (String name : List.of("Pilotage Global", "Objectifs et Classes d'actifs"))
        {
            var t = new Taxonomy(name); t.setRootNode(new Classification(name, name)); c.addTaxonomy(t);
            var actions = child(t.getRoot(), "Actions");
            var large = child(actions, "Large Caps"); large.addAssignment(new Assignment(s, 8000)); large.setWeight(7500);
            child(actions, "Small Caps").setWeight(2500);
            child(actions, "Infrastructure").addAssignment(new Assignment(s, 500));
            child(t.getRoot(), "Moteur Dynamique").addAssignment(new Assignment(s, 1500));
        }
        return c;
    }
    @Test public void officialFundWeightsIncludeMediumLargeInLargeAndPreserveResidual() throws Exception
    {
        var s = slice();
        var p = new java.util.LinkedHashMap<String, java.math.BigDecimal>();
        s.items().forEach(i -> p.put(i.name(), i.percent()));
        var w = EquityCaps.allocate(p, 10000);
        assertEquals(Integer.valueOf(7898), w.get(EquityCaps.LARGE));
        assertEquals(Integer.valueOf(1430), w.get(EquityCaps.MID));
        assertEquals(Integer.valueOf(665), w.get(EquityCaps.SMALL));
        assertEquals(Integer.valueOf(7), w.get(""));
        assertEquals(10000, w.values().stream().mapToInt(Integer::intValue).sum());
    }
    @Test public void rejectsPartialStaleFutureAndOverweightData() throws Exception
    {
        for (String mutation : List.of("missing", "stale", "future", "excess", "incomplete", "dates"))
        {
            var j = JsonParser.parseString(json()).getAsJsonObject();
            var codes = j.getAsJsonObject("data").getAsJsonArray("polarisAnalyticsHistory").get(0).getAsJsonObject()
                            .getAsJsonObject("monthly").getAsJsonObject("analytics").getAsJsonObject("fund")
                            .getAsJsonArray("items").get(0).getAsJsonObject().getAsJsonObject("codes");
            var large = codes.getAsJsonObject("FRCCAPTLTP261");
            switch (mutation)
            {
                case "missing" -> codes.remove("FRCCAPTLTP263");
                case "stale" -> large.addProperty("effectiveDate", "2025-01-01");
                case "future" -> large.addProperty("effectiveDate", "2027-01-01");
                case "dates" -> large.addProperty("effectiveDate", "2026-08-30");
                case "excess" -> large.addProperty("analyticValue", "99");
                case "incomplete" -> large.addProperty("analyticValue", "65");
            }
            assertThrows(mutation, IOException.class, () -> EquityCapSources.parse(j.toString(), "9679", LocalDate.of(2026,9,29)));
        }
    }
    @Test public void bothTaxonomiesPreserveBudgetsTargetsOtherBranchesAndAreIdempotent() throws Exception
    {
        var c = fixture(); var s = c.getSecurities().getFirst();
        var outcomes = List.of(new EquityCaps.Outcome(s.getUUID(), slice(), null));
        var plan = EquityCaps.prepare(c, outcomes);
        assertEquals(2, plan.creations().size());
        assertTrue(c.getTaxonomies().getFirst().getAllClassifications().stream().noneMatch(x -> x.getName().equals(EquityCaps.MID)));
        EquityCaps.apply(c, plan);
        for (var t : c.getTaxonomies())
        {
            var actions = t.getAllClassifications().stream().filter(x -> x.getName().equals("Actions")).findFirst().orElseThrow();
            assertEquals(10000, t.getAllClassifications().stream().flatMap(x -> x.getAssignments().stream()).mapToInt(Assignment::getWeight).sum());
            assertEquals(500, actions.getChildren().stream().filter(x -> x.getName().equals("Infrastructure")).findFirst().orElseThrow().getAssignments().getFirst().getWeight());
            assertEquals(7500, actions.getChildren().stream().filter(x -> x.getName().equals(EquityCaps.LARGE)).findFirst().orElseThrow().getWeight());
            assertEquals(0, actions.getChildren().stream().filter(x -> x.getName().equals(EquityCaps.MID)).findFirst().orElseThrow().getWeight());
            assertTrue(actions.getAssignments().getFirst().getWeight() > 0);
        }
        assertTrue(EquityCaps.prepare(c, outcomes).changes().isEmpty());
        assertTrue(EquityCaps.prepare(c, outcomes).creations().isEmpty());
    }
    @Test public void failedSourcesAndStalePreviewsDoNotRewriteAssignments() throws Exception
    {
        var c = fixture(); var id = c.getSecurities().getFirst().getUUID();
        assertTrue(EquityCaps.prepare(c, List.of(new EquityCaps.Outcome(id, null, "Unavailable"))).changes().isEmpty());
        var plan = EquityCaps.prepare(c, List.of(new EquityCaps.Outcome(id, slice(), null)));
        c.getTaxonomies().getFirst().getRoot().setName("Changed");
        assertThrows(IllegalArgumentException.class, () -> EquityCaps.apply(c, plan));
        assertTrue(c.getTaxonomies().getLast().getAllClassifications().stream().noneMatch(x -> x.getName().equals(EquityCaps.MID)));
    }
}
