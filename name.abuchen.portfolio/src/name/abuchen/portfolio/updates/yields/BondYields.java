package name.abuchen.portfolio.updates.yields;

import java.time.LocalDate;
import java.util.*;
import com.google.gson.*;
import name.abuchen.portfolio.model.*;
import name.abuchen.portfolio.money.CurrencyConverter;
import name.abuchen.portfolio.snapshot.ClientSnapshot;
import name.abuchen.portfolio.updates.bonds.BondSpec;
import name.abuchen.portfolio.updates.inflation.AftInflation;

/** Dated observations, kept independently from the current display attributes. */
public final class BondYields
{
    public static final String HISTORY = "xapa.ytm.history.v1";
    public static final Set<String> EXTRA = Set.of("IE00BG47KH54", "LU0234688595");
    public record Observation(String id, LocalDate date, double published, Double fees, boolean net,
                    boolean real, String source, String basis)
    {
        public boolean ytw() { return basis.startsWith("YTW"); }
        public Observation
        {
            Objects.requireNonNull(id); Objects.requireNonNull(date); YieldMath.rate(published);
            if (fees != null) YieldMath.net(published, fees, false);
            if (source == null || source.isBlank() || basis == null || basis.isBlank())
                throw new IllegalArgumentException("Source et convention requises.");
        }
    }
    public record Row(Security security, long value, Observation observation, Double gross, Double net, String status) { }
    public record Result(LocalDate date, List<Row> rows, YieldMath.Average average, YieldMath.Inflation inflation, String inflationError)
    {
        public String quality()
        {
            long ytw = rows.stream().filter(r -> r.value() > 0 && (r.gross() != null || r.net() != null) && r.observation().ytw()).count();
            long old = rows.stream().filter(r -> r.value() > 0 && r.observation() != null && r.observation().date().isBefore(date.minusDays(62))).count();
            return "YTW inclus : " + ytw + " position(s) · Données de plus de 62 jours : " + old;
        }
    }
    private BondYields() { }
    public static Client duplicate(Client client)
    {
        try { return ClientFactory.duplicate(client); }
        catch (java.io.IOException e) { throw new IllegalArgumentException("Copie de travail impossible.", e); }
    }

    public static boolean direct(Security security)
    { return BondSpec.find(security.getIsin()).isPresent() || "IT0005554982".equals(security.getIsin()); }

    public static double bondWeight(Client client, Security security)
    {
        if (direct(security)) return 1;
        var taxonomy = client.getTaxonomies().stream().filter(t -> t.getName().equalsIgnoreCase("Classes d'actifs")).findFirst();
        if (taxonomy.isEmpty()) return (security.getIsin() != null && EXTRA.contains(security.getIsin())) ? 1 : 0;
        int weight = 0;
        for (var category : taxonomy.get().getAllClassifications())
        {
            boolean bond = false;
            for (var node = category; node != null; node = node.getParent())
                if (node.getName().equalsIgnoreCase("Obligations")) bond = true;
            if (bond) for (var a : category.getAssignments())
                if (a.getInvestmentVehicle() == security) weight += a.getWeight();
        }
        if (weight < 0 || weight > Classification.ONE_HUNDRED_PERCENT) throw new IllegalArgumentException("Poids obligataire invalide : " + security.getName());
        return weight == 0 && (security.getIsin() != null && EXTRA.contains(security.getIsin())) ? 1 : weight / (double) Classification.ONE_HUNDRED_PERCENT;
    }

    public static List<Observation> history(Client client)
    {
        var result = new ArrayList<Observation>();
        String json = client.getProperty(HISTORY);
        if (json == null) return result;
        for (var element : JsonParser.parseString(json).getAsJsonArray())
        {
            var o = element.getAsJsonObject();
            result.add(new Observation(o.get("id").getAsString(), LocalDate.parse(o.get("date").getAsString()),
                            o.get("published").getAsDouble(), o.has("fees") ? o.get("fees").getAsDouble() : null,
                            o.get("net").getAsBoolean(), o.get("real").getAsBoolean(), o.get("source").getAsString(), o.get("basis").getAsString()));
        }
        return result;
    }
    public static Double attribute(Client client, Security security, String name)
    {
        var matches = client.getSettings().getAttributeTypes().filter(a -> name.equalsIgnoreCase(a.getName().strip()) && a.getTarget() == Security.class).toList();
        if (matches.size() != 1) return null;
        var value = security.getAttributes().get(matches.getFirst());
        return value instanceof Number n && Double.isFinite(n.doubleValue()) ? n.doubleValue() : null;
    }
    public static Result calculate(Client client, CurrencyConverter converter, LocalDate date)
    {
        var history = history(client);
        var prices = new TreeMap<LocalDate, Long>();
        YieldMath.Inflation inflation = null; String error = null;
        try { AftInflation.security(client).getPricesIncludingLatest().forEach(p -> prices.put(p.getDate(), p.getValue())); inflation = YieldMath.trailing(prices, date); }
        catch (RuntimeException e) { error = e.getMessage(); }
        var positions = ClientSnapshot.create(client, converter.with("EUR"), date).getPositionsByVehicle();
        var rows = new ArrayList<Row>();
        for (var security : client.getSecurities())
        {
            double weight = bondWeight(client, security);
            if (weight <= 0) continue;
            var position = positions.get(security);
            long value = position == null ? 0 : Math.round(name.abuchen.portfolio.commitments.Commitments.strictEur(converter).convert(date, position.getPosition().calculateValue()).getAmount() * weight);
            if (value < 0) throw new IllegalArgumentException("Position obligataire négative : " + security.getName());
            if (value <= 0 && !direct(security) && !(security.getIsin() != null && EXTRA.contains(security.getIsin()))) continue;
            var observation = history.stream().filter(o -> o.id().equals(security.getUUID()) && !o.date().isAfter(date))
                            .max(Comparator.comparing(Observation::date)).orElse(null);
            Double gross = null, net = null; String status = "À actualiser";
            if (observation != null)
            {
                status = observation.basis();
                if (!observation.net() && observation.fees() == null) status += " — frais annuels manquants";
                if (observation.date().isBefore(date.minusDays(62))) status += " — donnée ancienne";
                if (observation.real() && inflation == null) status += " — IPCH manquant";
                else
                {
                    double rate = observation.real() ? YieldMath.nominal(observation.published(), inflation.rate()) : observation.published();
                    if (observation.net()) net = rate;
                    else { gross = rate; if (observation.fees() != null) net = YieldMath.net(rate, observation.fees(), false); }
                }
            }
            rows.add(new Row(security, Math.max(0, value), observation, gross, net, status));
        }
        rows.sort(Comparator.comparing(r -> r.security().getName(), String.CASE_INSENSITIVE_ORDER));
        return new Result(date, List.copyOf(rows), YieldMath.average(rows.stream().map(r -> new YieldMath.Weighted(r.value(), r.gross(), r.net())).toList()), inflation, error);
    }
    public static void apply(Client client, List<Observation> updates, CurrencyConverter converter, LocalDate date)
    {
        var history = history(client);
        for (var o : updates)
        {
            if (o.date().isAfter(date) || client.getSecurities().stream().noneMatch(s -> s.getUUID().equals(o.id())))
                throw new IllegalArgumentException("Observation hors périmètre ou future.");
            history.removeIf(old -> old.id().equals(o.id()) && old.date().equals(o.date())); history.add(o);
        }
        var json = new JsonArray();
        for (var o : history)
        {
            var obj = new JsonObject(); obj.addProperty("id", o.id()); obj.addProperty("date", o.date().toString());
            obj.addProperty("published", o.published()); if (o.fees() != null) obj.addProperty("fees", o.fees());
            obj.addProperty("net", o.net()); obj.addProperty("real", o.real()); obj.addProperty("source", o.source()); obj.addProperty("basis", o.basis()); json.add(obj);
        }
        // Validate the complete change on a duplicate before touching the open model.
        var copy = duplicate(client); copy.setProperty(HISTORY, json.toString());
        type(copy, "YTM", "xapa.ytm.gross"); type(copy, "YTM-Frais", "xapa.ytm.net"); type(copy, "Frais", "xapa.ytm.fees");
        var result = calculate(copy, converter, LocalDate.now());
        var grossType = type(client, "YTM", "xapa.ytm.gross");
        var netType = type(client, "YTM-Frais", "xapa.ytm.net");
        var feesType = type(client, "Frais", "xapa.ytm.fees");
        client.setProperty(HISTORY, json.toString());
        var ids = updates.stream().map(Observation::id).collect(java.util.stream.Collectors.toSet());
        for (var row : result.rows()) if (ids.contains(row.security().getUUID()))
        {
            var security = client.getSecurities().stream().filter(s -> s.getUUID().equals(row.security().getUUID())).findFirst().orElseThrow();
            security.getAttributes().put(grossType, row.gross()); security.getAttributes().put(netType, row.net());
            if (row.observation() != null && row.observation().fees() != null) security.getAttributes().put(feesType, row.observation().fees());
        }
        client.markDirty();
    }
    private static AttributeType type(Client client, String name, String id)
    {
        var matches = client.getSettings().getAttributeTypes().filter(a -> name.equalsIgnoreCase(a.getName().strip()) && a.getTarget() == Security.class).toList();
        if (matches.size() > 1 || (matches.size() == 1 && matches.getFirst().getType() != Double.class))
            throw new IllegalArgumentException("Attribut ambigu ou type incompatible : " + name);
        if (!matches.isEmpty()) return matches.getFirst();
        var a = new AttributeType(id); a.setName(name); a.setColumnLabel(name); a.setTarget(Security.class); a.setType(Double.class);
        a.setConverter(AttributeType.PercentConverter.class); client.getSettings().addAttributeType(a); return a;
    }
}
