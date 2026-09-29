package name.abuchen.portfolio.updates.equity;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.updates.equity.EquityComposition.Item;
import name.abuchen.portfolio.updates.equity.EquityComposition.Slice;

/** Public issuer weights, never inferred from a fund's style box or top holdings. */
public final class EquityCapSources
{
    private static final Map<String, String> PORTS = Map.of(
                    "IE00BK5BQT80", "9679", "IE00BK5BQW10", "9680", "IE00BK5BQX27", "9681",
                    "IE00BK5BQZ41", "9676", "IE00BFMXYX26", "9674", "IE00BK5BR733", "9678",
                    "IE00BK5BQV03", "9675", "IE00B42W4L06", "9159");
    private static final List<String> CODES = List.of("FRCCAPTLTP261", "FRCCAPTLTP263", "FRCCAPTLTP262", "FRCCAPTLTP264", "FRCCAPTLTP265");
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final Map<String, Slice> cache = new ConcurrentHashMap<>();

    public EquityCaps.Outcome fetch(Security security, BooleanSupplier cancelled) throws InterruptedException
    {
        try
        {
            var slice = EquityTask.run(() -> fetch(security.getIsin()), cancelled, Duration.ofSeconds(45));
            return new EquityCaps.Outcome(security.getUUID(), slice, null);
        }
        catch (IOException | RuntimeException e)
        {
            return new EquityCaps.Outcome(security.getUUID(), null, e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private Slice fetch(String isin) throws IOException, InterruptedException
    {
        String port = isin == null ? null : PORTS.get(isin);
        if (port == null) throw new IOException("Pas de source chiffrée de capitalisation prise en charge ; répartition conservée.");
        if (cache.containsKey(isin)) return cache.get(isin);
        var request = new JsonObject();
        String fields = String.join(" ", CODES.stream().map(c -> c + " { effectiveDate analyticValue }").toList());
        request.addProperty("query", "query { polarisAnalyticsHistory(portIds: [\"" + port
                        + "\"]) { monthly { analytics { fund(limit: 1) { items { codes { " + fields + " } } } } } } }");
        var response = http.send(HttpRequest.newBuilder(URI.create(VanguardWorldCountries.ENDPOINT))
                        .timeout(Duration.ofSeconds(35)).header("Content-Type", "application/json")
                        .header("X-Consumer-ID", "uk2").POST(HttpRequest.BodyPublishers.ofString(request.toString())).build(),
                        HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IOException("Vanguard indisponible (HTTP " + response.statusCode() + ").");
        var result = parse(response.body(), port, LocalDate.now());
        cache.put(isin, result);
        return result;
    }

    static Slice parse(String json, String port, LocalDate today) throws IOException
    {
        try
        {
            var root = JsonParser.parseString(json).getAsJsonObject();
            if (root.has("errors")) throw new IOException("Erreur de données Vanguard.");
            var funds = root.getAsJsonObject("data").getAsJsonArray("polarisAnalyticsHistory");
            if (funds.size() != 1) throw new IOException("Réponse Vanguard ambiguë.");
            var rows = funds.get(0).getAsJsonObject().getAsJsonObject("monthly").getAsJsonObject("analytics")
                            .getAsJsonObject("fund").getAsJsonArray("items");
            if (rows.size() != 1) throw new IOException("Capitalisations Vanguard ambiguës.");
            var codes = rows.get(0).getAsJsonObject().getAsJsonObject("codes");
            var values = new java.util.ArrayList<BigDecimal>();
            LocalDate date = null;
            for (String code : CODES)
            {
                var row = codes.getAsJsonObject(code);
                var d = LocalDate.parse(row.get("effectiveDate").getAsString());
                if (d.isAfter(today) || d.isBefore(today.minusDays(90)) || date != null && !date.equals(d))
                    throw new IOException("Dates de capitalisation incohérentes ou trop anciennes.");
                date = d;
                var value = row.get("analyticValue").getAsBigDecimal();
                if (value.signum() < 0 || value.compareTo(new BigDecimal("100")) > 0)
                    throw new IOException("Poids de capitalisation invalide.");
                values.add(value);
            }
            var total = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            if (total.signum() <= 0 || total.compareTo(new BigDecimal("100")) > 0
                            || "9679".equals(port) && total.compareTo(new BigDecimal("99")) < 0)
                throw new IOException("Couverture de capitalisation insuffisante ou invalide : " + total + " %.");
            String source = "https://www.vanguard.co.uk/professional/product/" + ("9159".equals(port) ? "fund" : "etf")
                            + "/equity/" + port + " · Large + Medium/Large → Large Caps ; Medium → Mid Cap ; Medium/Small + Small → Small Caps"
                            + " · Couverture " + total.toPlainString() + " %, reliquat directement sous Actions.";
            return new Slice(List.of(new Item(EquityCaps.LARGE, values.get(0).add(values.get(1))),
                            new Item(EquityCaps.MID, values.get(2)),
                            new Item(EquityCaps.SMALL, values.get(3).add(values.get(4)))), date, source, false);
        }
        catch (RuntimeException e)
        {
            throw new IOException("Capitalisations Vanguard incomplètes ou invalides : répartition conservée.", e);
        }
    }
}
