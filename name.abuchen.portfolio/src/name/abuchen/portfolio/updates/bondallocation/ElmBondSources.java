package name.abuchen.portfolio.updates.bondallocation;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import org.jsoup.Jsoup;

import name.abuchen.portfolio.updates.bondallocation.BondComposition.Family;
import name.abuchen.portfolio.updates.bondallocation.BondComposition.Slice;
import name.abuchen.portfolio.updates.elm.ElmAllocation;

/** Per-command cache: one consistent parent composition for the four previews, no persistent web cache. */
public final class ElmBondSources
{
    private static final String VANGUARD_API = "https://advisors.vanguard.com/investments/products/api/funds/0928/";
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
                    .followRedirects(HttpClient.Redirect.NORMAL).build();
    private final Map<String, String> pages = new java.util.concurrent.ConcurrentHashMap<>();
    private String schwab;
    private boolean schwabFallback;

    public Slice fetch(Family family, LocalDate reference) throws IOException, InterruptedException
    {
        var html = Jsoup.parse(page(ElmAllocation.SOURCE), ElmAllocation.SOURCE);
        if (!html.text().contains(ElmBondAllocation.ISIN)) throw new IOException("ISIN ELM non confirmé sur la source officielle.");
        var links = html.select("a[href]").stream().filter(a -> a.text().contains("FULL Holdings CSV")).toList();
        if (links.size() != 1) throw new IOException("Lien vers les positions complètes ELM absent ou ambigu.");
        String url = links.getFirst().absUrl("href");
        var uri = URI.create(url);
        if (!"https".equals(uri.getScheme()) || !"docs.google.com".equals(uri.getHost()) || !"/spreadsheets/export".equals(uri.getPath()))
            throw new IOException("Adresse des positions ELM non reconnue.");
        var holdings = ElmBondAllocation.parseHoldings(page(url), reference);
        var funds = new LinkedHashMap<String, ElmBondAllocation.Fund>();
        for (String ticker : holdings.bonds().keySet())
        {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            switch (ticker)
            {
                case "SCHP" -> funds.put(ticker, ElmBondAllocation.parseSchwab(schwab(), family, reference, schwabFallback));
                case "BND" -> {
                    String identity = page(ElmBondAllocation.BND);
                    if (!identity.contains("\"fundIdentifier\":\"BND\"") || !identity.contains("\"portId\":\"0928\""))
                        throw new IOException("Identité BND non confirmée.");
                    String positions = page(VANGUARD_API + "holdings/latest");
                    String date = com.google.gson.JsonParser.parseString(positions).getAsJsonObject().get("latestEffectiveDate").getAsString();
                    String details = switch (family)
                    {
                        case RATING -> page(VANGUARD_API + "exposures/credit-quality?effectiveDate=" + date);
                        case ISSUER -> page(VANGUARD_API + "exposures/issuers?sectorExposureCode=SECTORMVB");
                        case REGIONS -> page(VANGUARD_API + "exposures/regions/fixed-income?effectiveDate="
                                        + reference.minusMonths(6).withDayOfMonth(1) + ":to:" + reference);
                        case MATURITY -> "";
                    };
                    funds.put(ticker, ElmBondAllocation.parseVanguard(positions, details, family, reference));
                }
                case "AGG" -> funds.put(ticker, ElmBondAllocation.parseIshares(page(ElmBondAllocation.AGG + "/latest-holdings.csv"),
                                family == Family.RATING ? page(ElmBondAllocation.AGG) : "", family, reference));
                default -> throw new IOException("Fonds obligataire ELM non pris en charge : " + ticker);
            }
        }
        return ElmBondAllocation.combine(holdings, funds, family);
    }

    private String schwab() throws IOException, InterruptedException
    {
        if (schwab != null) return schwab;
        try
        {
            var document = Jsoup.parse(page(ElmBondAllocation.SCHP), ElmBondAllocation.SCHP);
            var links = document.select("a[href]").stream().filter(a -> a.text().contains("Export All Holdings"))
                            .map(a -> a.absUrl("href")).distinct().toList();
            if (links.size() != 1) throw new IOException("Export SCHP absent ou ambigu.");
            var uri = URI.create(links.getFirst());
            if (!"https".equals(uri.getScheme()) || !"www.schwabassetmanagement.com".equals(uri.getHost())
                            || !uri.getPath().contains("/SCHP/SCHP_FundHoldings_")) throw new IOException("Adresse SCHP non reconnue.");
            schwab = page(uri.toString());
        }
        catch (IOException e)
        {
            try (var stream = ElmBondSources.class.getResourceAsStream("elm-schp-2026-10-15.csv"))
            {
                if (stream == null) throw new IOException("Copie officielle SCHP absente.", e);
                schwab = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                schwabFallback = true;
            }
        }
        return schwab;
    }

    private String page(String url) throws IOException, InterruptedException
    {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        String cached = pages.get(url);
        if (cached != null) return cached;
        var response = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(35))
                        .header("User-Agent", "Mozilla/5.0").GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200 || response.body().length() > 25_000_000)
            throw new IOException("Source ELM/sous-jacent indisponible (HTTP " + response.statusCode() + ") : " + url);
        pages.put(url, response.body());
        return response.body();
    }
}
