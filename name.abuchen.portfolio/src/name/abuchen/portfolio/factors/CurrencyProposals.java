package name.abuchen.portfolio.factors;

import java.time.LocalDate;
import java.util.*;
import com.google.gson.Gson;
import com.google.gson.JsonParser;
import name.abuchen.portfolio.model.*;
import name.abuchen.portfolio.commitments.AssetClasses;
import name.abuchen.portfolio.commitments.Commitments;
import name.abuchen.portfolio.updates.elm.ElmAdjustment;

/** Read-only proposals followed by an explicit, stale-safe application. */
@SuppressWarnings("nls")
public final class CurrencyProposals
{
    public static final String AUDIT = "fork.factor-map.currencies.audit.v1";
    public static final String ELM = "US81752T4296";
    public static final Gson JSON = new com.google.gson.GsonBuilder().registerTypeAdapter(LocalDate.class,
                    new com.google.gson.TypeAdapter<LocalDate>() {
                        @Override public void write(com.google.gson.stream.JsonWriter out, LocalDate value) throws java.io.IOException { out.value(value.toString()); }
                        @Override public LocalDate read(com.google.gson.stream.JsonReader in) throws java.io.IOException { return LocalDate.parse(in.nextString()); }
                    }.nullSafe()).create();

    public record Evidence(Map<String, Integer> weights, String method, LocalDate date, String source, String note,
                    boolean estimated)
    {
        public Evidence
        {
            weights = Map.copyOf(weights);
            Objects.requireNonNull(method); Objects.requireNonNull(source); Objects.requireNonNull(note);
            int sum = 0;
            for (var e : weights.entrySet())
            {
                if ((!e.getKey().equals("UNKNOWN") && !e.getKey().matches("[A-Z]{3}")) || e.getValue() <= 0 || e.getValue() > 10000)
                    throw new IllegalArgumentException("Répartition devises invalide.");
                sum = Math.addExact(sum, e.getValue());
            }
            if (!weights.isEmpty() && sum != 10000)
                throw new IllegalArgumentException("La proposition doit totaliser 100 %, fraction inconnue comprise.");
        }
    }
    public record Row(String id, String name, String isin, String before, Evidence evidence, boolean explicitOverride) { }
    public record Plan(String fingerprint, LocalDate asof, List<Row> rows)
    {
        public Plan { rows = List.copyOf(rows); }
    }
    private CurrencyProposals() { }

    public static boolean isElm(Client client, Security security)
    {
        if (ELM.equals(security.getIsin())) return true;
        String settings = client.getProperty(ElmAdjustment.SETTINGS);
        if (settings == null) return false;
        var json = JsonParser.parseString(settings).getAsJsonObject();
        return json.has("securityId") && security.getUUID().equals(json.get("securityId").getAsString());
    }

    public static List<Security> scope(Client client, LocalDate today)
    {
        var balances = new HashMap<Security, Long>();
        for (var p : client.getPortfolios())
            if (!p.isRetired())
                for (var t : p.getTransactions())
                    if (t.getSecurity() != null && !t.getDateTime().toLocalDate().isAfter(today))
                    {
                        int sign = switch (t.getType()) {
                            case BUY, DELIVERY_INBOUND, TRANSFER_IN -> 1;
                            case SELL, DELIVERY_OUTBOUND, TRANSFER_OUT -> -1;
                            default -> 0;
                        };
                        balances.merge(t.getSecurity(), Math.multiplyExact(sign, t.getShares()), Math::addExact);
                    }
        var funds = new HashSet<Security>();
        client.getTaxonomies().stream().filter(t -> "Type d'actif".equals(t.getName())).forEach(t -> {
            for (var c : t.getAllClassifications())
                if (Set.of("OPCVM", "ETF", "FCPI Fiscaux", "FCPI Juridiques", "Devise / Monétaire").contains(c.getName()))
                    for (var a : c.getAssignments())
                        if (a.getWeight() > 0 && a.getInvestmentVehicle() instanceof Security s) funds.add(s);
        });
        return client.getSecurities().stream().filter(s -> !s.isRetired() && (isElm(client, s)
                        || balances.getOrDefault(s, 0L) > 0 && (funds.contains(s)
                                        || s.getAttributes().getMap().containsKey(Commitments.TOTAL)
                                        || Set.of("PE", "VC", "Dette privée", "Fonds euros", "Hedge funds").contains(AssetClasses.type(client, s).label()))))
                        .sorted(Comparator.comparing((Security s) -> s.getName()).thenComparing(Security::getUUID)).toList();
    }

    public static Plan prepare(Client client, LocalDate today, Map<String, Evidence> evidence)
    {
        var rows = new ArrayList<Row>();
        var eligible = scope(client, today);
        var ids = eligible.stream().map(Security::getUUID).toList();
        if (!ids.containsAll(evidence.keySet())) throw new IllegalArgumentException("Proposition hors périmètre.");
        var overrides = FactorSettings.load(client);
        for (var s : eligible)
        {
            var e = evidence.getOrDefault(s.getUUID(), missing("Source de devises après couverture non disponible."));
            if (e.date() != null && e.date().isAfter(today)) throw new IllegalArgumentException("Source future : " + s.getName());
            rows.add(new Row(s.getUUID(), s.getName(), Objects.toString(s.getIsin(), ""), current(client, s), e,
                            overrides.mappings().containsKey(s.getUUID())));
        }
        return new Plan(fingerprint(client, today), today, rows);
    }

    public static Evidence missing(String note)
    {
        return new Evidence(Map.of(), "À documenter", null, "", note, false);
    }

    public static String format(Map<String, Integer> weights)
    {
        return weights.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(e ->
                        (e.getKey().equals("UNKNOWN") ? "À renseigner" : e.getKey()) + " "
                                        + String.format(Locale.FRANCE, "%.2f %%", e.getValue() / 100d))
                        .collect(java.util.stream.Collectors.joining(" · "));
    }

    public static String code(Classification c)
    {
        return c.getKey().startsWith("factor.fx.") ? c.getKey().substring("factor.fx.".length()) : c.getName();
    }

    private static String current(Client client, Security s)
    {
        var tax = CurrencyExposure.find(client);
        if (tax == null) return "Non affecté";
        var weights = new TreeMap<String, Integer>();
        for (var c : tax.getAllClassifications())
            for (var a : c.getAssignments())
                if (a.getInvestmentVehicle().equals(s)) weights.merge(code(c), a.getWeight(), Math::addExact);
        return weights.isEmpty() ? "Non affecté" : format(weights);
    }

    public static void apply(Client client, Plan plan, Set<String> selected)
    {
        if (!fingerprint(client, plan.asof()).equals(plan.fingerprint()))
            throw new IllegalArgumentException("Le portefeuille a changé depuis l'aperçu. Relancer la proposition.");
        var chosen = plan.rows().stream().filter(r -> selected.contains(r.id())).toList();
        if (chosen.size() != selected.size() || chosen.stream().anyMatch(r -> r.evidence().weights().isEmpty()))
            throw new IllegalArgumentException("Sélection sans proposition exploitable.");
        if (chosen.isEmpty()) return;
        var existing = CurrencyExposure.find(client);
        var categories = new HashMap<String, Classification>();
        if (existing != null)
            for (var c : existing.getAllClassifications())
                if (categories.putIfAbsent(code(c), c) != null)
                    throw new IllegalArgumentException("Catégorie devises ambiguë : " + code(c));
        // Validate everything before the first mutation.
        var securities = new HashMap<String, Security>();
        client.getSecurities().forEach(s -> securities.put(s.getUUID(), s));
        for (var row : chosen)
            if (!securities.containsKey(row.id()) || FactorMap.isDbi(securities.get(row.id())))
                throw new IllegalArgumentException("Support non applicable : " + row.name());
        var audit = new TreeMap<String, Row>();
        String prior = client.getProperty(AUDIT);
        if (prior != null)
        {
            var object = JsonParser.parseString(prior).getAsJsonObject();
            object.entrySet().forEach(e -> audit.put(e.getKey(), JSON.fromJson(e.getValue(), Row.class)));
        }
        var taxonomy = CurrencyExposure.ensure(client);
        if (existing == null)
            for (var c : taxonomy.getAllClassifications()) categories.put(code(c), c);
        for (var row : chosen)
        {
            var security = securities.get(row.id());
            var previousAssignments = new HashMap<String, Classification.Assignment>();
            for (var c : taxonomy.getAllClassifications())
                for (var a : c.getAssignments())
                    if (a.getInvestmentVehicle().equals(security)) previousAssignments.putIfAbsent(code(c), a);
            taxonomy.getAllClassifications().forEach(c -> c.getAssignments().removeIf(a -> a.getInvestmentVehicle().equals(security)));
            for (var entry : row.evidence().weights().entrySet())
            {
                var c = categories.get(entry.getKey());
                if (c == null)
                {
                    c = new Classification(taxonomy.getRoot(), UUID.randomUUID().toString(), entry.getKey());
                    c.setKey("factor.fx." + entry.getKey());
                    taxonomy.getRoot().addChild(c); categories.put(entry.getKey(), c);
                }
                var a = previousAssignments.containsKey(entry.getKey()) ? previousAssignments.get(entry.getKey()).copyWith(security)
                                : new Classification.Assignment(security);
                a.setWeight(entry.getValue());
                if (!previousAssignments.containsKey(entry.getKey()))
                    a.setRank(c.getAssignments().stream().mapToInt(Classification.Assignment::getRank).max().orElse(-1) + 1);
                a.setData(AUDIT, JSON.toJson(row.evidence()));
                c.addAssignment(a);
            }
            audit.put(row.id(), row);
        }
        client.setProperty(AUDIT, JSON.toJson(audit));
        taxonomy.notifyAssignmentsChanged(); client.markDirty();
    }

    private static String fingerprint(Client client, LocalDate asof)
    {
        var state = new ArrayList<Object>();
        state.add(Objects.toString(client.getProperty(ElmAdjustment.SETTINGS), ""));
        state.add(Objects.toString(client.getProperty(FactorSettings.PROPERTY), ""));
        state.add(Objects.toString(client.getProperty(AUDIT), ""));
        for (var t : client.getTaxonomies())
        {
            state.add(List.of(t.getId(), t.getName(), Objects.toString(t.getSource(), "")));
            for (var c : t.getAllClassifications())
            {
                state.add(List.of(c.getId(), c.getName(), c.getKey(), c.getWeight(), c.getRank(), c.getColor(),
                                c.getParent() == null ? "" : c.getParent().getId()));
                for (var a : c.getAssignments()) state.add(List.of(a.getInvestmentVehicle().getUUID(), a.getWeight(), a.getRank()));
            }
        }
        for (var s : scope(client, asof)) state.add(List.of(s.getUUID(), s.getName(), Objects.toString(s.getIsin(), ""), Objects.toString(s.getCurrencyCode(), "")));
        return new Gson().toJson(state);
    }
}
