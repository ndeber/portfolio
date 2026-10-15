package name.abuchen.portfolio.factors;

import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.apache.commons.csv.CSVFormat;
import org.jsoup.Jsoup;
import name.abuchen.portfolio.model.*;
import name.abuchen.portfolio.updates.equity.EquityTask;

/** Published sources; unknown risk is never replaced with the share currency. */
@SuppressWarnings("nls")
public final class CurrencySources
{
    public static final String ELM_PAGE = "https://www.elmfunds.com/elm-market-navigator-etf";

    public CurrencyProposals.Evidence fetch(Client client, Security security, LocalDate today, BooleanSupplier cancelled)
                    throws InterruptedException
    {
        if (cancelled.getAsBoolean()) throw new InterruptedException();
        if (FactorMap.isDbi(security))
            return CurrencyProposals.missing("DBi est traité séparément : une part EUR couverte ne permet pas de reconstruire les positions FX de la stratégie.");
        if (CurrencyProposals.isElm(client, security))
        {
            try
            {
                return EquityTask.run(() -> {
                    var page = Jsoup.parse(download(ELM_PAGE), ELM_PAGE);
                    var links = page.select("a[href]").stream().filter(a -> a.text().contains("FULL Holdings CSV")).toList();
                    if (links.size() != 1) throw new IOException("Lien des positions ELM absent ou ambigu.");
                    String url = links.getFirst().absUrl("href");
                    var uri = URI.create(url);
                    if (!"https".equals(uri.getScheme()) || !"docs.google.com".equals(uri.getHost()) || !uri.getPath().equals("/spreadsheets/export"))
                        throw new IOException("Lien public ELM non reconnu.");
                    return parseElm(download(url), url, today);
                }, cancelled, Duration.ofSeconds(45));
            }
            catch (IOException | RuntimeException e)
            {
                return CurrencyProposals.missing("ELM : lecture impossible, affectations conservées. " + e.getMessage());
            }
        }
        String isin = Objects.toString(security.getIsin(), "");
        if ("LU1165644672".equals(isin))
        {
            try
            {
                return EquityTask.run(() -> CurrencyReports.fetchIvo(today), cancelled, Duration.ofSeconds(45));
            }
            catch (IOException | RuntimeException e)
            {
                var saved = CurrencyReports.reviewed(isin, today);
                return new CurrencyProposals.Evidence(saved.weights(), saved.method(), saved.date(), saved.source(),
                                saved.note() + " Actualisation impossible : " + e.getMessage(), false);
            }
        }
        return CurrencyReports.reviewed(isin, today);
    }

    public static CurrencyProposals.Evidence parseElm(String csv, String source, LocalDate today) throws IOException
    {
        var seen = new HashSet<String>();
        LocalDate date = null;
        double nav = 0, total = 0;
        try (var parser = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build().parse(new StringReader(csv)))
        {
            for (var row : parser)
            {
                if (!row.get("Account").equals("ELM")) throw new IOException("Compte ELM non confirmé.");
                var current = LocalDate.parse(row.get("Date"), DateTimeFormatter.ofPattern("MM/dd/uuuu"));
                if (current.isAfter(today) || date != null && !date.equals(current)) throw new IOException("Dates ELM incohérentes ou futures.");
                date = current;
                double currentNav = Double.parseDouble(row.get("Net Assets"));
                if (!Double.isFinite(currentNav) || currentNav <= 0 || nav != 0 && nav != currentNav) throw new IOException("Actif net ELM incohérent.");
                nav = currentNav;
                String ticker = row.get("Stock Ticker");
                if (!seen.add(ticker)) throw new IOException("Position ELM dupliquée.");
                double value = Double.parseDouble(row.get("Market Value"));
                if (!Double.isFinite(value) || value < 0) throw new IOException("Position ELM négative / invalide, non représentable dans la taxonomie.");
                total += value / nav;

            }
        }
        catch (RuntimeException e) { throw new IOException("Format des positions ELM inconnu : " + e.getMessage(), e); }
        if (date == null || seen.size() < 5 || Math.abs(total - 1) > .001) throw new IOException("Positions ELM incomplètes.");
        return new CurrencyProposals.Evidence(Map.of(), "ELM · positions, devises non publiées", date, source,
                        "Positions réelles ELM vérifiées (" + seen.size() + " lignes). La devise de cotation et le mandat géographique "
                                        + "des ETF ne sont pas une exposition nette. Ventilations par devise des sous-jacents et couvertures requises ; "
                                        + "aucune répartition USD/CAD déduite. Source de découverte : " + ELM_PAGE, false);
    }

    /** Largest-remainder rounding; unknown residual retained, never scaled over known currencies. */
    static Map<String, Integer> points(Map<String, Double> source)
    {
        var all = new TreeMap<>(source);
        double total = source.values().stream().mapToDouble(Double::doubleValue).sum();
        if (!Double.isFinite(total) || total < 0 || total > 1.000001 || source.values().stream().anyMatch(v -> !Double.isFinite(v) || v < 0))
            throw new IllegalArgumentException("Somme d'expositions invalide.");
        if (total < 1) all.put("UNKNOWN", 1 - total);
        var result = new TreeMap<String, Integer>();
        var fractions = new HashMap<String, Double>();
        all.forEach((k,v) -> { result.put(k, (int)Math.floor(v * 10000)); fractions.put(k, v * 10000 - Math.floor(v * 10000)); });
        int remainder = 10000 - result.values().stream().mapToInt(Integer::intValue).sum();
        var order = all.keySet().stream().sorted(Comparator.<String, Double>comparing(fractions::get).reversed().thenComparing(k -> k)).toList();
        if (remainder < 0 || remainder > order.size()) throw new IllegalArgumentException("Arrondi impossible.");
        for (int i = 0; i < remainder; i++) result.merge(order.get(i), 1, Integer::sum);
        result.values().removeIf(v -> v == 0);
        return Map.copyOf(result);
    }

    private static String download(String url) throws IOException, InterruptedException
    {
        try (var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build())
        {
            var response = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
                            .header("User-Agent", "Mozilla/5.0").GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200 || response.body().length() > 5_000_000) throw new IOException("Source HTTP " + response.statusCode());
            return response.body();
        }
    }

}
