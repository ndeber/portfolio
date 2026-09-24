package name.abuchen.portfolio.updates.bondallocation;

import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jsoup.Jsoup;

import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.updates.bondallocation.BondComposition.*;
import name.abuchen.portfolio.updates.equity.EquitySources;
import name.abuchen.portfolio.updates.equity.EquityTask;

public final class BondAllocationSources
{
    public static final String VAGF = "IE00BG47KH54";
    public static final String VANGUARD = "https://www.vanguard.co.uk/professional/product/etf/bond/9443/global-aggregate-bond-ucits-etf-eur-hedged-accumulating";
    private static final String API = "https://www.vanguard.co.uk/gpx/graphql";
    private static final JsonObject PROFILES;
    static
    {
        try (var reader = new InputStreamReader(BondAllocationSources.class.getResourceAsStream("profiles.json"), StandardCharsets.UTF_8))
        { PROFILES = JsonParser.parseReader(reader).getAsJsonObject(); }
        catch (IOException e) { throw new ExceptionInInitializerError(e); }
    }
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NORMAL).build();
    private final Map<String, String> pages = new ConcurrentHashMap<>();
    private final EquitySources countries = new EquitySources();

    public static Set<String> forcedIsins()
    {
        var isins = new HashSet<String>(); isins.add(VAGF);
        for (var row : PROFILES.getAsJsonArray("direct")) isins.add(row.getAsJsonObject().get("isin").getAsString());
        return Set.copyOf(isins);
    }

    public List<Outcome> fetch(Security security, LocalDate reference, BooleanSupplier cancelled, Consumer<Family> progress) throws InterruptedException
    {
        var results = new ArrayList<Outcome>();
        for (var family : Family.values())
        {
            if (cancelled.getAsBoolean()) throw new InterruptedException();
            progress.accept(family);
            try
            {
                var slice = EquityTask.run(() -> fetch(security, family, reference), cancelled,
                                Duration.ofSeconds(family == Family.MATURITY && VAGF.equals(security.getIsin()) ? 120 : 45));
                results.add(new Outcome(security.getUUID(), family, slice, null));
            }
            catch (IOException | RuntimeException e)
            { results.add(new Outcome(security.getUUID(), family, null, e.getMessage() == null ? e.toString() : e.getMessage())); }
        }
        return results;
    }

    private Slice fetch(Security security, Family family, LocalDate reference) throws IOException, InterruptedException
    {
        String isin = security.getIsin();
        if (VAGF.equals(isin))
        {
            if (family == Family.MATURITY) return fetchMaturities(reference);
            String html = page(VANGUARD);
            if (!html.contains(VAGF)) throw new IOException("ISIN Vanguard non confirmé.");
            return parseVanguard(html, family);
        }
        var fixed = staticSlice(isin, family, reference);
        if (fixed != null) return fixed;
        if (isin == null || !PROFILES.getAsJsonObject("funds").has(isin))
            throw new IOException("Profil obligataire non pris en charge : affectations conservées.");
        var data = countries.fetchCountries(security);
        var weights = new LinkedHashMap<String, BigDecimal>();
        for (var item : data.items()) weights.merge(region(item.name()), item.percent(), BigDecimal::add);
        return new Slice(percentages(weights, "Autres", false), data.date(), data.source(), "Répartition géographique publiée.");
    }

    public static Slice staticSlice(String isin, Family family, LocalDate reference)
    {
        if (isin == null) return null;
        for (var row : PROFILES.getAsJsonArray("direct"))
        {
            var direct = row.getAsJsonObject();
            if (!isin.equals(direct.get("isin").getAsString())) continue;
            String category = switch (family)
            {
                case RATING -> direct.get("rating").getAsString();
                case MATURITY -> maturityBucket(LocalDate.parse(direct.get("maturity").getAsString()), reference, true);
                case REGIONS -> direct.get("country").getAsString();
                case ISSUER -> "Gouvernement";
            };
            return new Slice(Map.of(category, 10000), null, direct.get("note").getAsString().lines().reduce((a, b) -> b).orElseThrow(),
                            family == Family.RATING ? "PROFIL FIXE DU SCRIPT — notation souveraine non relue en ligne ; date de vérification non fournie."
                                            : "Métadonnées du script ; échéance " + direct.get("maturity").getAsString() + ", tranche recalculée au " + reference + ".");
        }
        var element = PROFILES.getAsJsonObject("funds").get(isin);
        if (element == null || family == Family.REGIONS) return null;
        var fund = element.getAsJsonObject();
        var weights = new LinkedHashMap<String, Integer>();
        String field = family == Family.RATING ? "rating" : "maturity";
        if (family == Family.MATURITY && fund.has("maturity_date"))
            weights.put(maturityBucket(LocalDate.parse(fund.get("maturity_date").getAsString()), reference, true), 10000);
        else
        {
            var rows = family == Family.ISSUER ? PROFILES.getAsJsonObject("issuers").getAsJsonArray(isin) : fund.getAsJsonArray(field);
            for (var row : rows) weights.merge(row.getAsJsonArray().get(0).getAsString(), row.getAsJsonArray().get(1).getAsInt(), Integer::sum);
        }
        return new Slice(weights, null, "Profil du script / " + isin, "PROFIL FIXE DU SCRIPT — date de publication non fournie ;"
                        + (family == Family.MATURITY && fund.has("maturity_date") ? " échéance " + fund.get("maturity_date").getAsString() + ", tranche recalculée au " + reference : " non actualisé en ligne") + ".");
    }

    public static String maturityBucket(LocalDate maturity, LocalDate reference, boolean maturedIsCash)
    {
        if (maturedIsCash && !maturity.isAfter(reference)) return "Cash";
        int[] months = { 6, 12, 24, 36, 48, 60, 84, 120, 180, 240, 300 };
        String[] names = { "< 6 mois", "< 1 an", "< 2 ans", "< 3 ans", "< 4 ans", "< 5 ans", "5-7 ans", "7-10 ans", "10-15 ans", "15-20 ans", "20-25 ans" };
        for (int i = 0; i < months.length; i++) if (!maturity.isAfter(reference.plusMonths(months[i]))) return names[i];
        return "Plus de 25 ans";
    }

    public static String region(String country)
    {
        var alias = PROFILES.getAsJsonObject("countries").get(country);
        String name = alias == null ? EquitySources.alias("COUNTRY_NAMES", country) : alias.getAsString();
        for (var group : PROFILES.getAsJsonArray("regionTree"))
        {
            var row = group.getAsJsonArray();
            if (row.get(0).getAsString().equals(name)) return name;
            for (var item : row.get(1).getAsJsonArray()) if (item.getAsString().equals(name)) return name;
        }
        if (List.of("Autriche", "Danemark", "Finlande", "Grèce", "Islande", "Norvège", "Portugal", "République tchèque", "Europe & Moyen-Orient").contains(name)) return "Europe";
        if (List.of("Israël", "Taïwan").contains(name)) return "Asie-Pacifique";
        if (List.of("Argentine", "Colombie", "Hongrie", "Malaisie", "Pérou", "Philippines", "Pologne", "Roumanie", "Thaïlande").contains(name)) return "Marchés émergents";
        return "Autres";
    }

    public static Map<String, Integer> percentages(Map<String, BigDecimal> source, String residual, boolean positiveExposure)
    {
        var values = new LinkedHashMap<String, BigDecimal>();
        for (var entry : source.entrySet())
        {
            if (entry.getValue().signum() < 0 && !positiveExposure) throw new IllegalArgumentException("Exposition négative non prise en charge.");
            if (entry.getValue().signum() > 0) values.merge(entry.getKey(), entry.getValue(), BigDecimal::add);
        }
        var total = values.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.signum() == 0) throw new IllegalArgumentException("Composition vide.");
        if (!positiveExposure)
        {
            if (total.compareTo(new BigDecimal("100")) > 0) throw new IllegalArgumentException("Composition supérieure à 100 %.");
            values.merge(residual, new BigDecimal("100").subtract(total), BigDecimal::add); total = new BigDecimal("100");
        }
        var weights = new LinkedHashMap<String, Integer>(); var fractions = new LinkedHashMap<String, BigDecimal>(); int sum = 0;
        for (var entry : values.entrySet())
        {
            var exact = entry.getValue().multiply(new BigDecimal("10000")).divide(total, 16, RoundingMode.HALF_UP);
            int points = exact.intValue(); weights.put(entry.getKey(), points); sum += points;
            fractions.put(entry.getKey(), exact.subtract(BigDecimal.valueOf(points)));
        }
        var priority = fractions.keySet().stream().sorted(java.util.Comparator.<String, BigDecimal>comparing(fractions::get).reversed().thenComparing(k -> k)).toList();
        for (int i = 0; i < 10000 - sum; i++) weights.merge(priority.get(i % priority.size()), 1, Integer::sum);
        weights.values().removeIf(v -> v == 0);
        return weights;
    }

    private String page(String url) throws IOException, InterruptedException
    {
        if (!pages.containsKey(url)) pages.put(url, request(HttpRequest.newBuilder(URI.create(url)).GET()));
        return pages.get(url);
    }
    private String request(HttpRequest.Builder request) throws IOException, InterruptedException
    {
        var response = http.send(request.timeout(Duration.ofSeconds(35)).header("User-Agent", "Mozilla/5.0").build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IOException("Source indisponible (HTTP " + response.statusCode() + ").");
        return response.body();
    }

    private static String section(String text, String heading, String end) throws IOException
    {
        int start = text.indexOf(heading); int finish = text.indexOf(end, start + heading.length());
        if (start < 0 || finish < 0) throw new IOException("Table Vanguard absente : " + heading);
        return text.substring(start, finish);
    }
    private static BigDecimal percentage(String table, String label) throws IOException
    {
        var match = Pattern.compile("(?:^|\\s)" + Pattern.quote(label) + "\\s+(-?\\d+(?:\\.\\d+)?)%", Pattern.CASE_INSENSITIVE).matcher(table);
        BigDecimal value = null;
        while (match.find()) value = new BigDecimal(match.group(1));
        if (value == null) throw new IOException("Ligne Vanguard absente : " + label);
        return value;
    }
    private static LocalDate date(String table) throws IOException
    {
        var match = Pattern.compile("As at\\s+(\\d{1,2}\\s+[A-Za-z]+\\s+\\d{4})", Pattern.CASE_INSENSITIVE).matcher(table);
        if (!match.find()) throw new IOException("Date Vanguard absente.");
        for (String pattern : List.of("d MMM uuuu", "d MMMM uuuu"))
            try { return LocalDate.parse(match.group(1), new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(pattern).toFormatter(Locale.ENGLISH)); }
            catch (java.time.format.DateTimeParseException e) { /* try full month */ }
        throw new IOException("Date Vanguard illisible.");
    }

    public static Slice parseVanguard(String html, Family family) throws IOException
    {
        String text = Jsoup.parse(html).text().replace('\u00a0', ' ').replace('\u202f', ' ').replaceAll("\\s+", " ");
        String table = section(text, "Market allocation", "Distribution by credit issuer (% of funds)");
        var weights = new LinkedHashMap<String, BigDecimal>();
        boolean positive = false;
        if (family == Family.REGIONS)
        {
            if (!table.contains("Country Region Fund")) throw new IOException("En-tête géographique Vanguard absent.");
            for (var element : PROFILES.getAsJsonArray("vanguardCountries"))
            {
                String country = element.getAsString();
                var match = Pattern.compile("\\b" + Pattern.quote(country) + "\\s+(?:North America|Europe|Pacific|Emerging Markets|Other)\\s+(-?\\d+(?:\\.\\d+)?)%").matcher(table);
                if (!match.find()) throw new IOException("Pays Vanguard absent : " + country);
                weights.merge(region(country), new BigDecimal(match.group(1)), BigDecimal::add);
            }
        }
        else if (family == Family.RATING)
        {
            if (table.contains("Distribution by credit quality (% of funds)"))
                table = table.substring(table.indexOf("Distribution by credit quality (% of funds)"));
            for (String label : List.of("AAA", "AA", "A", "BBB")) weights.put(label, percentage(table, label));
            if (percentage(table, "Less than BBB").signum() != 0)
                throw new IOException("Vanguard agrège les notations inférieures à BBB : ventilation BB/B/CCC indisponible, notation conservée.");
            weights.put("Non noté", percentage(table, "Not Rated"));
        }
        else if (family == Family.ISSUER)
        {
            table = section(text, "Distribution by credit issuer (% of funds)", "Distribution by credit maturity (% of funds)"); positive = true;
            String[][] groups = {
                {"Gouvernement", "Treasury/Federal", "Gov-Related-Provincials/Municipals", "Gov-Related-Local Authority", "Gov-Related-Sovereign"},
                {"Agences publiques & supranationaux", "Gov-Related-Agencies", "Gov-Related-Supranational"},
                {"Banques & institutions financières", "Corporate-Financial Institutions"},
                {"Entreprises", "Corporate-Industrials", "Corporate-Utilities"},
                {"Crédit titrisé", "Securitized-Mortgage Backed Security Pass-through", "Securitized-Asset Backed Security", "Securitized-Commercial Mortgage Backed Security"},
                {"Cash & monétaire", "Cash"} };
            for (var group : groups)
            {
                BigDecimal value = BigDecimal.ZERO;
                for (int i = 1; i < group.length; i++) value = value.add(percentage(table, group[i]));
                weights.put(group[0], value);
            }
            var publishedTotal = weights.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add).add(percentage(table, "Other"));
            if (publishedTotal.subtract(new BigDecimal("100")).abs().compareTo(new BigDecimal("0.10")) > 0)
                throw new IOException("Table des émetteurs Vanguard incomplète ou incohérente.");
        }
        else throw new IOException("Échéances détaillées nécessaires.");
        return new Slice(percentages(weights, family == Family.RATING ? "Non noté" : "Autres", positive), date(table), VANGUARD,
                        positive ? "Expositions positives normalisées à 100 % (convention du script, couvertures négatives exclues)." : "Table officielle Vanguard.");
    }

    public static final class MaturityPages
    {
        private final LocalDate reference;
        private final Map<String, BigDecimal> weights = new LinkedHashMap<>();
        private final Set<String> cursors = new HashSet<>();
        private int expected = -1;
        private int received;
        private boolean complete;
        public MaturityPages(LocalDate reference) { this.reference = reference; }
        public String accept(String json) throws IOException
        {
            if (complete) throw new IOException("Pagination déjà terminée.");
            var document = JsonParser.parseString(json).getAsJsonObject();
            if (document.has("errors")) throw new IOException("Erreur de l'API Vanguard.");
            var data = document.getAsJsonObject("data").getAsJsonArray("borHoldings");
            if (data.size() != 1) throw new IOException("Réponse Vanguard ambiguë.");
            var holdings = data.get(0).getAsJsonObject().getAsJsonObject("holdings");
            int count = holdings.get("totalHoldings").getAsInt();
            if (count <= 0 || count > 100000 || (expected != -1 && count != expected)) throw new IOException("Nombre de lignes Vanguard incohérent.");
            expected = count;
            var items = holdings.getAsJsonArray("items");
            if (items.isEmpty()) throw new IOException("Page Vanguard vide.");
            for (var item : items)
            {
                var row = item.getAsJsonObject();
                var weight = row.get("marketValuePercentage").getAsBigDecimal();
                var value = row.get("finalMaturity");
                String maturity = value == null || value.isJsonNull() ? "" : value.getAsString().strip();
                String bucket = maturity.isEmpty() ? "Cash" : maturityBucket(LocalDate.parse(maturity.substring(0, 10)), reference, false);
                weights.merge(bucket, weight, BigDecimal::add);
            }
            received += items.size();
            if (received > expected) throw new IOException("Trop de lignes Vanguard.");
            var value = holdings.get("lastItemKey"); String cursor = value == null || value.isJsonNull() ? "" : value.getAsString();
            if (!cursor.isEmpty() && !cursors.add(cursor)) throw new IOException("Pagination Vanguard bloquée.");
            if (cursor.isEmpty())
            {
                if (received != expected) throw new IOException("Échéances Vanguard incomplètes : " + received + " / " + expected);
                complete = true;
            }
            return cursor;
        }
        public Slice result()
        {
            if (!complete) throw new IllegalStateException("Pagination incomplète.");
            return new Slice(percentages(weights, "Cash", true), null, VANGUARD,
                            "Détail officiel : " + received + " lignes ; date de composition non fournie par l'API. Tranches calculées au " + reference
                                            + ". Expositions positives normalisées à 100 %, couvertures négatives exclues.");
        }
    }

    private Slice fetchMaturities(LocalDate reference) throws IOException, InterruptedException
    {
        var pages = new MaturityPages(reference); String cursor = "";
        String query = "query FundsHoldingsQuery($portIds: [String!], $securityTypes: [String!], $lastItemKey: String) { borHoldings(portIds: $portIds) { holdings(limit: 1500, securityTypes: $securityTypes, lastItemKey: $lastItemKey) { items { marketValuePercentage finalMaturity } totalHoldings lastItemKey } } }";
        do
        {
            var variables = new JsonObject(); var ids = new com.google.gson.JsonArray(); ids.add("9443"); variables.add("portIds", ids);
            variables.add("securityTypes", com.google.gson.JsonNull.INSTANCE);
            if (cursor.isEmpty()) variables.add("lastItemKey", com.google.gson.JsonNull.INSTANCE); else variables.addProperty("lastItemKey", cursor);
            var body = new JsonObject(); body.addProperty("query", query); body.add("variables", variables);
            cursor = pages.accept(request(HttpRequest.newBuilder(URI.create(API)).header("Content-Type", "application/json")
                            .header("Accept", "application/json").header("Origin", "https://www.vanguard.co.uk").header("Referer", VANGUARD)
                            .header("X-Consumer-ID", "uk2").POST(HttpRequest.BodyPublishers.ofString(body.toString()))));
        } while (!cursor.isEmpty());
        return pages.result();
    }
}
