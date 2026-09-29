package name.abuchen.portfolio.updates.equity;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import com.google.gson.Gson;
import name.abuchen.portfolio.model.*;
import name.abuchen.portfolio.model.Classification.Assignment;
import name.abuchen.portfolio.updates.equity.EquityComposition.Slice;

/** Capitalisation assignments only; preserves each branch's total exposure and targets. */
public final class EquityCaps
{
    public static final String LARGE = "Large Caps", MID = "Mid Cap", SMALL = "Small Caps";
    public record Outcome(String securityId, Slice slice, String error) { }
    public record Creation(String taxonomyId, String parentId, String id, String name, String color, String location) { }
    public record Change(String taxonomyId, String categoryId, String securityId, String location, int before, int after) { }
    public record Plan(String fingerprint, List<Creation> creations, List<Change> changes, List<String> notices, List<Outcome> sources)
    {
        public Plan
        {
            creations = List.copyOf(creations); changes = List.copyOf(changes);
            notices = List.copyOf(notices); sources = List.copyOf(sources);
        }
    }
    private EquityCaps() { }
    static List<Taxonomy> taxonomies(Client client)
    {
        var result = new ArrayList<Taxonomy>();
        for (String name : List.of("Pilotage Global", "Objectifs et Classes d'actifs"))
        {
            var matches = client.getTaxonomies().stream().filter(t -> name.equalsIgnoreCase(t.getName().strip())).toList();
            if (matches.size() > 1) throw new IllegalArgumentException("Taxonomie ambiguë : " + name);
            result.addAll(matches);
        }
        return result;
    }
    static String key(String name)
    {
        return switch (name.strip().toLowerCase(Locale.ROOT))
        {
            case "large cap", "large caps" -> LARGE;
            case "mid cap", "mid caps" -> MID;
            case "small cap", "small caps" -> SMALL;
            default -> "";
        };
    }
    static List<Classification> parents(Taxonomy tax)
    {
        return tax.getAllClassifications().stream().filter(c -> c.getName().equalsIgnoreCase("Actions"))
                        .filter(c -> c.getChildren().stream().anyMatch(x -> key(x.getName()).equals(LARGE))
                                        && c.getChildren().stream().anyMatch(x -> key(x.getName()).equals(SMALL))).toList();
    }
    private static List<Classification> categories(Classification parent)
    {
        var result = new ArrayList<Classification>(); result.add(parent);
        for (String key : List.of(LARGE, MID, SMALL))
        {
            var matches = parent.getChildren().stream().filter(c -> key(c.getName()).equals(key)).toList();
            if (matches.size() > 1) throw new IllegalArgumentException("Catégorie de capitalisation ambiguë : " + key);
            result.addAll(matches);
        }
        return result;
    }
    private static int weight(Classification c, String id)
    { return Math.toIntExact(c.getAssignments().stream().filter(a -> a.getInvestmentVehicle().getUUID().equals(id)).mapToLong(Assignment::getWeight).sum()); }
    public static boolean eligible(Client client, Security security)
    {
        return taxonomies(client).stream().flatMap(t -> parents(t).stream()).anyMatch(p ->
                        categories(p).stream().mapToInt(c -> weight(c, security.getUUID())).sum() > 0);
    }
    private static String categoryId(Taxonomy tax, Classification parent, String key)
    { return UUID.nameUUIDFromBytes(("equity-caps:" + tax.getId() + ":" + parent.getId() + ":" + key).getBytes(StandardCharsets.UTF_8)).toString(); }
    public static Plan prepare(Client client, List<Outcome> outcomes)
    {
        var creations = new ArrayList<Creation>(); var changes = new ArrayList<Change>(); var notices = new ArrayList<String>();
        var seen = new HashSet<String>();
        for (var o : outcomes)
            if (!seen.add(o.securityId()) || client.getSecurities().stream().noneMatch(s -> s.getUUID().equals(o.securityId()))
                            || (o.slice() == null) == (o.error() == null)) throw new IllegalArgumentException("Résultat capitalisation invalide.");
        for (var tax : taxonomies(client))
            if (parents(tax).isEmpty()) notices.add(tax.getName() + " — aucune branche Actions avec Large Caps et Small Caps ; inchangée.");
        notices.add("Capitalisations : Large + Medium/Large → Large Caps ; Medium → Mid Cap ; Medium/Small + Small → Small Caps. Les objectifs existants sont conservés ; la nouvelle cible Mid Cap est à définir (0 %). Le reliquat non publié reste directement sous Actions.");
        for (var tax : taxonomies(client)) for (var parent : parents(tax))
        {
            var cats = categories(parent);
            if (cats.stream().skip(1).anyMatch(c -> !c.getChildren().isEmpty()))
                throw new IllegalArgumentException("Les catégories de capitalisation doivent être des feuilles : " + tax.getName());
            var ids = new LinkedHashMap<String, String>();
            for (String k : List.of(LARGE, MID, SMALL))
            {
                var existing = cats.stream().filter(c -> key(c.getName()).equals(k)).findFirst();
                if (existing.isPresent()) ids.put(k, existing.get().getId());
                else
                {
                    String id = categoryId(tax, parent, k);
                    if (tax.getClassificationById(id) != null) throw new IllegalArgumentException("Identifiant de catégorie déjà utilisé.");
                    ids.put(k, id);
                    creations.add(new Creation(tax.getId(), parent.getId(), id, k, parent.getColor(), tax.getName() + " / " + parent.getPathName(false) + " / " + k));
                }
            }
            for (var o : outcomes)
            {
                int budget = cats.stream().mapToInt(c -> weight(c, o.securityId())).sum();
                if (budget == 0) continue;
                if (budget < 0 || budget > Classification.ONE_HUNDRED_PERCENT) throw new IllegalArgumentException("Poids de capitalisation invalide.");
                var security = client.getSecurities().stream().filter(s -> s.getUUID().equals(o.securityId())).findFirst().orElseThrow();
                if (o.error() != null) { notices.add(tax.getName() + " / " + security.getName() + " — inchangé : " + o.error()); continue; }
                var p = new LinkedHashMap<String, BigDecimal>();
                for (var item : o.slice().items())
                {
                    if (!List.of(LARGE, MID, SMALL).contains(item.name())) throw new IllegalArgumentException("Taille de capitalisation inconnue.");
                    p.merge(item.name(), item.percent(), BigDecimal::add);
                }
                var allocation = allocate(p, budget);
                var wanted = new LinkedHashMap<String, Integer>(); int used = 0;
                for (String k : List.of(LARGE, MID, SMALL))
                {
                    int w = allocation.get(k);
                    used += w;
                    String id = ids.get(k);
                    wanted.put(id, w);
                }
                // Residual is explicitly assigned to Actions; never invented as Large or Small.
                wanted.put(parent.getId(), budget - used);
                for (var entry : wanted.entrySet())
                {
                    var c = cats.stream().filter(x -> x.getId().equals(entry.getKey())).findFirst().orElse(null);
                    int before = c == null ? 0 : weight(c, o.securityId());
                    long count = c == null ? 0 : c.getAssignments().stream().filter(a -> a.getInvestmentVehicle().getUUID().equals(o.securityId())).count();
                    if (before != entry.getValue() || count > 1)
                        changes.add(new Change(tax.getId(), entry.getKey(), o.securityId(), tax.getName() + " / " + parent.getPathName(false) + " / "
                                        + (c == parent ? "Capitalisation non publiée" : c == null ? ids.entrySet().stream().filter(x -> x.getValue().equals(entry.getKey())).findFirst().orElseThrow().getKey() : c.getName()) + " / " + security.getName(), before, entry.getValue()));
                }
                notices.add(tax.getName() + " / " + security.getName() + " — capitalisations au " + o.slice().date() + " · " + o.slice().source());
            }
        }
        if (taxonomies(client).isEmpty()) notices.add("Capitalisations — taxonomies de pilotage absentes, inchangées.");
        return new Plan(fingerprint(client), List.copyOf(creations), List.copyOf(changes), List.copyOf(notices), List.copyOf(outcomes));
    }
    static Map<String, Integer> allocate(Map<String, BigDecimal> percentages, int budget)
    {
        var p = new LinkedHashMap<String, BigDecimal>();
        for (String key : List.of(LARGE, MID, SMALL)) p.put(key, percentages.getOrDefault(key, BigDecimal.ZERO));
        var total = p.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.signum() <= 0 || total.compareTo(new BigDecimal("100")) > 0) throw new IllegalArgumentException("Répartition de capitalisation invalide.");
        p.put("", new BigDecimal("100").subtract(total));
        var units = new LinkedHashMap<String, Integer>(); var fractions = new HashMap<String, BigDecimal>();
        p.forEach((k,v) -> { var n=v.multiply(BigDecimal.valueOf(budget)).movePointLeft(2); units.put(k,n.intValue()); fractions.put(k,n.remainder(BigDecimal.ONE)); });
        var order = new ArrayList<>(p.keySet()); order.sort(Comparator.<String,BigDecimal>comparing(fractions::get).reversed().thenComparing(x -> x));
        int missing = budget - units.values().stream().mapToInt(Integer::intValue).sum();
        for (int n=0;n<missing;n++) units.merge(order.get(n),1,Integer::sum);
        return units;
    }
    public static void validate(Client client, Plan plan)
    {
        if (!prepare(client, plan.sources()).equals(plan)) throw new IllegalArgumentException("Les capitalisations ont changé depuis l'aperçu. Relancer l'actualisation.");
    }
    public static void apply(Client client, Plan plan)
    {
        validate(client, plan);
        for (var creation : plan.creations())
        {
            var t = taxonomies(client).stream().filter(x -> x.getId().equals(creation.taxonomyId())).findFirst().orElseThrow();
            var parent = t.getClassificationById(creation.parentId());
            var c = new Classification(parent, creation.id(), creation.name(), creation.color()); c.setWeight(0);
            c.setRank(parent.getChildren().stream().mapToInt(Classification::getRank).max().orElse(0) + 1); parent.addChild(c);
        }
        for (var change : plan.changes())
        {
            var t = taxonomies(client).stream().filter(x -> x.getId().equals(change.taxonomyId())).findFirst().orElseThrow();
            var c = t.getClassificationById(change.categoryId());
            var s = client.getSecurities().stream().filter(x -> x.getUUID().equals(change.securityId())).findFirst().orElseThrow();
            var old = c.getAssignments().stream().filter(a -> a.getInvestmentVehicle().equals(s)).toList();
            var a = old.isEmpty() ? new Assignment(s) : old.getFirst(); c.getAssignments().removeAll(old);
            if (change.after() > 0) { a.setWeight(change.after()); c.addAssignment(a); }
        }
        client.setProperty("fork.equity.caps.lastUpdate", new Gson().toJson(plan.notices()));
        taxonomies(client).forEach(Taxonomy::notifyAssignmentsChanged); client.markDirty();
    }
    private static String fingerprint(Client client)
    {
        var data = new ArrayList<Object>();
        for (var t : taxonomies(client)) for (var c : t.getAllClassifications())
        {
            data.add(List.of(t.getId(),t.getName(),c.getId(),c.getName(),c.getWeight(),c.getColor(),c.getRank(),c.getParent()==null?"":c.getParent().getId()));
            c.getAssignments().forEach(a -> data.add(List.of(a.getInvestmentVehicle().getUUID(),a.getWeight(),a.getRank())));
        }
        return new Gson().toJson(data);
    }
}
