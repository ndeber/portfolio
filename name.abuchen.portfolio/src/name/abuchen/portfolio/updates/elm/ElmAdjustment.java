package name.abuchen.portfolio.updates.elm;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.google.gson.Gson;

import name.abuchen.portfolio.model.Classification;
import name.abuchen.portfolio.model.Classification.Assignment;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.model.Taxonomy;

/** Changes only the chosen security's assignments; preserves category identity and targets. */
public final class ElmAdjustment
{
    public static final String SETTINGS = "fork.elm.settings";
    public static final String AUDIT = "fork.elm.lastUpdate";

    public record Mapping(String taxonomyId, String cashId, String bondsId, String equitiesId)
    {
    }

    public record Pilotage(String taxonomyId, String dynamicId)
    {
    }

    public record Options(String securityId, List<Mapping> mappings, Pilotage pilotage)
    {
        public Options
        {
            mappings = List.copyOf(mappings);
        }
    }

    public record Change(String taxonomyId, String classificationId, String location, int before, int after)
    {
    }

    public record Plan(Options options, ElmAllocation allocation, List<Change> changes)
    {
        public Plan
        {
            changes = List.copyOf(changes);
        }
    }

    private ElmAdjustment()
    {
    }

    public static Plan prepare(Client client, Options options, ElmAllocation allocation)
    {
        var security = security(client, options.securityId());
        if (options.mappings().isEmpty() && options.pilotage() == null)
            throw new IllegalArgumentException("Choisissez au moins une taxonomie ou l'option Pilotage.");
        var desired = new LinkedHashMap<Taxonomy, Map<String, Integer>>();
        for (var mapping : options.mappings())
        {
            var taxonomy = taxonomy(client, mapping.taxonomyId());
            var ids = List.of(mapping.cashId(), mapping.bondsId(), mapping.equitiesId());
            if (new HashSet<>(ids).size() != 3)
                throw new IllegalArgumentException("Choisissez trois catégories distinctes dans " + taxonomy.getName());
            var categories = ids.stream().map(id -> classification(taxonomy, id)).toList();
            for (var category : categories)
            {
                for (var parent = category.getParent(); parent != null; parent = parent.getParent())
                    if (categories.contains(parent))
                        throw new IllegalArgumentException("Les trois catégories ne doivent pas être imbriquées.");
            }
            if (desired.put(taxonomy, Map.of(mapping.cashId(), allocation.cash(), mapping.bondsId(), allocation.bonds(),
                            mapping.equitiesId(), allocation.equities())) != null)
                throw new IllegalArgumentException("Taxonomie sélectionnée plusieurs fois.");
        }
        if (options.pilotage() != null)
        {
            var pilotage = options.pilotage();
            var taxonomy = taxonomy(client, pilotage.taxonomyId());
            classification(taxonomy, pilotage.dynamicId());
            if (desired.put(taxonomy, Map.of(pilotage.dynamicId(), 10000)) != null)
                throw new IllegalArgumentException("Pilotage ne peut pas aussi recevoir la répartition par classe d'actifs.");
        }
        var changes = new ArrayList<Change>();
        desired.forEach((taxonomy, targets) -> {
            for (var classification : taxonomy.getAllClassifications())
            {
                var assignments = classification.getAssignments().stream()
                                .filter(a -> a.getInvestmentVehicle().equals(security)).toList();
                int before = Math.toIntExact(assignments.stream().mapToLong(Assignment::getWeight).sum());
                int after = targets.getOrDefault(classification.getId(), 0);
                if (before != after || assignments.size() > 1 || (after == 0 && !assignments.isEmpty()))
                    changes.add(new Change(taxonomy.getId(), classification.getId(),
                                    taxonomy.getName() + " / " + path(classification), before, after));
            }
        });
        return new Plan(options, allocation, changes);
    }

    public static void apply(Client client, Plan plan)
    {
        // Validate the entire scope before touching any assignment.
        if (!prepare(client, plan.options(), plan.allocation()).changes().equals(plan.changes()))
            throw new IllegalArgumentException("Le portefeuille a changé depuis l'aperçu. Relancez l'ajustement.");
        var security = security(client, plan.options().securityId());
        for (var change : plan.changes())
        {
            var taxonomy = taxonomy(client, change.taxonomyId());
            var category = classificationIncludingRoot(taxonomy, change.classificationId());
            var matches = category.getAssignments().stream().filter(a -> a.getInvestmentVehicle().equals(security)).toList();
            Assignment assignment = matches.isEmpty() ? new Assignment(security) : matches.getFirst();
            category.getAssignments().removeAll(matches);
            if (change.after() > 0)
            {
                assignment.setWeight(change.after());
                if (matches.isEmpty())
                    assignment.setRank(category.getAssignments().stream().mapToInt(Assignment::getRank).max().orElse(-1) + 1);
                category.addAssignment(assignment);
            }
        }
        client.setProperty(SETTINGS, new Gson().toJson(plan.options()));
        client.setProperty(AUDIT, new Gson().toJson(Map.of("date", plan.allocation().date().toString(),
                        "source", ElmAllocation.SOURCE, "cash", plan.allocation().cash(), "bonds", plan.allocation().bonds(),
                        "equities", plan.allocation().equities(), "securityId", security.getUUID())));
        plan.changes().stream().map(Change::taxonomyId).distinct()
                        .forEach(id -> taxonomy(client, id).notifyAssignmentsChanged());
        client.markDirty();
    }

    public static Security security(Client client, String id)
    {
        return client.getSecurities().stream().filter(s -> Objects.equals(s.getUUID(), id)).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Le titre ELM sélectionné est introuvable."));
    }

    public static Taxonomy taxonomy(Client client, String id)
    {
        return client.getTaxonomies().stream().filter(t -> Objects.equals(t.getId(), id)).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Une taxonomie sélectionnée est introuvable."));
    }

    private static Classification classification(Taxonomy taxonomy, String id)
    {
        var result = taxonomy.getClassificationById(id);
        if (result == null)
            throw new IllegalArgumentException("Une catégorie sélectionnée est introuvable dans " + taxonomy.getName());
        return result;
    }

    private static Classification classificationIncludingRoot(Taxonomy taxonomy, String id)
    {
        return taxonomy.getRoot().getId().equals(id) ? taxonomy.getRoot() : classification(taxonomy, id);
    }

    public static String path(Classification category)
    {
        var names = new ArrayList<String>();
        for (var current = category; current != null && current.getParent() != null; current = current.getParent())
            names.addFirst(current.getName());
        return names.isEmpty() ? category.getName() : String.join(" / ", names);
    }
}
