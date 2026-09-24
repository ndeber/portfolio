package name.abuchen.portfolio.updates.bondallocation;

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

import com.google.gson.Gson;

import name.abuchen.portfolio.model.Classification;
import name.abuchen.portfolio.model.Classification.Assignment;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.model.Taxonomy;
import name.abuchen.portfolio.updates.bondallocation.BondComposition.Family;
import name.abuchen.portfolio.updates.bondallocation.BondComposition.Outcome;

/** A previewable, stale-safe plan confined to the four Obligations taxonomies. */
public final class BondAllocationAdjustment
{
    public static final String AUDIT = "fork.bondAllocation.lastUpdate";
    public record Change(String taxonomyId, String categoryId, String categoryName, boolean create,
                    String securityId, String location, int before, int after) { }
    public record Plan(String fingerprint, List<Change> changes, List<String> notices, List<Outcome> sources)
    {
        public Plan { changes = List.copyOf(changes); notices = List.copyOf(notices); sources = List.copyOf(sources); }
        public boolean isEmpty() { return changes.isEmpty(); }
    }
    private BondAllocationAdjustment() { }

    public static boolean isBondTaxonomy(String name)
    {
        return java.util.Arrays.stream(Family.values()).anyMatch(f -> f.matches(name));
    }

    public static Taxonomy taxonomy(Client client, Family family)
    {
        var found = client.getTaxonomies().stream().filter(t -> family.matches(t.getName())).toList();
        if (found.size() != 1) throw new IllegalArgumentException("Taxonomie absente ou ambiguë : " + family.title());
        return found.getFirst();
    }

    public static List<Security> scope(Client client, LocalDate date)
    {
        var asset = client.getTaxonomies().stream().filter(t -> "Classes d'actifs".equalsIgnoreCase(t.getName().strip())).toList();
        if (asset.size() != 1) throw new IllegalArgumentException("Taxonomie Classes d'actifs absente ou ambiguë.");
        var actions = asset.getFirst().getAllClassifications().stream().filter(c -> "Obligations".equalsIgnoreCase(c.getName().strip())).toList();
        if (actions.size() != 1) throw new IllegalArgumentException("Catégorie Obligations absente ou ambiguë dans Classes d'actifs.");
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
                        && ((members.contains(s) && balances.getOrDefault(s, 0L) > 0) || BondAllocationSources.forcedIsins().contains(s.getIsin() == null ? "" : s.getIsin())))
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
        // Require the four existing taxonomies. Never invent or rebuild a taxonomy.
        for (var f : Family.values()) taxonomy(client, f);
        var changes = new ArrayList<Change>();
        var notices = new ArrayList<String>();
        var seen = new HashSet<String>();
        var eligible = scope(client, LocalDate.now()).stream().map(Security::getUUID).toList();
        for (var outcome : outcomes)
        {
            if (!seen.add(outcome.securityId() + outcome.family())) throw new IllegalArgumentException("Résultat de source dupliqué.");
            var s = security(client, outcome.securityId());
            if (!eligible.contains(s.getUUID())) throw new IllegalArgumentException("Titre hors périmètre Obligations : " + s.getName());
            String prefix = outcome.family().title() + " / " + s.getName();
            if (outcome.error() != null) { notices.add(prefix + " — inchangé : " + outcome.error()); continue; }
            try
            {
                // Local staging: an unknown or ambiguous category keeps this entire slice intact.
                changes.addAll(prepareSlice(client, s, outcome));
                var data = outcome.slice();
                notices.add(prefix + " — " + (data.date() == null ? "date non publiée" : data.date())
                                + " · " + data.note()
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
        var targets = new LinkedHashMap<String, Integer>();
        for (var entry : outcome.slice().weights().entrySet())
        {
            var matches = t.getAllClassifications().stream().filter(c -> c != t.getRoot())
                            .filter(c -> BondComposition.normalize(c.getName()).equals(BondComposition.normalize(entry.getKey()))).toList();
            if (matches.size() != 1) throw new IllegalArgumentException("Catégorie absente ou ambiguë : " + entry.getKey());
            targets.merge(matches.getFirst().getId(), entry.getValue(), Math::addExact);
        }
        var result = new ArrayList<Change>();
        for (var c : t.getAllClassifications())
        {
            var assignments = c.getAssignments().stream().filter(a -> a.getInvestmentVehicle().equals(security)).toList();
            int before = Math.toIntExact(assignments.stream().mapToLong(Assignment::getWeight).sum());
            int after = targets.getOrDefault(c.getId(), 0);
            if (before != after || assignments.size() > 1 || (after == 0 && !assignments.isEmpty()))
                result.add(new Change(t.getId(), c.getId(), c.getName(), false, security.getUUID(),
                                t.getName() + " / " + c.getName() + " / " + security.getName(), before, after));
        }
        return result;
    }

    public static void apply(Client client, Plan plan)
    {
        var verified = prepare(client, plan.sources());
        if (!fingerprint(client).equals(plan.fingerprint()) || !verified.changes().equals(plan.changes()))
            throw new IllegalArgumentException("Le portefeuille a changé depuis l'aperçu. Relancez l'actualisation.");
        // Prepare serializable audit before mutating anything (no reflective LocalDate serialization).
        var audit = new Gson().toJson(Map.of("retrieved", LocalDate.now().toString(), "notices", plan.notices()));
        var touched = new LinkedHashSet<Taxonomy>();
        for (var change : plan.changes())
        {
            var t = client.getTaxonomies().stream().filter(x -> x.getId().equals(change.taxonomyId())).findFirst().orElseThrow();
            var c = t.getClassificationById(change.categoryId());
            if (t.getRoot().getId().equals(change.categoryId())) c = t.getRoot();
            if (c == null) throw new IllegalStateException("Catégorie disparue.");
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
