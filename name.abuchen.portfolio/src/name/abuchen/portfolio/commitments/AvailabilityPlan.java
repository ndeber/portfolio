package name.abuchen.portfolio.commitments;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.IntStream;
import com.google.gson.Gson;
import name.abuchen.portfolio.model.*;

/** Persistent input percentages. The availability taxonomy is a projection of this plan. */
public final class AvailabilityPlan
{
    public static final String PROPERTY = "fork.xapa.availability.plan.v1";
    public static final List<Integer> HORIZONS;
    static
    {
        var years = new ArrayList<Integer>(); years.add(0); IntStream.rangeClosed(2026, 2034).forEach(years::add);
        HORIZONS = List.copyOf(years);
    }
    public record Plan(int version, String taxonomyId, Map<String, Map<Integer, Integer>> allocations)
    {
        public Plan
        {
            var copy = new TreeMap<String, Map<Integer, Integer>>();
            allocations.forEach((id, weights) -> copy.put(id, Collections.unmodifiableMap(new TreeMap<>(weights))));
            allocations = Collections.unmodifiableMap(copy);
        }
    }
    private AvailabilityPlan() { }
    public static String label(int year) { return year == 0 ? "Immédiate" : Integer.toString(year); }
    public static int planned(Map<Integer, Integer> weights)
    { return weights.values().stream().reduce(0, Math::addExact); }
    public static void validate(Plan plan)
    {
        if (plan.version() != 1) throw new IllegalArgumentException("Version du tableau des disponibilités non prise en charge.");
        for (var row : plan.allocations().entrySet())
        {
            UUID.fromString(row.getKey());
            for (var weight : row.getValue().entrySet())
                if (!HORIZONS.contains(weight.getKey()) || weight.getValue() == null || weight.getValue() < 0 || weight.getValue() > 10000)
                    throw new IllegalArgumentException("Pourcentage ou année invalide.");
            if (planned(row.getValue()) > 10000) throw new IllegalArgumentException("La répartition d'un fonds dépasse 100 %.");
        }
    }
    public static List<Security> securities(Client client, Plan plan, LocalDate date)
    {
        var scoped = Availability.scope(client);
        var held = new HashMap<Security, Long>();
        scoped.getPortfolios().forEach(p -> p.getTransactions().stream().filter(t -> !t.getDateTime().toLocalDate().isAfter(date)).forEach(t ->
                        held.merge(t.getSecurity(), t.getType().isPurchase() ? t.getShares() : -t.getShares(), Long::sum)));
        return scoped.getSecurities().stream().filter(s -> held.getOrDefault(s, 0L) != 0 || plan.allocations().containsKey(s.getUUID()))
                        .sorted(Comparator.comparing(Security::getName)).toList();
    }
    public static Plan load(Client client)
    {
        var stored = client.getProperty(PROPERTY);
        if (stored != null)
        {
            try { var plan = new Gson().fromJson(stored, Plan.class); validate(plan); return plan; }
            catch (RuntimeException e) { throw new IllegalArgumentException("Tableau des disponibilités illisible : " + e.getMessage(), e); }
        }
        var matches = client.getTaxonomies().stream().filter(t -> Availability.NAME.equalsIgnoreCase(t.getName())).toList();
        if (matches.size() > 1) throw new IllegalArgumentException("Plusieurs taxonomies « Date de disponibilité » : les distinguer avant l'initialisation.");
        if (matches.isEmpty()) return new Plan(1, null, Map.of());
        var taxonomy = matches.getFirst();
        var included = new HashSet<>(Availability.scope(client).getSecurities());
        var rows = new HashMap<String, Map<Integer, Integer>>();
        var totals = new HashMap<String, Integer>();
        for (var category : taxonomy.getAllClassifications())
        {
            int year = Availability.horizon(category, taxonomy.getRoot());
            for (var assignment : category.getAssignments())
                if (assignment.getInvestmentVehicle() instanceof Security security && included.contains(security))
                {
                    if (assignment.getWeight() < 0) throw new IllegalArgumentException("Pourcentage négatif dans la taxonomie.");
                    if (totals.merge(security.getUUID(), assignment.getWeight(), Math::addExact) > 10000)
                        throw new IllegalArgumentException("Répartition existante supérieure à 100 % : " + security.getName());
                    var weights = rows.computeIfAbsent(security.getUUID(), id -> new TreeMap<>());
                    if (year != Availability.UNPLANNED) weights.merge(year, assignment.getWeight(), Math::addExact);
                }
        }
        var plan = new Plan(1, taxonomy.getId(), rows); validate(plan); return plan;
    }
    public static Map<InvestmentVehicle, Map<Integer, Integer>> weights(Client client, Plan plan, LocalDate date)
    {
        validate(plan);
        var result = new HashMap<InvestmentVehicle, Map<Integer, Integer>>();
        Availability.scope(client).getAccounts().forEach(a -> result.put(a, Map.of(0, 10000)));
        for (var security : securities(client, plan, date))
        {
            var row = new TreeMap<>(plan.allocations().getOrDefault(security.getUUID(), Map.of()));
            row.put(Availability.UNPLANNED, 10000 - planned(row)); result.put(security, row);
        }
        return result;
    }
    public static Taxonomy apply(Client client, Plan plan)
    {
        validate(plan);
        // Prepare everything before mutating either the source table or the derived taxonomy.
        Taxonomy existing = plan.taxonomyId() == null ? null : client.getTaxonomies().stream().filter(t -> t.getId().equals(plan.taxonomyId())).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Taxonomie de disponibilité supprimée : restaurer la taxonomie avant le recalcul."));
        if (existing == null && client.getTaxonomies().stream().anyMatch(t -> Availability.NAME.equalsIgnoreCase(t.getName())))
            throw new IllegalArgumentException("Une taxonomie de disponibilité a été créée entre-temps. Rouvrir le tableau.");
        var scoped = Availability.scope(client);
        var known = new HashMap<String, Security>(); client.getSecurities().forEach(s -> known.put(s.getUUID(), s));
        for (String id : plan.allocations().keySet())
            if (!known.containsKey(id)) throw new IllegalArgumentException("Un fonds du tableau a été supprimé. Rouvrir le tableau après avoir restauré ce titre.");
        var taxonomy = existing == null ? new Taxonomy(Availability.NAME) : existing;
        var root = existing == null ? new Classification(UUID.randomUUID().toString(), Availability.NAME) : taxonomy.getRoot();
        var categories = new TreeMap<Integer, Classification>();
        var allYears = new ArrayList<>(HORIZONS); allYears.add(Availability.UNPLANNED);
        int rank = 0;
        for (int year : allYears)
        {
            String name = year == Availability.UNPLANNED ? "À planifier" : label(year);
            var category = root.getChildren().stream().filter(c -> c.getName().equals(name)).findFirst()
                            .orElseGet(() -> new Classification(root, UUID.randomUUID().toString(), name));
            categories.put(year, category);
        }
        var assignments = new HashMap<Integer, List<Classification.Assignment>>(); allYears.forEach(y -> assignments.put(y, new ArrayList<>()));
        scoped.getAccounts().forEach(a -> assignments.get(0).add(new Classification.Assignment(a)));
        for (var security : securities(client, plan, LocalDate.now()))
        {
            var weights = plan.allocations().getOrDefault(security.getUUID(), Map.of());
            weights.forEach((year, weight) -> { if (weight > 0) assignments.get(year).add(new Classification.Assignment(security, weight)); });
            int unplanned = 10000 - planned(weights);
            if (unplanned > 0) assignments.get(Availability.UNPLANNED).add(new Classification.Assignment(security, unplanned));
        }
        var stored = new Plan(1, taxonomy.getId(), plan.allocations());
        String serialized = new Gson().toJson(stored);
        // Keep the taxonomy, root and horizon identifiers stable for existing widgets.
        root.getAssignments().clear(); root.getChildren().clear();
        for (int year : allYears)
        {
            var category = categories.get(year); category.getChildren().clear(); category.getAssignments().clear(); category.setRank(rank++);
            assignments.get(year).forEach(category::addAssignment); root.addChild(category);
        }
        taxonomy.setRootNode(root);
        if (existing == null) client.addTaxonomy(taxonomy);
        client.setProperty(PROPERTY, serialized); taxonomy.notifyAssignmentsChanged(); client.markDirty();
        return taxonomy;
    }
}
