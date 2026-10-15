package name.abuchen.portfolio.factors;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import com.google.gson.Gson;

import name.abuchen.portfolio.pdfbox3.PDFBox3Adapter;

/** Public sources only; no portfolio content is sent to the fund provider. */
@SuppressWarnings("nls")
public final class DbiData
{
    public static final String DBMF_URL = "https://www.imgp.com/us/fund/US53700T8273-imgp-dbi-managed-futures-strategy-etf/";
    public static final String UCITS_URL = "https://www.imgp.com/fund/LU3359622902-imgp-dbi-managed-futures-fund/";
    public static final String UCITS_FACTSHEET_URL = "https://www.imgp.com/uploads/factsheets/LU3359622902_FACTSHEETS_EN.pdf";
    public static final String VA_URL = "https://www.imgp.com/wp-content/uploads/2026/09/DBMF-August-2026-Video-Update-Deck.pdf";

    public record Holding(String name, String ticker, String quantity, double notional, Factor factor)
    {
        public Holding
        {
            Objects.requireNonNull(name);
            Objects.requireNonNull(ticker);
            Objects.requireNonNull(quantity);
            Objects.requireNonNull(factor);
            if (!Double.isFinite(notional))
                throw new IllegalArgumentException("Notionnel invalide");
        }
    }

    public record Snapshot(String isin, String date, String source, double nav, List<Holding> holdings,
                    String vaDate, String vaSource, Map<Factor, Double> va)
    {
        public Snapshot
        {
            if (!FactorMap.DBMF.equals(isin) || !DBMF_URL.equals(source))
                throw new IllegalArgumentException("Source DBMF non reconnue");
            LocalDate.parse(date);
            if (!Double.isFinite(nav) || nav <= 0)
                throw new IllegalArgumentException("Actif net invalide");
            holdings = List.copyOf(holdings);
            va = Map.copyOf(va);
            if (!va.isEmpty())
            {
                LocalDate.parse(vaDate);
                Objects.requireNonNull(vaSource);
                va.values().forEach(v -> {
                    if (!Double.isFinite(v))
                        throw new IllegalArgumentException("VA invalide");
                });
            }
        }

        public Double exposure(Factor factor, boolean adjusted, LocalDate asof)
        {
            // Published VA coefficients use different per-contract volatility scales;
            // they cannot be added to obtain a synthetic USD/EUR VA coefficient.
            if (factor == Factor.USD_EUR || factor == Factor.JPY_EUR)
            {
                if (adjusted)
                    return null;
                Double yen = exposure(Factor.JPY_USD, false, asof);
                if (factor == Factor.JPY_EUR)
                    return yen;
                Double euro = exposure(Factor.EUR_USD, false, asof);
                return euro == null || yen == null ? null : -euro - yen;
            }
            if (adjusted)
                return vaDate == null || LocalDate.parse(vaDate).isAfter(asof) ? null : va.get(factor);
            if (LocalDate.parse(date).isAfter(asof))
                return null;
            var rows = holdings.stream().filter(h -> h.factor() == factor).toList();
            if (!rows.isEmpty())
                return rows.stream().mapToDouble(Holding::notional).sum() / nav;
            return null;
        }
    }

    public record Ucits(String date, String source, String text)
    {
        public Ucits
        {
            LocalDate.parse(date);
            if (source == null || !source.startsWith("https://extranet.imgp.com/api/attachments/preview/Portfolio_Composition-"))
                throw new IllegalArgumentException("Source UCITS invalide");
            if (text == null || text.isBlank())
                throw new IllegalArgumentException("Composition vide");
        }

        public List<UcitsHolding> positions()
        {
            var positions = new ArrayList<UcitsHolding>();
            var future = Pattern.compile("([A-Z]{1,4}[HMUZ]\\d)\\s*(?:Curncy|Index|Comdty)\\s*(.*?)\\s+(-?[\\d,]+)\\s*$");
            var security = Pattern.compile("([A-Z]{2}[A-Z0-9]{10})\\s*(.*?)\\s+([\\d,]+)\\s+(\\d+\\.\\d+)\\s*$");
            for (String line : text.lines().toList())
            {
                var f = future.matcher(line);
                var s = security.matcher(line);
                if (f.find())
                    positions.add(new UcitsHolding("Future", f.group(1), f.group(2).strip(), f.group(3), null));
                else if (s.find())
                    positions.add(new UcitsHolding(s.group(1).startsWith("XS") ? "Produit structuré" : "Titre",
                                    s.group(1), s.group(2).strip(), s.group(3), Double.parseDouble(s.group(4)) / 100));
            }
            return List.copyOf(positions);
        }
    }

    public record UcitsHolding(String group, String identifier, String name, String quantity, Double navWeight)
    {
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(12))
                    .followRedirects(HttpClient.Redirect.NORMAL).build();

    public static Snapshot bundled()
    {
        try (var reader = new InputStreamReader(DbiData.class.getResourceAsStream("dbmf-reference.json"), StandardCharsets.UTF_8))
        {
            return new Gson().fromJson(reader, Snapshot.class);
        }
        catch (IOException e)
        {
            throw new IllegalStateException(e);
        }
    }

    public static Ucits bundledUcits()
    {
        try (var reader = new InputStreamReader(DbiData.class.getResourceAsStream("ucits-reference.json"), StandardCharsets.UTF_8))
        {
            return new Gson().fromJson(reader, Ucits.class);
        }
        catch (IOException e)
        {
            throw new IllegalStateException(e);
        }
    }

    public Snapshot fetch(Snapshot previous, LocalDate today) throws IOException, InterruptedException
    {
        var current = parse(new String(download(DBMF_URL, 12_000_000), StandardCharsets.UTF_8), today);
        if (previous != null && LocalDate.parse(current.date()).isBefore(LocalDate.parse(previous.date())))
            throw new IOException("La source a renvoyé une publication plus ancienne ; dernier instantané conservé.");
        return new Snapshot(current.isin(), current.date(), current.source(), current.nav(), current.holdings(),
                        previous == null ? null : previous.vaDate(), previous == null ? null : previous.vaSource(),
                        previous == null ? Map.of() : previous.va());
    }

    public Ucits fetchUcits(Ucits previous, LocalDate today) throws IOException, InterruptedException
    {
        var html = Jsoup.parse(new String(download(UCITS_URL, 10_000_000), StandardCharsets.UTF_8));
        String prefix = "https://extranet.imgp.com/api/attachments/preview/Portfolio_Composition-";
        String url = html.select("a[href]").stream().map(a -> a.attr("href"))
                        .filter(h -> h.startsWith(prefix) && h.endsWith("-EN-00-" + FactorMap.UCITS_HEDGED + ".pdf"))
                        .sorted().reduce((a, b) -> b).orElseThrow(() -> new IOException("Composition UCITS absente"));
        LocalDate date = LocalDate.parse(url.substring(prefix.length(), prefix.length() + 10));
        if (date.isAfter(today))
            throw new IOException("Publication UCITS future refusée");
        if (previous != null && !date.isAfter(LocalDate.parse(previous.date())))
            return previous;
        var file = Files.createTempFile("pp-dbi-composition-", ".pdf");
        try
        {
            Files.write(file, download(url, 8_000_000));
            String content = new PDFBox3Adapter().convertToText(file.toFile());
            if (!content.contains("DBi Managed Futures Fund") || !content.contains("Portfolio Holdings"))
                throw new IOException("Document UCITS non reconnu");
            // Check the printed observation date as well as the filename.
            var printed = Pattern.compile("(?i)Portfolio Holdings as of\\s+(\\d{1,2}\\s+[A-Za-z]+,?\\s+\\d{4})").matcher(content);
            if (!printed.find() || !LocalDate.parse(printed.group(1).replace(",", "").replaceAll("\\s+", " "),
                            DateTimeFormatter.ofPattern("d MMMM uuuu", Locale.ENGLISH)).equals(date))
                throw new IOException("Date interne de composition non confirmée");
            int end = content.indexOf("Note:");
            return new Ucits(date.toString(), url, end > 0 ? content.substring(0, end).strip() : content.strip());
        }
        finally
        {
            Files.deleteIfExists(file);
        }
    }

    public RateSettings.Mapping fetchRates(RateSettings.Mapping previous, LocalDate today) throws IOException, InterruptedException
    {
        var file = Files.createTempFile("pp-dbi-duration-", ".pdf");
        try
        {
            Files.write(file, download(UCITS_FACTSHEET_URL, 8_000_000));
            var current = DbiRateData.parse(new PDFBox3Adapter().convertToText(file.toFile()), today);
            if (previous != null && LocalDate.parse(current.date()).isBefore(LocalDate.parse(previous.date())))
                throw new IOException("Reporting plus ancien ; duration précédente conservée.");
            return current;
        }
        finally
        {
            Files.deleteIfExists(file);
        }
    }

    private byte[] download(String url, int maxBytes) throws IOException, InterruptedException
    {
        var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(25))
                        .header("User-Agent", "Mozilla/5.0 PortfolioPerformance").GET().build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (var stream = response.body())
        {
            if (response.statusCode() != 200)
                throw new IOException("iMGP : HTTP " + response.statusCode());
            byte[] bytes = stream.readNBytes(maxBytes + 1);
            if (bytes.length > maxBytes)
                throw new IOException("Publication trop volumineuse");
            return bytes;
        }
    }

    public static Snapshot parse(String html, LocalDate today)
    {
        var document = Jsoup.parse(html);
        if (!html.contains(FactorMap.DBMF))
            throw new IllegalArgumentException("ISIN DBMF absent");
        var table = document.selectFirst("table#breakdown-holdings-us");
        if (table == null)
            throw new IllegalArgumentException("Table officielle DBMF absente");
        var holdings = new ArrayList<Holding>();
        LocalDate date = null;
        Double nav = null;
        var identifiers = new java.util.HashSet<String>();
        for (var row : table.select("tbody tr"))
        {
            var cells = row.select("td");
            if (cells.size() != 7)
                throw new IllegalArgumentException("Colonnes DBMF inattendues");
            var rowDate = LocalDate.parse(cells.get(0).text(), DateTimeFormatter.ofPattern("MM/dd/uuuu"));
            if (rowDate.isAfter(today) || date != null && !date.equals(rowDate))
                throw new IllegalArgumentException("Dates DBMF futures ou mélangées");
            date = rowDate;
            String name = cells.get(1).text();
            double value = number(cells.get(5).text());
            if (name.equals("TOTAL NET ASSETS"))
            {
                if (nav != null)
                    throw new IllegalArgumentException("Actif net dupliqué");
                nav = value;
                continue;
            }
            Factor factor = factor(name);
            if (factor == null)
            {
                if (!name.equals("TREASURY BILL") && !name.toUpperCase(Locale.ROOT).contains("CASH"))
                    throw new IllegalArgumentException("Instrument DBMF non reconnu : " + name);
                continue;
            }
            String id = cells.get(2).text();
            if (!identifiers.add(id))
                throw new IllegalArgumentException("Instrument DBMF dupliqué : " + id);
            holdings.add(new Holding(name, cells.get(3).text(), cells.get(4).text(), value, factor));
        }
        if (date == null || nav == null || nav <= 0 || holdings.isEmpty())
            throw new IllegalArgumentException("Instantané DBMF incomplet");
        return new Snapshot(FactorMap.DBMF, date.toString(), DBMF_URL, nav, holdings, null, null, Map.of());
    }

    private static double number(String text)
    {
        double value = Double.parseDouble(text.replace("$", "").replace(",", "").replaceAll("\\s+", "")
                        .replace("(", "-").replace(")", ""));
        if (!Double.isFinite(value))
            throw new IllegalArgumentException("Valeur DBMF invalide");
        return value;
    }

    private static Factor factor(String name)
    {
        String value = name.toUpperCase(Locale.ROOT);
        if (value.startsWith("US 2YR NOTE")) return Factor.US_2Y;
        if (value.startsWith("US 10YR NOTE")) return Factor.US_10Y;
        if (value.startsWith("US LONG BOND")) return Factor.US_LONG;
        if (value.startsWith("EURO FX CURR")) return Factor.EUR_USD;
        if (value.startsWith("JPN YEN CURR")) return Factor.JPY_USD;
        if (value.startsWith("MSCI EAFE")) return Factor.EAFE;
        if (value.startsWith("MSCI EMGMKT")) return Factor.EMERGING;
        if (value.startsWith("GOLD 100 OZ")) return Factor.GOLD;
        if (value.startsWith("WTI CRUDE")) return Factor.OIL;
        if (value.startsWith("S+P500 EMINI")) return Factor.US_EQUITY;
        return null;
    }
}
