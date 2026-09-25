package name.abuchen.portfolio.updates.inflation;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.function.BooleanSupplier;

import org.jsoup.Jsoup;
import name.abuchen.portfolio.model.*;
import name.abuchen.portfolio.updates.bonds.BondSources;
import name.abuchen.portfolio.updates.bonds.BondWorkbook;
import name.abuchen.portfolio.updates.bonds.BondUpdate;

/** AFT daily euro HICP references, not monthly inflation rates or bond coefficients. */
@SuppressWarnings("nls")
public final class AftInflation
{
    public static final String PAGE = "https://www.aft.gouv.fr/fr/oateuroi-principaux-chiffres";
    public static final String NAME = "EU (IPCH)";
    public record Point(LocalDate date, long value) { }
    public record Download(String url, List<Point> points)
    {
        public Download { points = List.copyOf(points); }
    }
    public record Plan(String id, BondUpdate.State before, Download download, int added, int corrected) { }
    private AftInflation() { }

    public static Security security(Client client)
    {
        var matches = client.getSecurities().stream().filter(s -> NAME.equals(s.getName())).toList();
        if (matches.size() != 1) throw new IllegalArgumentException("Il faut exactement un instrument nommé « EU (IPCH) ».");
        return matches.getFirst();
    }

    public static String workbookLink(String html) throws IOException
    {
        var doc = Jsoup.parse(html, PAGE);
        if (!doc.text().replaceAll("\\s+", "").contains("2025=100"))
            throw new IOException("La base 2025 = 100 n'est plus confirmée sur la page AFT. Aucune mise à jour appliquée.");
        var links = doc.select("a[href]").stream().map(a -> a.absUrl("href"))
                        .filter(url -> url.toLowerCase(Locale.ROOT).matches("https://www\\.aft\\.gouv\\.fr/files/.*coef_oatei[^?]*\\.xlsx"))
                        .distinct().toList();
        if (links.size() != 1) throw new IOException("Classeur des coefficients OAT€i absent ou ambigu sur la page AFT.");
        return links.getFirst();
    }

    public static Download fetch(BooleanSupplier cancelled, LocalDate today) throws IOException, InterruptedException
    {
        var transport = new BondSources(cancelled, today);
        String url = workbookLink(new String(transport.fetchAft(PAGE), StandardCharsets.UTF_8));
        var points = parse(transport.fetchAft(url), today);
        if (points.size() < 365 || points.getFirst().date().isAfter(LocalDate.of(2002, 1, 1)))
            throw new IOException("Le classeur AFT ne contient pas l'historique quotidien attendu.");
        if (cancelled.getAsBoolean()) throw new InterruptedException();
        return new Download(url, points);
    }

    public static List<Point> parse(byte[] bytes, LocalDate today) throws IOException
    {
        try
        {
            var sheets = BondWorkbook.read(bytes).stream().filter(sheet -> {
                var header = sheet.getOrDefault(1, Map.of());
                return header.getOrDefault("A", "").contains("jour")
                                && header.getOrDefault("B", "").replaceAll("\\s+", " ").contains("daily inflation reference");
            }).toList();
            if (sheets.size() != 1) throw new IOException("Colonne de référence quotidienne IPCH absente ou ambiguë.");
            var values = new TreeMap<LocalDate, Long>();
            for (var entry : sheets.getFirst().entrySet())
            {
                if (entry.getKey() < 10) continue;
                var row = entry.getValue();
                String date = row.getOrDefault("A", ""), value = row.getOrDefault("B", "");
                if (date.isBlank() && value.isBlank()) continue;
                long serial = new BigDecimal(date).longValueExact();
                LocalDate day = LocalDate.of(1899, 12, 30).plusDays(serial);
                long scaled = new BigDecimal(value).setScale(5, RoundingMode.HALF_UP).movePointRight(8).longValueExact();
                if (day.isBefore(LocalDate.of(1990, 1, 1)) || day.isAfter(today.plusMonths(3)) || scaled <= 0)
                    throw new IOException("Date ou référence IPCH invalide : " + day);
                if (values.putIfAbsent(day, scaled) != null) throw new IOException("Date IPCH dupliquée : " + day);
            }
            if (values.isEmpty()) throw new IOException("Aucune référence IPCH reçue.");
            if (values.lastKey().isBefore(today.minusMonths(3))) throw new IOException("Le classeur IPCH est trop ancien.");
            LocalDate previous = null;
            for (var day : values.keySet())
            {
                if (previous != null && !day.equals(previous.plusDays(1))) throw new IOException("Trou dans la série IPCH : " + previous + " → " + day);
                previous = day;
            }
            return values.entrySet().stream().map(e -> new Point(e.getKey(), e.getValue())).toList();
        }
        catch (RuntimeException e) { throw new IOException("Classeur IPCH illisible ou valeur manquante.", e); }
    }

    public static Plan prepare(Security security, Download download)
    {
        if (!NAME.equals(security.getName()) || !"MANUAL".equals(security.getFeed())
                        || (security.getLatestFeed() != null && !"MANUAL".equals(security.getLatestFeed())))
            throw new IllegalArgumentException("« EU (IPCH) » doit utiliser le fournisseur Manuel.");
        URI url = URI.create(download.url());
        if (!"https".equals(url.getScheme()) || !"www.aft.gouv.fr".equals(url.getHost()))
            throw new IllegalArgumentException("Source AFT invalide.");
        var old = new TreeMap<LocalDate, Long>();
        security.getPricesIncludingLatest().forEach(p -> old.put(p.getDate(), p.getValue()));
        int added = 0, corrected = 0;
        LocalDate previous = null;
        for (var point : download.points())
        {
            if (point.value() <= 0 || (previous != null && !point.date().equals(previous.plusDays(1))))
                throw new IllegalArgumentException("Série quotidienne invalide.");
            previous = point.date();
            Long before = old.get(point.date());
            if (before == null) added++;
            else
            {
                // A base change must never be spliced into an existing series silently.
                if (before <= 0 || Math.abs(point.value() / (double) before - 1) > .01)
                    throw new IllegalArgumentException("Base incompatible avec l'historique existant au " + point.date()
                                    + ". Vérifier la base 2025 avant mise à jour.");
                if (before.longValue() != point.value()) corrected++;
            }
        }
        if (previous == null || (!old.isEmpty() && previous.isBefore(old.lastKey())))
            throw new IllegalArgumentException("La source est vide ou plus ancienne que l'historique existant.");
        return new Plan(security.getUUID(), BondUpdate.state(security), download, added, corrected);
    }

    public static void apply(Client client, Plan plan)
    {
        var security = security(client);
        if (!security.getUUID().equals(plan.id()) || !prepare(security, plan.download()).equals(plan))
            throw new IllegalArgumentException("L'instrument a changé depuis l'aperçu. Relancer la mise à jour.");
        // These future-dated references are already published, unlike forecast market quotes.
        // addAllPrices would discard them and would not correct older overlapping dates.
        plan.download().points().forEach(p -> security.addPrice(new SecurityPrice(p.date(), p.value()), true));
        // Fold any previous latest quote into history; AFT future references are already published values.
        var oldLatest = security.getLatest();
        if (oldLatest != null && plan.download().points().stream().noneMatch(p -> p.date().equals(oldLatest.getDate())))
            security.addPrice(new SecurityPrice(oldLatest.getDate(), oldLatest.getValue()));
        security.setLatest(null);
        var note = Objects.toString(security.getNote(), "").replaceAll("(?s)\\n?\\[AFT-IPCH-BEGIN\\].*?\\[AFT-IPCH-END\\]", "").stripTrailing();
        security.setNote(note + "\n\n[AFT-IPCH-BEGIN]\nRéférence quotidienne OAT€i — IPCH zone euro hors tabac, base 2025 = 100."
                        + "\nSource : " + plan.download().url() + "\nDernière référence publiée : " + plan.download().points().getLast().date()
                        + "\n[AFT-IPCH-END]");
        security.setUpdatedAt(Instant.now());
        client.markDirty();
    }
}
