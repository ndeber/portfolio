package name.abuchen.portfolio.updates.bonds;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import org.jsoup.Jsoup;

/** Public FedInvest mirror-security reference prices, never an executable market quote. */
public final class TreasurySources
{
    public static final String URL = "https://www.treasurydirect.gov/GA-FI/FedInvest/selectSecurityPriceDate";
    private final BooleanSupplier cancelled;
    public TreasurySources(BooleanSupplier cancelled) { this.cancelled = cancelled; }

    public List<BondQuote> fetch(BondSpec bond, LocalDate today) throws IOException, InterruptedException
    {
        return fetch(bond, today, today.minusDays(7), true);
    }

    public List<BondQuote> fetch(BondSpec bond, LocalDate today, LocalDate from, boolean latestOnly) throws IOException, InterruptedException
    {
        var result = new java.util.TreeMap<LocalDate, BondQuote>();
        if (from.isBefore(bond.issued())) from = bond.issued();
        try (var client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER))
                        .connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NORMAL).build())
        {
            String form = send(client, HttpRequest.newBuilder(URI.create(URL)).timeout(Duration.ofSeconds(30)).GET().build());
            var token = Jsoup.parse(form).selectFirst("input[name=_csrf]");
            if (token == null || token.val().isBlank()) throw new IOException("Formulaire Treasury illisible.");
            for (var date = today.minusDays(1); !date.isBefore(from); date = date.minusDays(1))
            {
                if (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY) continue;
                String body = "priceDate=" + date + "&submit=Show+Prices&_csrf=" + URLEncoder.encode(token.val(), StandardCharsets.UTF_8);
                String html = send(client, HttpRequest.newBuilder(URI.create(URL)).timeout(Duration.ofSeconds(30))
                                .header("Content-Type", "application/x-www-form-urlencoded")
                                .POST(HttpRequest.BodyPublishers.ofString(body)).build());
                var quote = parse(html, bond, date);
                if (quote.isPresent())
                {
                    result.put(date, quote.get());
                    if (latestOnly) break;
                }
            }
        }
        if (result.isEmpty()) throw new IOException("Aucune clôture Treasury FedInvest reçue.");
        return List.copyOf(result.values());
    }

    public static LocalDate historyStart(name.abuchen.portfolio.model.Security security, BondSpec bond)
    {
        var prices = security.getPricesIncludingLatest();
        if (prices.isEmpty() || prices.getFirst().getDate().isAfter(bond.issued().plusDays(7))) return bond.issued();
        return prices.getLast().getDate().minusDays(7);
    }

    private String send(HttpClient client, HttpRequest request) throws IOException, InterruptedException
    {
        if (cancelled.getAsBoolean()) throw new InterruptedException();
        var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (var stream = response.body())
        {
            if (response.statusCode() != 200 || !"www.treasurydirect.gov".equals(response.uri().getHost()))
                throw new IOException("Source Treasury indisponible : HTTP " + response.statusCode());
            var data = stream.readNBytes(2_000_001);
            if (data.length > 2_000_000) throw new IOException("Réponse Treasury trop volumineuse.");
            if (cancelled.getAsBoolean()) throw new InterruptedException();
            return new String(data, StandardCharsets.UTF_8);
        }
    }

    public static Optional<BondQuote> parse(String html, BondSpec bond, LocalDate requested) throws IOException
    {
        var doc = Jsoup.parse(html);
        var heading = doc.select("h2").stream().filter(e -> e.text().startsWith("Prices For:")).findFirst();
        if (heading.isEmpty())
        {
            if (doc.text().contains("prices are not available") || doc.text().contains("No prices")) return Optional.empty();
            throw new IOException("Date Treasury absente.");
        }
        try
        {
            var date = LocalDate.parse(heading.get().text().substring("Prices For:".length()).strip(),
                            DateTimeFormatter.ofPattern("MMMM d, uuuu", Locale.US));
            if (!date.equals(requested)) throw new IOException("La date Treasury diffère de la date demandée.");
            var table = doc.selectFirst("table.data1");
            if (table != null && table.select("td").isEmpty() && table.text().contains("END OF DAY"))
                return Optional.empty(); // Dated, empty official table on a market holiday.
            String cusip = bond.isin().substring(2, 11);
            var rows = doc.select("tr").stream().filter(r -> r.select("td").size() == 8
                            && r.select("td").getFirst().text().equals(cusip)).toList();
            if (rows.size() != 1) throw new IOException("CUSIP Treasury absent ou dupliqué : " + cusip);
            var cells = rows.getFirst().select("td");
            if (!cells.get(1).text().equals("MARKET BASED NOTE")
                            || new BigDecimal(cells.get(2).text().replace("%", "")).movePointLeft(2).compareTo(bond.coupon()) != 0
                            || !LocalDate.parse(cells.get(3).text(), DateTimeFormatter.ofPattern("MM/dd/uuuu")).equals(bond.maturity())
                            || !cells.get(4).text().isBlank()) throw new IOException("Caractéristiques Treasury incohérentes.");
            var clean = new BigDecimal(cells.get(7).text()); // END OF DAY; never substitute BUY or SELL.
            if (clean.signum() == 0) return Optional.empty();
            return Optional.of(BondMath.nominal(bond, date, clean, URL + "\nDate demandée : " + date
                            + "\nRéférence indicative FedInvest END OF DAY (titre miroir GAS), non exécutable ; valorisation à date (T+0), ACT/ACT semestriel"));
        }
        catch (RuntimeException e) { throw new IOException("Données Treasury invalides.", e); }
    }
}
