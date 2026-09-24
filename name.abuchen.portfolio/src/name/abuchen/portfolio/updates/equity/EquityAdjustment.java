package name.abuchen.portfolio.updates.equity;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.google.gson.Gson;

import name.abuchen.portfolio.model.Classification;
import name.abuchen.portfolio.model.Classification.Assignment;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.model.Taxonomy;
import name.abuchen.portfolio.updates.equity.EquityComposition.Family;
import name.abuchen.portfolio.updates.equity.EquityComposition.Outcome;

/** A previewable, stale-safe plan confined to the three Actions taxonomies. */
public final class EquityAdjustment
{
    public static final String AUDIT = "fork.equity.lastUpdate";
    public record Change(String taxonomyId, String categoryId, String categoryName, boolean create,
                    String securityId, String location, int before, int after) { }
    public record Plan(String fingerprint, List<Change> changes, List<String> notices, List<Outcome> sources)
    {
        public Plan { changes = List.copyOf(changes); notices = List.copyOf(notices); sources = List.copyOf(sources); }
    }
    private EquityAdjustment() { }

    public static boolean isActionsTaxonomy(String name)
    {
        return java.util.Arrays.stream(Family.values()).anyMatch(f -> f.title().equalsIgnoreCase(name.strip()));
    }

    public static Taxonomy taxonomy(Client client, Family family)
    {
        var found = client.getTaxonomies().stream().filter(t -> family.title().equalsIgnoreCase(t.getName().strip())).toList();
        if (found.size() != 1) throw new IllegalArgumentException("Taxonomie absente ou ambiguë : " + family.title());
        return found.getFirst();
    }

    public static List<Security> scope(Client client, LocalDate date)
    {
        var asset = client.getTaxonomies().stream().filter(t -> "Classes d'actifs".equalsIgnoreCase(t.getName().strip())).toList();
        if (asset.size() != 1) throw new IllegalArgumentException("Taxonomie Classes d'actifs absente ou ambiguë.");
        var actions = asset.getFirst().getAllClassifications().stream().filter(c -> "Actions".equalsIgnoreCase(c.getName().strip())).toList();
        if (actions.size() != 1) throw new IllegalArgumentException("Catégorie Actions absente ou ambiguë dans Classes d'actifs.");
        var members = new HashSet<Security>();
        collect(actions.getFirst(), members);
        var balances = new LinkedHashMap<Security, Long>();
        for (var portfolio : client.getPortfolios())
            if (!portfolio.isRetired())
                for (var tx : portfolio.getTransactions())
                    if (!tx.getDateTime().toLocalDate().isAfter(date) && tx.getSecurity() != null)
                    {
                        int sign = switch (tx.getType())
                        {
                            case BUY, DELIVERY_INBOUND, TRANSFER_IN -> 1;
                            case SELL, DELIVERY_OUTBOUND, TRANSFER_OUT -> -1;
                            default -> 0;
                        };
                        balances.merge(tx.getSecurity(), Math.multiplyExact(sign, tx.getShares()), Math::addExact);
                    }
        return client.getSecurities().stream().filter(s -> !s.isRetired()
                        && ((members.contains(s) && balances.getOrDefault(s, 0L) > 0) || "IE00BK5BQT80".equals(s.getIsin())))
                        .sorted(Comparator.comparing((Security s) -> s.getName()).thenComparing(Security::getUUID)).toList();
    }

    private static void collect(Classification c, Set<Security> found)
    {
        for (var a : c.getAssignments())
            if (a.getWeight() > 0 && a.getInvestmentVehicle() instanceof Security s) found.add(s);
        c.getChildren().forEach(child -> collect(child, found));
    }

    public static Plan prepare(Client client, List<Outcome> outcomes)
    {
        // Require the three existing taxonomies. Never invent or rebuild a taxonomy.
        for (var f : Family.values()) taxonomy(client, f);
        var changes = new ArrayList<Change>();
        var notices = new ArrayList<String>();
        var seen = new HashSet<String>();
        var eligible = scope(client, LocalDate.now()).stream().map(Security::getUUID).toList();
        for (var outcome : outcomes)
        {
            if (!seen.add(outcome.securityId() + outcome.family())) throw new IllegalArgumentException("Résultat de source dupliqué.");
            var s = security(client, outcome.securityId());
            if (!eligible.contains(s.getUUID())) throw new IllegalArgumentException("Titre hors périmètre Actions : " + s.getName());
            String prefix = outcome.family().title() + " / " + s.getName();
            if (outcome.error() != null) { notices.add(prefix + " — inchangé : " + outcome.error()); continue; }
            try
            {
                // Local staging: an unknown or ambiguous category keeps this entire slice intact.
                changes.addAll(prepareSlice(client, s, outcome));
                var data = outcome.slice();
                notices.add(prefix + " — " + (data.date() == null ? "date non publiée" : data.date())
                                + (data.staticProfile() ? " · profil fixe du script" : "")
                                + (data.date() != null && data.date().isBefore(LocalDate.now().minusDays(90)) ? " · DONNÉES ANCIENNES" : "")
                                + " · " + data.source());
            }
            catch (IllegalArgumentException e) { notices.add(prefix + " — inchangé : " + e.getMessage()); }
        }
        return new Plan(fingerprint(client), changes, notices, outcomes);
    }

    private static List<Change> prepareSlice(Client client, Security security, Outcome outcome)
    {
        var t = taxonomy(client, outcome.family());
        String residual = outcome.family() == Family.REGIONS ? EquitySources.residual(security.getIsin()) : "Other";
        var weights = EquityComposition.weights(outcome.slice(), outcome.family(), residual);
        var targets = new LinkedHashMap<String, Change>();
        for (var entry : weights.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList())
        {
            String key = entry.getKey();
            var matches = t.getAllClassifications().stream().filter(c -> c != t.getRoot())
                            .filter(c -> outcome.family() == Family.HOLDINGS ? EquityComposition.holdingKey(c.getName()).equals(key) : c.getName().equals(key))
                            .filter(c -> !(outcome.family() == Family.REGIONS && key.equals(residual)
                                            && EquitySources.topLevelResidual(security.getIsin())) || c.getParent() == t.getRoot()).toList();
            if (matches.size() > 1) throw new IllegalArgumentException("Catégorie ambiguë : " + key);
            if (matches.isEmpty() && outcome.family() != Family.HOLDINGS)
                throw new IllegalArgumentException("Catégorie manquante : " + key);
            boolean create = matches.isEmpty();
            String id = create ? UUID.nameUUIDFromBytes(("equity:" + t.getId() + ":" + key).getBytes(StandardCharsets.UTF_8)).toString() : matches.getFirst().getId();
            if (create && t.getClassificationById(id) != null) throw new IllegalArgumentException("Identifiant de catégorie déjà utilisé.");
            String label = create ? EquityComposition.holdingLabel(key, outcome.slice()) : matches.getFirst().getName();
            targets.put(id, new Change(t.getId(), id, label, create, security.getUUID(),
                            t.getName() + " / " + label + " / " + security.getName(), 0, entry.getValue()));
        }
        var result = new ArrayList<Change>();
        for (var c : t.getAllClassifications())
        {
            var assignments = c.getAssignments().stream().filter(a -> a.getInvestmentVehicle().equals(security)).toList();
            int before = Math.toIntExact(assignments.stream().mapToLong(Assignment::getWeight).sum());
            var target = targets.remove(c.getId());
            int after = target == null ? 0 : target.after();
            if (before != after || assignments.size() > 1 || (after == 0 && !assignments.isEmpty()))
                result.add(new Change(t.getId(), c.getId(), c.getName(), false, security.getUUID(),
                                t.getName() + " / " + c.getName() + " / " + security.getName(), before, after));
        }
        result.addAll(targets.values());
        return result;
    }

    public static void apply(Client client, Plan plan)
    {
        if (!fingerprint(client).equals(plan.fingerprint()) || !prepare(client, plan.sources()).changes().equals(plan.changes()))
            throw new IllegalArgumentException("Le portefeuille a changé depuis l'aperçu. Relancez l'actualisation.");
        // Prepare serializable audit before mutating anything (no reflective LocalDate serialization).
        var audit = new Gson().toJson(Map.of("retrieved", LocalDate.now().toString(), "notices", plan.notices()));
        var touched = new LinkedHashSet<Taxonomy>();
        for (var change : plan.changes())
        {
            var t = client.getTaxonomies().stream().filter(x -> x.getId().equals(change.taxonomyId())).findFirst().orElseThrow();
            var c = t.getClassificationById(change.categoryId());
            if (t.getRoot().getId().equals(change.categoryId())) c = t.getRoot();
            if (c == null)
            {
                c = new Classification(t.getRoot(), change.categoryId(), change.categoryName(), "#8baeb5");
                c.setWeight(0);
                c.setRank(t.getRoot().getChildren().stream().mapToInt(Classification::getRank).max().orElse(-1) + 1);
                t.getRoot().addChild(c);
            }
            var security = security(client, change.securityId());
            var old = c.getAssignments().stream().filter(a -> a.getInvestmentVehicle().equals(security)).toList();
            var assignment = old.isEmpty() ? new Assignment(security) : old.getFirst();
            c.getAssignments().removeAll(old);
            if (change.after() > 0)
            {
                assignment.setWeight(change.after());
                if (old.isEmpty()) assignment.setRank(c.getAssignments().stream().mapToInt(Assignment::getRank).max().orElse(-1) + 1);
                c.addAssignment(assignment);
            }
            touched.add(t);
        }
        client.setProperty(AUDIT, audit);
        touched.forEach(Taxonomy::notifyAssignmentsChanged);
        client.markDirty();
    }

    private static Security security(Client client, String id)
    {
        return client.getSecurities().stream().filter(s -> Objects.equals(s.getUUID(), id)).findFirst().orElseThrow();
    }

    private static String fingerprint(Client client)
    {
        var values = new ArrayList<Object>();
        for (var f : Family.values())
        {
            var t = taxonomy(client, f);
            values.add(List.of(t.getId(), t.getName()));
            for (var c : t.getAllClassifications())
            {
                values.add(List.of(c.getId(), c.getName(), c.getWeight(), c.getRank(), c.getColor(), c.getParent() == null ? "" : c.getParent().getId()));
                for (var a : c.getAssignments()) values.add(List.of(a.getInvestmentVehicle().getUUID(), a.getWeight(), a.getRank()));
            }
        }
        for (var s : scope(client, LocalDate.now())) values.add(List.of(s.getUUID(), s.getName(), s.getIsin() == null ? "" : s.getIsin()));
        return new Gson().toJson(values);
    }
}
