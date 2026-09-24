package name.abuchen.portfolio.updates.equity;

import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jsoup.Jsoup;

import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.pdfbox3.PDFBox3Adapter;
import name.abuchen.portfolio.updates.equity.EquityComposition.Family;
import name.abuchen.portfolio.updates.equity.EquityComposition.Item;
import name.abuchen.portfolio.updates.equity.EquityComposition.Outcome;
import name.abuchen.portfolio.updates.equity.EquityComposition.Slice;

/** Fresh source requests per run; no persistent cache and no quote-provider changes. */
public final class EquitySources
{
    private static final JsonObject PROFILES;
    static
    {
        try (var reader = new InputStreamReader(EquitySources.class.getResourceAsStream("profiles.json"), StandardCharsets.UTF_8))
        {
            PROFILES = JsonParser.parseReader(reader).getAsJsonObject();
        }
        catch (IOException e) { throw new ExceptionInInitializerError(e); }
    }
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
                    .followRedirects(HttpClient.Redirect.NORMAL).build();
    private final Map<String, String> pages = new ConcurrentHashMap<>();

    public static String alias(String group, String key)
    {
        var value = PROFILES.getAsJsonObject(group).get(key);
        return value == null ? key : value.getAsString();
    }

    public static String residual(String isin)
    {
        var value = PROFILES.getAsJsonObject("REGION_RESIDUAL_CLASSIFICATIONS").get(isin == null ? "" : isin);
        return value == null ? "Other" : value.getAsJsonArray().get(0).getAsString();
    }

    public static boolean topLevelResidual(String isin)
    {
        return "IE00BK5BQW10".equals(isin);
    }

    private byte[] bytes(String url) throws IOException, InterruptedException
    {
        var response = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(35))
                        .header("User-Agent", "Mozilla/5.0").GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200)
            throw new IOException("Source indisponible (HTTP " + response.statusCode() + ") : " + url);
        return response.body();
    }

    private String page(String url) throws IOException, InterruptedException
    {
        if (!pages.containsKey(url)) pages.put(url, new String(bytes(url), StandardCharsets.UTF_8));
        return pages.get(url);
    }

    public List<Outcome> fetch(Security security, BooleanSupplier cancelled) throws InterruptedException
    {
        return fetch(security, cancelled, family -> { });
    }

    public List<Outcome> fetch(Security security, BooleanSupplier cancelled, Consumer<Family> progress) throws InterruptedException
    {
        var result = new ArrayList<Outcome>();
        for (var family : Family.values())
        {
            if (cancelled.getAsBoolean()) throw new InterruptedException();
            progress.accept(family);
            try
            {
                var slice = EquityTask.run(() -> fetch(security, family), cancelled, Duration.ofSeconds(45));
                result.add(new Outcome(security.getUUID(), family, slice, null));
            }
            catch (IOException | RuntimeException e)
            {
                result.add(new Outcome(security.getUUID(), family, null,
                                e.getMessage() == null ? e.toString() : e.getMessage()));
            }
        }
        return List.copyOf(result);
    }

    private Slice fetch(Security security, Family family) throws IOException, InterruptedException
    {
        String isin = security.getIsin();
        if (isin == null || isin.isBlank()) throw new IOException("ISIN absent : titre non pris en charge.");
        var direct = PROFILES.getAsJsonObject("DIRECT_STOCK_COUNTRIES");
        if (direct.has(isin))
        {
            String label = family == Family.HOLDINGS ? security.getName()
                            : alias(family == Family.REGIONS ? "DIRECT_STOCK_COUNTRIES" : "DIRECT_STOCK_SECTORS", isin);
            return new Slice(List.of(new Item(label, new BigDecimal("100"))), null,
                            "Profil titre vif du script : pays d'incorporation / secteur GICS", true);
        }
        String group = switch (family)
        {
            case REGIONS -> "SPECIAL_COUNTRY_ALLOCATIONS";
            case SECTORS -> "SPECIAL_SECTOR_ALLOCATIONS";
            case HOLDINGS -> "SPECIAL_TRANSPARENCY_ALLOCATIONS";
        };
        if (PROFILES.getAsJsonObject(group).has(isin))
        {
            var data = PROFILES.getAsJsonObject(group).getAsJsonArray(isin);
            var items = new ArrayList<Item>();
            for (var element : data.get(0).getAsJsonArray())
            {
                var row = element.getAsJsonArray();
                items.add(new Item(row.get(0).getAsString(), row.get(1).getAsBigDecimal()));
            }
            return new Slice(items, LocalDate.parse(data.get(1).getAsString()), data.get(2).getAsString(), true);
        }
        if (family == Family.HOLDINGS && PROFILES.getAsJsonObject("SYNTHETIC_ETF_INDEX_SOURCES").has(isin))
        {
            var source = PROFILES.getAsJsonObject("SYNTHETIC_ETF_INDEX_SOURCES").getAsJsonObject(isin);
            String url = source.get("url").getAsString();
            var file = Files.createTempFile("equity-index-", ".pdf");
            try
            {
                Files.write(file, bytes(url));
                return parseMsci(new PDFBox3Adapter().convertToText(file.toFile()), source.get("parser").getAsString(), url);
            }
            finally { Files.deleteIfExists(file); }
        }
        if (family == Family.REGIONS && "IE00BK5BR733".equals(isin))
        {
            String url = "https://www.vanguard.co.uk/professional/product/etf/equity/9507/ftse-emerging-markets-ucits";
            return parseVanguard(page(url), url);
        }
        if (family != Family.HOLDINGS && PROFILES.getAsJsonObject("DIVVY_COUNTRY_PAGE_URLS").has(isin))
        {
            String url = alias("DIVVY_COUNTRY_PAGE_URLS", isin);
            return parseDivvy(page(url), family, url);
        }
        String url = compositionUrl(isin);
        String html = page(url);
        // Do not accept a redirected composition belonging to another share class.
        if (!Jsoup.parse(html).text().contains(isin))
            throw new IOException("ISIN non confirmé sur la page de composition.");
        return parseBoursorama(html, family, url);
    }

    private String compositionUrl(String isin) throws IOException, InterruptedException
    {
        var configured = PROFILES.getAsJsonObject("BOURSORAMA_COMPOSITION_URLS").get(isin);
        if (configured != null) return configured.getAsString();
        String url = "https://www.boursorama.com/recherche/?query=" + URLEncoder.encode(isin, StandardCharsets.UTF_8);
        var matcher = Pattern.compile("href=\"(/bourse/(?:opcvm|trackers)/cours/composition/[^\"/]+/)\"").matcher(page(url));
        if (!matcher.find()) throw new IOException("Page de composition introuvable pour " + isin);
        return "https://www.boursorama.com" + matcher.group(1);
    }

    public static Slice parseBoursorama(String html, Family family, String url) throws IOException
    {
        String text = Jsoup.parse(html).text();
        var date = Pattern.compile("Date du portefeuille\\s*:\\s*(\\d{2}/\\d{2}/\\d{4})").matcher(text);
        if (!date.find()) throw new IOException("Date de composition absente.");
        var items = new ArrayList<Item>();
        if (family != Family.HOLDINGS)
        {
            String id = family == Family.REGIONS ? "regional" : "sector";
            var chart = Pattern.compile("\"id\":\"" + id + "\".*?\"amChartData\":(\\[[^\\]]*\\])", Pattern.DOTALL).matcher(html);
            if (!chart.find()) throw new IOException("Répartition " + id + " absente.");
            for (var element : JsonParser.parseString(chart.group(1)).getAsJsonArray())
            {
                var row = element.getAsJsonObject();
                items.add(new Item(alias(family == Family.REGIONS ? "COUNTRY_NAMES" : "SECTOR_NAMES", row.get("name").getAsString()), row.get("value").getAsBigDecimal()));
            }
        }
        else
        {
            var rows = Pattern.compile("<tr>.*?c-table-gauge__cell--header\">\\s*([^<]+?)\\s*</td>.*?data-gauge-current-step=\"([\\d.]+)\"", Pattern.DOTALL).matcher(html);
            while (rows.find())
            {
                String name = Jsoup.parse(rows.group(1)).text();
                if (name.toUpperCase(Locale.ROOT).matches(".*\\b(?:TRS|TOTAL RETURN SWAP|SWAP)\\b.*"))
                    throw new IOException("ETF synthétique : source d'indice requise, portefeuille de substitution non utilisé.");
                items.add(new Item(name, new BigDecimal(rows.group(2))));
            }
        }
        return new Slice(items, LocalDate.parse(date.group(1), DateTimeFormatter.ofPattern("dd/MM/uuuu")), url, false);
    }

    /** Remove only recognized labels from the adjacent INDEX CHARACTERISTICS column. */
    public static String msciHoldingName(String name)
    {
        return name.strip().replaceFirst("^(?:Constituents|Weight \\(\\s*%\\s*\\)|Mkt Cap \\(\\s*USD (?:Millions|Billions)\\)|"
                        + "(?:Number of|Index|Largest|Smallest|Average|Median)(?:\\s+[\\d,]+(?:\\.\\d+)?){1,2})\\s+", "");
    }

    public static Slice parseMsci(String text, String parser, String url) throws IOException
    {
        if (!List.of("msci_world", "msci_em_ex_egypt").contains(parser))
            throw new IOException("Format MSCI inconnu.");
        int start = text.indexOf("TOP 10 CONSTITUENTS");
        if (start < 0) throw new IOException("Principales lignes MSCI absentes.");
        String section = text.substring(start).split("FACTORS - KEY EXPOSURES|SECTOR WEIGHTS|COUNTRY WEIGHTS", 2)[0];
        String numeric = "msci_world".equals(parser) ? "[\\d,]+\\.\\d+\\s+(\\d+\\.\\d+)" : "[A-Z]{2}\\s+(\\d+\\.\\d+)\\s+\\d+\\.\\d+";
        var pattern = Pattern.compile("^(.+?)\\s+" + numeric + "\\s+(?:Info Tech|Cons Discr|Comm Srvcs|Financials|Health Care|Industrials|Consumer Staples|Energy|Materials|Utilities|Real Estate)$");
        var items = new ArrayList<Item>();
        for (String line : section.split("\\R"))
        {
            var match = pattern.matcher(line.strip());
            if (match.matches())
            {
                String name = msciHoldingName(match.group(1));
                // MSCI names in these tables are uppercase. Fail closed on a changed layout,
                // rather than create categories containing statistics or adjacent headings.
                if (!name.matches("[A-Z0-9][A-Z0-9 &'()./,-]*") || name.matches(".*[0-9][.,][0-9].*"))
                    throw new IOException("Nom de constituant MSCI ambigu : " + name);
                items.add(new Item(name, new BigDecimal(match.group(2))));
            }
        }
        if (items.size() != 10 || items.stream().map(Item::name).distinct().count() != 10) throw new IOException("Les dix lignes MSCI ne sont pas lisibles.");
        var total = Pattern.compile("(?m)^Total\\s+([\\d,]+\\.\\d+)\\s+([\\d,]+\\.\\d+)\\s*$").matcher(section);
        if (!total.find()) throw new IOException("Total MSCI absent.");
        var expected = new BigDecimal(total.group("msci_world".equals(parser) ? 2 : 1).replace(",", ""));
        var actual = items.stream().map(Item::percent).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (expected.subtract(actual).abs().compareTo(new BigDecimal("0.05")) > 0)
            throw new IOException("Poids MSCI incohérents avec le total publié.");
        var date = Pattern.compile("([A-Z]{3}\\s+\\d{1,2},\\s+\\d{4}) Index Factsheet").matcher(text.replaceAll("\\s+", " "));
        if (!date.find()) throw new IOException("Date MSCI absente.");
        var format = new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("MMM d, uuuu").toFormatter(Locale.ENGLISH);
        return new Slice(items, LocalDate.parse(date.group(1), format), url + " (indice de référence)", false);
    }

    public static Slice parseVanguard(String html, String url) throws IOException
    {
        String text = Jsoup.parse(html).text();
        String section = java.util.Arrays.stream(text.split("(?i)Market allocation"))
                        .filter(s -> s.contains("Country Region Fund Benchmark") && s.contains("Taiwan")).findFirst()
                        .orElseThrow(() -> new IOException("Table pays Vanguard absente."));
        var date = Pattern.compile("As at\\s+(\\d{1,2}\\s+[A-Za-z]+\\s+\\d{4})", Pattern.CASE_INSENSITIVE).matcher(section);
        if (!date.find()) throw new IOException("Date Vanguard absente.");
        var items = new ArrayList<Item>();
        for (var entry : PROFILES.getAsJsonObject("VANGUARD_EMERGING_MARKETS_COUNTRIES").entrySet())
        {
            var row = Pattern.compile("\\b" + Pattern.quote(entry.getKey()) + "\\s+(?:Emerging Markets|Europe)\\s+(\\d+(?:\\.\\d+)?)%").matcher(section);
            if (!row.find()) throw new IOException("Pays Vanguard absent : " + entry.getKey());
            items.add(new Item(entry.getValue().getAsString(), new BigDecimal(row.group(1))));
        }
        String dateText = date.group(1);
        String monthPattern = dateText.split("\\s+")[1].length() == 3 ? "MMM" : "MMMM";
        return new Slice(items, LocalDate.parse(dateText, new DateTimeFormatterBuilder().parseCaseInsensitive()
                        .appendPattern("d " + monthPattern + " uuuu").toFormatter(Locale.ENGLISH)), url, false);
    }

    public static Slice parseDivvy(String html, Family family, String url) throws IOException
    {
        String decoded = html.replace("\\\"", "\"");
        var match = Pattern.compile("\"" + (family == Family.REGIONS ? "countryWeightings" : "sectorWeightings") + "\":(\\[.*?\\])").matcher(decoded);
        if (!match.find()) throw new IOException("Composition DivvyDiary absente.");
        var items = new ArrayList<Item>();
        String group = family == Family.REGIONS ? "DIVVY_COUNTRY_CODES" : "DIVVY_SECTOR_NAMES";
        for (var element : JsonParser.parseString(match.group(1)).getAsJsonArray())
        {
            var row = element.getAsJsonObject();
            String key = row.get(family == Family.REGIONS ? "country" : "sector").getAsString();
            if (!PROFILES.getAsJsonObject(group).has(key)) throw new IOException("Libellé DivvyDiary inconnu : " + key);
            items.add(new Item(alias(group, key), row.get("weight").getAsBigDecimal().multiply(new BigDecimal("100"))));
        }
        // Keep publication date unknown rather than relabelling retrieval time as a portfolio date.
        return new Slice(items, null, url + " (consulté le " + LocalDate.now() + "; date de composition non publiée)", false);
    }
}
