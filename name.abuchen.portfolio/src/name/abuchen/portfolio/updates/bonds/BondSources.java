package name.abuchen.portfolio.updates.bonds;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.apache.commons.csv.CSVFormat;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.util.Timeout;
import org.jsoup.Jsoup;

import com.google.gson.JsonParser;

/** Sources are fetched on explicit demand. A fresh instance caches shared downloads for one preview only. */
public final class BondSources
{
    public static final String BUNDESBANK = "https://www.bundesbank.de/dynamic/action/de/service/bundeswertpapiere/kurse-und-renditen/810554/kurse-und-renditen-boersennotierter-bundeswertpapiere";
    private static final Set<String> HOSTS = Set.of("www.bundesbank.de", "api.statistiken.bundesbank.de", "www.aft.gouv.fr",
                    "grafici.borsaitaliana.it", "www.borsaitaliana.it");
    private final Map<String, byte[]> cache = new HashMap<>();
    private final BooleanSupplier cancelled;
    private final LocalDate today;

    public BondSources(BooleanSupplier cancelled, LocalDate today)
    {
        this.cancelled = cancelled;
        this.today = today;
    }

    /** Shared public AFT transport; restricted to the official HTTPS host. */
    public byte[] fetchAft(String url) throws IOException, InterruptedException
    {
        var uri = URI.create(url);
        if (!"https".equals(uri.getScheme()) || !"www.aft.gouv.fr".equals(uri.getHost())
                        || uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443))
            throw new IOException("Adresse AFT invalide.");
        return get(url);
    }

    public List<BondQuote> fetch(BondSpec bond) throws IOException, InterruptedException
    {
        checkCancelled();
        try
        {
            var result = bond.indexed() ? indexed(bond) : german(bond);
            if (result.isEmpty())
                throw new IOException("Aucun cours reçu.");
            if (result.stream().anyMatch(q -> q.date().isAfter(today)))
                throw new IOException("La source contient des cours futurs.");
            checkCancelled();
            return List.copyOf(result);
        }
        catch (RuntimeException e)
        {
            throw new IOException(bond.isin() + " : format de données inattendu.", e);
        }
    }

    private void checkCancelled() throws InterruptedException
    {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
            throw new InterruptedException();
    }

    private byte[] get(String url) throws IOException, InterruptedException
    {
        checkCancelled();
        var previous = cache.get(url);
        if (previous != null)
            return previous;
        for (int attempt = 0; ; attempt++)
        {
            try
            {
                var data = request(url, null, url);
                cache.put(url, data);
                return data;
            }
            catch (SourceException e)
            {
                if (attempt >= 1 || e.status < 500)
                    throw e;
                checkCancelled();
            }
            catch (java.net.SocketTimeoutException e)
            {
                if (attempt >= 1)
                    throw e;
                checkCancelled();
            }
        }
    }

    private String text(String url) throws IOException, InterruptedException
    {
        return new String(get(url), StandardCharsets.UTF_8);
    }

    private byte[] request(String url, String token, String referer) throws IOException, InterruptedException
    {
        // The public AFT site rejects some JVM TLS clients. macOS ships curl; use its system TLS
        // transport for this host only. No shell, credentials, installed script or office suite.
        if ("www.aft.gouv.fr".equals(URI.create(url).getHost()) && Files.isExecutable(Path.of("/usr/bin/curl")))
            return aftWithSystemTransport(url);
        var manager = new PoolingHttpClientConnectionManager();
        manager.setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(Timeout.ofSeconds(15))
                        .setSocketTimeout(Timeout.ofSeconds(40)).build());
        try (var http = HttpClients.custom().setConnectionManager(manager).useSystemProperties().disableRedirectHandling().disableContentCompression()
                        .setDefaultRequestConfig(RequestConfig.custom().setResponseTimeout(Timeout.ofSeconds(40)).build()).build())
        {
            var uri = URI.create(url);
            for (int redirects = 0; redirects < 4; redirects++)
            {
                checkCancelled();
                if (!"https".equals(uri.getScheme()) || !HOSTS.contains(uri.getHost()) || uri.getUserInfo() != null
                                || (token != null && !"grafici.borsaitaliana.it".equals(uri.getHost())))
                    throw new IOException("Destination de source non autorisée.");
                var request = new HttpGet(uri);
                request.setHeader("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140 Safari/537.36");
                request.setHeader("Accept", "www.aft.gouv.fr".equals(uri.getHost())
                                ? "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
                                : token != null ? "application/json" : "*/*");
                request.setHeader("Accept-Language", "fr-FR,fr;q=0.9,en;q=0.8");
                request.setHeader("Referer", "www.aft.gouv.fr".equals(uri.getHost()) ? "https://www.aft.gouv.fr/" : referer);
                if (token != null)
                    request.setHeader("Authorization", "Bearer " + token);
                String host = uri.getHost();
                var result = http.execute(request, response -> {
                    int status = response.getCode();
                    if (status >= 300 && status < 400)
                    {
                        var location = response.getFirstHeader("Location");
                        if (location == null)
                            throw new IOException("Redirection sans destination.");
                        return new Response(location.getValue(), null);
                    }
                    if (status != 200)
                        throw new SourceException(status, host);
                    if (response.getEntity() == null)
                        throw new IOException("Source sans contenu.");
                    try (var stream = response.getEntity().getContent())
                    {
                        var bytes = stream.readNBytes(20_000_001);
                        if (bytes.length > 20_000_000)
                            throw new IOException("Réponse trop volumineuse.");
                        return new Response(null, bytes);
                    }
                });
                if (result.redirect() != null)
                {
                    uri = uri.resolve(result.redirect());
                    continue;
                }
                checkCancelled();
                return result.bytes();
            }
        }
        throw new IOException("Trop de redirections de la source.");
    }

    private byte[] aftWithSystemTransport(String url) throws IOException, InterruptedException
    {
        if (!"https".equals(URI.create(url).getScheme()) || URI.create(url).getUserInfo() != null)
            throw new IOException("Adresse AFT invalide.");
        var file = Files.createTempFile("portfolio-aft-", ".download");
        Process process = null;
        try
        {
            process = new ProcessBuilder("/usr/bin/curl", "--fail", "--silent", "--http1.1", "--proto", "=https",
                            "--connect-timeout", "15", "--max-time", "40", "--max-filesize", "20000000",
                            "--user-agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140 Safari/537.36",
                            "--header", "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                            "--header", "Accept-Language: fr-FR,fr;q=0.9,en;q=0.8",
                            "--referer", "https://www.aft.gouv.fr/", "--output", file.toString(), "--write-out", "%{http_code}", url)
                            .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
            while (!process.waitFor(250, TimeUnit.MILLISECONDS))
            {
                checkCancelled();
                if (System.nanoTime() > deadline)
                    throw new IOException("Le téléchargement AFT a dépassé le délai prévu.");
            }
            checkCancelled();
            String status = new String(process.getInputStream().readNBytes(16), StandardCharsets.UTF_8);
            if (process.exitValue() == 28)
                throw new java.net.SocketTimeoutException("Délai de téléchargement AFT dépassé.");
            if (process.exitValue() != 0 || !"200".equals(status))
            {
                if (status.matches("[1-5][0-9]{2}"))
                    throw new SourceException(Integer.parseInt(status), "www.aft.gouv.fr");
                throw new IOException("Téléchargement AFT impossible. Réessayez ultérieurement.");
            }
            if (Files.size(file) > 20_000_000)
                throw new IOException("Fichier AFT trop volumineux.");
            return Files.readAllBytes(file);
        }
        finally
        {
            if (process != null && process.isAlive())
                process.destroyForcibly().waitFor();
            Files.deleteIfExists(file);
        }
    }

    private record Response(String redirect, byte[] bytes)
    {
    }

    private static final class SourceException extends IOException
    {
        private static final long serialVersionUID = 1L;
        private final int status;

        private SourceException(int status, String host)
        {
            super(host + " : HTTP " + status);
            this.status = status;
        }
    }

    public static BigDecimal decimal(String value)
    {
        return new BigDecimal(value.strip().replace("\u00a0", "").replace(" ", "").replace(',', '.'));
    }

    public static NavigableMap<LocalDate, BigDecimal> csv(String data)
    {
        var result = new TreeMap<LocalDate, BigDecimal>();
        data = data.replace("\ufeff", "");
        char delimiter = data.lines().findFirst().orElse("").contains(";") ? ';' : ',';
        try (var parser = CSVFormat.DEFAULT.builder().setDelimiter(delimiter).get().parse(new StringReader(data)))
        {
            for (var row : parser)
            {
                if (row.size() < 2 || !row.get(0).matches("\\d{4}-\\d{2}-\\d{2}"))
                    continue;
                if (row.get(1).isBlank() || row.get(1).equals("."))
                    continue;
                putUnique(result, LocalDate.parse(row.get(0)), decimal(row.get(1)));
            }
        }
        catch (IOException e)
        {
            throw new IllegalArgumentException("CSV Bundesbank invalide.", e);
        }
        return result;
    }

    private static <T> void putUnique(Map<LocalDate, T> result, LocalDate date, T value)
    {
        var previous = result.putIfAbsent(date, value);
        if (previous != null && !previous.equals(value))
            throw new IllegalArgumentException("Deux valeurs contradictoires pour le " + date);
    }

    private List<BondQuote> german(BondSpec bond) throws IOException, InterruptedException
    {
        if (bond.category() != null)
        {
            String base = "https://api.statistiken.bundesbank.de/rest/data/BBSSY/D.%s.EUR." + bond.category() + "." + bond.isin() + ".A?format=csv";
            try
            {
                var clean = csv(text(base.formatted("KCP")));
                var dirty = csv(text(base.formatted("KDP")));
                var result = new ArrayList<BondQuote>();
                for (var entry : clean.entrySet())
                    if (!entry.getKey().isBefore(bond.issued()) && dirty.containsKey(entry.getKey()))
                        result.add(germanQuote(entry.getKey(), entry.getValue(), dirty.get(entry.getKey())));
                if (!result.isEmpty())
                    return result;
                throw new IOException("Historique Bundesbank vide ou sans dates communes pour " + bond.isin());
            }
            catch (SourceException e)
            {
                if (e.status != 404)
                    throw e;
                // New series can be absent from the API: use the official monthly workbooks.
            }
        }
        var links = new LinkedHashMap<String, YearMonth>();
        var first = YearMonth.from(bond.issued());
        boolean covered = false;
        var monthPattern = Pattern.compile("(20\\d{2})-(\\d{2})-excel-data\\.xlsx");
        for (int page = 0; page < 48; page++)
        {
            String url = page == 0 ? BUNDESBANK : "https://www.bundesbank.de/action/de/810554/bbksearch?pageNumString=" + page;
            var document = Jsoup.parse(text(url), url);
            YearMonth oldest = null;
            for (var link : document.select("a[href]"))
            {
                var href = link.absUrl("href");
                var match = monthPattern.matcher(href);
                if (!match.find())
                    continue;
                var month = YearMonth.of(Integer.parseInt(match.group(1)), Integer.parseInt(match.group(2)));
                if (oldest == null || month.isBefore(oldest))
                    oldest = month;
                if (!month.isBefore(first))
                    links.put(href, month);
            }
            if (oldest == null)
                throw new IOException("Archives mensuelles Bundesbank introuvables.");
            if (!oldest.isAfter(first))
            {
                covered = true;
                break;
            }
        }
        if (!covered)
            throw new IOException("Historique Bundesbank incomplet.");
        var result = new TreeMap<LocalDate, BondQuote>();
        for (var url : links.keySet())
            for (var quote : germanWorkbook(get(url), bond))
                putUnique(result, quote.date(), quote);
        return new ArrayList<>(result.values());
    }

    private static BondQuote germanQuote(LocalDate date, BigDecimal clean, BigDecimal dirty)
    {
        return new BondQuote(date, clean, dirty.subtract(clean), dirty, null, null, BUNDESBANK);
    }

    public static List<BondQuote> germanWorkbook(byte[] bytes, BondSpec bond) throws IOException
    {
        var result = new ArrayList<BondQuote>();
        var pattern = Pattern.compile("Bundeswertpapiere vom (\\d{2})\\.(\\d{2})\\.(\\d{4}|\\d{2})(?!\\d)");
        for (var sheet : BondWorkbook.read(bytes))
        {
            var match = pattern.matcher(String.join(" ", sheet.getOrDefault(1, Map.of()).values()));
            if (!match.find())
                continue;
            int year = Integer.parseInt(match.group(3));
            var date = LocalDate.of(year < 100 ? year + 2000 : year, Integer.parseInt(match.group(2)), Integer.parseInt(match.group(1)));
            if (date.isBefore(bond.issued()))
                continue;
            for (var row : sheet.values())
            {
                var a = row.getOrDefault("A", "").strip();
                boolean legacy = a.equals("DE000");
                String isin = legacy ? a + integerText(row.getOrDefault("B", "")) + integerText(row.getOrDefault("C", "")) : a;
                if (!isin.equals(bond.isin()))
                    continue;
                String clean = row.getOrDefault(legacy ? "I" : "G", ""), dirty = row.getOrDefault(legacy ? "K" : "I", "");
                if (!clean.isBlank() && !dirty.isBlank())
                    result.add(germanQuote(date, decimal(clean), decimal(dirty)));
            }
        }
        return result;
    }

    private static String integerText(String value)
    {
        return value.matches("[0-9]+\\.0+") ? value.substring(0, value.indexOf('.')) : value;
    }

    public static NavigableMap<LocalDate, BigDecimal> coefficients(byte[] bytes, LocalDate maturity) throws IOException
    {
        var result = new TreeMap<LocalDate, BigDecimal>();
        for (var sheet : BondWorkbook.read(bytes))
        {
            String column = null;
            for (var cell : sheet.getOrDefault(4, Map.of()).entrySet())
            {
                try
                {
                    if (BondWorkbook.date(cell.getValue()).equals(maturity))
                        column = cell.getKey();
                }
                catch (RuntimeException e)
                {
                    // AFT's descriptive header cells are not dates.
                }
            }
            if (column == null)
                continue;
            for (var row : sheet.values())
            {
                var dateText = row.getOrDefault("A", "");
                if (!dateText.matches("[0-9]+(?:\\.[0-9]+)?") || !row.containsKey(column) || row.get(column).isBlank())
                    continue;
                var date = BondWorkbook.date(dateText);
                if (date.isBefore(LocalDate.of(2000, 1, 1)))
                    continue;
                var coefficient = decimal(row.get(column));
                if (coefficient.signum() <= 0)
                    throw new IOException("Coefficient AFT non positif.");
                putUnique(result, date, coefficient);
            }
        }
        if (result.isEmpty())
            throw new IOException("Échéance introuvable dans les coefficients AFT : " + maturity);
        return result;
    }

    public static NavigableMap<LocalDate, BigDecimal> borsaHistory(String json)
    {
        var payload = JsonParser.parseString(json).getAsJsonObject();
        var history = payload.has("history") ? payload.getAsJsonObject("history") : payload;
        var result = new TreeMap<LocalDate, BigDecimal>();
        for (var entry : history.getAsJsonArray("historyDt"))
        {
            var row = entry.getAsJsonObject();
            if (!row.has("setPx") || row.get("setPx").isJsonNull() || row.get("setPx").getAsString().isBlank())
                continue;
            putUnique(result, LocalDate.parse(row.get("dt").getAsString(), DateTimeFormatter.BASIC_ISO_DATE), decimal(row.get("setPx").getAsString()));
        }
        return result;
    }

    public static String coefficientFile(String html, String page, boolean euroIndex) throws IOException
    {
        // AFT sometimes keeps older, hidden downloads on the current page. Select the dated publication,
        // not the first matching link or the pre-2016 historical archive.
        var files = new TreeMap<YearMonth, String>();
        var pattern = Pattern.compile("(?i)/(20[0-9]{2})-([0-9]{2})_coef_" + (euroIndex ? "oatei" : "oati") + "[^/]*\\.xlsx?$");
        for (var link : Jsoup.parse(html, page).select("a[href]"))
        {
            String url = link.absUrl("href");
            var uri = URI.create(url);
            var match = pattern.matcher(uri.getPath());
            if (!match.find() || !"www.aft.gouv.fr".equals(uri.getHost()) || !"https".equals(uri.getScheme()))
                continue;
            var month = YearMonth.of(Integer.parseInt(match.group(1)), Integer.parseInt(match.group(2)));
            var previous = files.putIfAbsent(month, url);
            if (previous != null && !previous.equals(url))
                throw new IOException("Plusieurs fichiers AFT pour la même publication.");
        }
        if (files.isEmpty())
            throw new IOException("Lien du fichier de coefficients AFT courant introuvable.");
        return files.lastEntry().getValue();
    }

    private List<BondQuote> indexed(BondSpec bond) throws IOException, InterruptedException
    {
        String aft = "https://www.aft.gouv.fr/fr/" + (bond.euroIndex() ? "oateuroi" : "oati") + "-principaux-chiffres";
        var file = coefficientFile(text(aft), aft, bond.euroIndex());
        var coefficients = coefficients(get(file), bond.maturity());
        String chart = "https://grafici.borsaitaliana.it/summary-chart/" + bond.isin() + "-MOTX?lang=it";
        var element = Jsoup.parse(text(chart)).selectFirst("chart-allinone");
        if (element == null || !bond.isin().equals(element.attr("code")) || !"XMIL".equals(element.attr("exchcode")) || element.attr("token").isBlank())
            throw new IOException("Paramètres du graphique Borsa Italiana invalides.");
        var base = URI.create(element.attr("url"));
        if (!"https://grafici.borsaitaliana.it/api/".equals(base.toString()))
            throw new IOException("Adresse de l'historique Borsa Italiana inattendue.");
        var historyUrl = base.resolve("instruments/" + bond.isin() + ",XMIL,ISIN/history/period?period=10Y&adjustment=true&add-last-price=true");
        var clean = borsaHistory(new String(request(historyUrl.toString(), element.attr("token"), chart), StandardCharsets.UTF_8));
        String detail = "https://www.borsaitaliana.it/borsa/obbligazioni/mot/euro-obbligazioni/scheda/" + bond.isin() + "-MOTX.html?lang=it";
        supplementOfficial(clean, text(detail));
        return indexHistory(bond, clean, coefficients, detail + "\nCoefficients AFT : " + file);
    }

    public static void supplementOfficial(Map<LocalDate, BigDecimal> clean, String html) throws IOException
    {
        var price = Pattern.compile("Prezzo ufficiale.*?class=\"t-text -right\"[^>]*>\\s*([^<]+)", Pattern.DOTALL).matcher(html);
        var date = Pattern.compile("Data Pr Ufficiale.*?class=\"t-text -right\"[^>]*>\\s*([^<]+)", Pattern.DOTALL).matcher(html);
        if (!price.find() || !date.find())
            throw new IOException("Dernier cours officiel Borsa Italiana introuvable.");
        var day = LocalDate.parse(date.group(1).strip(), DateTimeFormatter.ofPattern("dd/MM/yy"));
        clean.put(day, decimal(price.group(1)));
    }

    public static List<BondQuote> indexHistory(BondSpec bond, NavigableMap<LocalDate, BigDecimal> clean,
                    NavigableMap<LocalDate, BigDecimal> coefficients, String source) throws IOException
    {
        if (coefficients.isEmpty() || clean.isEmpty())
            throw new IOException("Historique de cours ou coefficients vide.");
        var first = coefficients.lastKey();
        while (coefficients.containsKey(first.minusDays(1)))
            first = first.minusDays(1);
        var result = new ArrayList<BondQuote>();
        for (var entry : clean.entrySet())
        {
            var settlement = BondMath.settlement(entry.getKey());
            if (settlement.isBefore(first))
                continue; // Older discontinuous AFT windows cannot be combined into a homogeneous history.
            var coefficient = coefficients.get(settlement);
            if (coefficient == null)
                throw new IOException("Coefficient AFT manquant au règlement du " + settlement);
            result.add(BondMath.indexed(bond, entry.getKey(), entry.getValue(), coefficient, source));
        }
        if (result.isEmpty())
            throw new IOException("Aucune date commune entre cours et coefficients AFT.");
        return result;
    }
}
