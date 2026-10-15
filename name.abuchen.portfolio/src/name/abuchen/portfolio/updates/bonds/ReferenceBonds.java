package name.abuchen.portfolio.updates.bonds;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import com.google.gson.JsonParser;
import name.abuchen.portfolio.model.*;
import name.abuchen.portfolio.updates.bondallocation.*;

/** Presentation and documented classification of the three nominal reference bonds. */
public final class ReferenceBonds
{
    public static final Map<String, String> NAMES = Map.of(
                    BondSpreads.FR, "France — OAT 1,25 % — 25/05/2036",
                    BondSpreads.US, "États-Unis — Treasury 4,625 % — 15/08/2036",
                    BondSpreads.DE, "Allemagne — Bund 3,00 % — 15/08/2036");
    private record Placement(Taxonomy taxonomy, Classification category, Security security) { }
    private ReferenceBonds() { }

    private static Taxonomy taxonomy(Client c, String name)
    {
        var matches = c.getTaxonomies().stream().filter(t -> name.equals(t.getName())).toList();
        if (matches.size() != 1) throw new IllegalArgumentException("Taxonomie absente ou ambiguë : " + name);
        return matches.getFirst();
    }

    private static Classification path(Taxonomy t, String... names)
    {
        var node = t.getRoot();
        for (var name : names)
        {
            var children = node.getChildren().stream().filter(c -> name.equals(c.getName())).toList();
            if (children.size() != 1) throw new IllegalArgumentException("Classe absente ou ambiguë : " + t.getName() + " / " + name);
            node = children.getFirst();
        }
        return node;
    }

    public static void apply(Client client, LocalDate date)
    {
        var placements = new ArrayList<Placement>();
        var refs = new LinkedHashMap<String, Security>();
        var flags = new HashMap<String, String>();
        try (var reader = new InputStreamReader(ReferenceBonds.class.getResourceAsStream("reference-flags.json"), StandardCharsets.UTF_8))
        { JsonParser.parseReader(reader).getAsJsonObject().entrySet().forEach(e -> flags.put(e.getKey(), e.getValue().getAsString())); }
        catch (java.io.IOException e) { throw new IllegalArgumentException("Drapeaux indisponibles.", e); }
        for (String isin : List.of(BondSpreads.FR, BondSpreads.US, BondSpreads.DE))
        {
            var matches = client.getSecurities().stream().filter(s -> isin.equals(s.getIsin())).toList();
            if (matches.size() != 1) throw new IllegalArgumentException("Référence absente ou dupliquée : " + isin);
            var security = matches.getFirst(); refs.put(isin, security);
            if (!BondSpec.find(isin).orElseThrow().currency().equals(security.getCurrencyCode())) throw new IllegalArgumentException("Devise incohérente.");
            String[][] targets = {
                { "Classes d'actifs", "Obligations" },
                { "Objectifs et Classes d'actifs", "Portefeuille Investissable", "Core", "Obligations" },
                { "Pilotage Global", "Moteur Statique", "Obligations" },
                { "Type d'actif", "Obligation" },
                { "Expositions – Devises", security.getCurrencyCode() }
            };
            for (var target : targets)
            {
                var t = taxonomy(client, target[0]);
                placements.add(new Placement(t, path(t, Arrays.copyOfRange(target, 1, target.length)), security));
            }
            for (var family : BondComposition.Family.values())
            {
                var t = BondAllocationAdjustment.taxonomy(client, family);
                var slice = BondAllocationSources.staticSlice(isin, family, date);
                String name = slice.weights().keySet().iterator().next();
                var categories = t.getAllClassifications().stream().filter(c -> name.equals(c.getName())).toList();
                if (categories.size() != 1) throw new IllegalArgumentException("Catégorie obligataire absente ou ambiguë : " + name);
                placements.add(new Placement(t, categories.getFirst(), security));
            }
        }
        var logo = new AttributeType("logo");
        for (var entry : refs.entrySet())
        {
            var s = entry.getValue();
            if (!NAMES.get(entry.getKey()).equals(s.getName())) s.setName(NAMES.get(entry.getKey()));
            s.getAttributes().put(logo, flags.get(entry.getKey()));
            String rating = entry.getKey().equals(BondSpreads.FR) ? "S&P A+ → famille A ; décision 29/05/2026, source AFT."
                            : entry.getKey().equals(BondSpreads.US) ? "S&P AA+ → famille AA ; décision 26/06/2026." : "AAA — profil souverain Allemagne.";
            String note = Objects.toString(s.getNote(), "").replaceAll("(?s)\\n?\\[XAPA-REFERENCE-METADATA\\].*?\\[/XAPA-REFERENCE-METADATA\\]", "");
            s.setNote(note + "\n[XAPA-REFERENCE-METADATA]\nNominal à taux fixe ; Gouvernement ; " + s.getCurrencyCode()
                            + " 100 % ; " + rating + "\nÉchéance recalculée au " + date + ".\n[/XAPA-REFERENCE-METADATA]");
        }
        for (var p : placements)
        {
            var assigned = p.taxonomy().getAllClassifications().stream().flatMap(c -> c.getAssignments().stream())
                            .filter(a -> a.getInvestmentVehicle() == p.security()).toList();
            if (assigned.size() == 1 && assigned.getFirst().getWeight() == Classification.ONE_HUNDRED_PERCENT
                            && p.category().getAssignments().contains(assigned.getFirst())) continue;
            for (var c : p.taxonomy().getAllClassifications())
                for (var a : List.copyOf(c.getAssignments())) if (a.getInvestmentVehicle() == p.security()) c.removeAssignment(a);
            p.category().addAssignment(new Classification.Assignment(p.security()));
        }
        client.markDirty();
    }
}
