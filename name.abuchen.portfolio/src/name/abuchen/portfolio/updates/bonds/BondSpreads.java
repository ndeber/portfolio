package name.abuchen.portfolio.updates.bonds;

import java.time.LocalDate;
import java.util.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import name.abuchen.portfolio.model.*;
import name.abuchen.portfolio.money.Values;
import name.abuchen.portfolio.updates.yields.YieldMath;

/** Signed basis-point indices, calculated from common-date dirty reference prices. */
public final class BondSpreads
{
    public static final String DE = "DE000BU2Z072", FR = "FR0013154044", US = "US91282CRF04";
    public record Point(LocalDate date, double countryYield, double bundYield, long value) { }
    public record Series(String ticker, String name, List<Point> points, String note) { }
    private BondSpreads() { }

    public static double referenceYield(String isin, LocalDate quoteDate, double dirty)
    {
        if (DE.equals(isin)) return YieldMath.longFirstCouponBond(dirty, .03, BondMath.settlement(quoteDate),
                        LocalDate.of(2036, 8, 15), LocalDate.of(2026, 7, 10), LocalDate.of(2027, 8, 15));
        var b = BondSpec.find(isin).orElseThrow();
        if (!b.nominal()) throw new IllegalArgumentException("Référence non nominale.");
        double y = YieldMath.periodicBond(dirty, b.coupon().doubleValue(), US.equals(isin) ? quoteDate : BondMath.settlement(quoteDate), b.maturity(), b.frequency());
        return b.frequency() == 2 ? Math.pow(1 + y / 2, 2) - 1 : y;
    }

    public static List<Point> calculate(String country, NavigableMap<LocalDate, Long> left, NavigableMap<LocalDate, Long> bund, LocalDate today)
    {
        var points = new ArrayList<Point>();
        var start = BondSpec.find(country).orElseThrow().issued();
        for (var e : left.entrySet())
        {
            var d = e.getKey();
            if (d.isBefore(start) || d.isBefore(LocalDate.of(2026, 7, 10)) || d.isAfter(today) || !bund.containsKey(d)) continue;
            double l = referenceYield(country, d, e.getValue() / (double) Values.Quote.factor());
            double r = referenceYield(DE, d, bund.get(d) / (double) Values.Quote.factor());
            long value = BigDecimal.valueOf((l - r) * 10_000).multiply(BigDecimal.valueOf(Values.Quote.factor()))
                            .setScale(0, RoundingMode.HALF_UP).longValueExact();
            points.add(new Point(d, l, r, value));
        }
        if (points.isEmpty()) throw new IllegalArgumentException("Aucun cours de même date pour " + country + " et le Bund.");
        if (points.getLast().date().isBefore(today.minusDays(7))) throw new IllegalArgumentException("Dernière date commune de spread trop ancienne.");
        return List.copyOf(points);
    }

    private static Security reference(Client client, String isin)
    {
        var refs = client.getSecurities().stream().filter(s -> isin.equals(s.getIsin())).toList();
        if (refs.size() != 1 || !BondUpdate.managed(refs.getFirst())
                        || !BondSpec.find(isin).orElseThrow().currency().equals(refs.getFirst().getCurrencyCode()))
            throw new IllegalArgumentException("Référence absente, ambiguë ou convention dirty inconnue : " + isin);
        return refs.getFirst();
    }

    private static NavigableMap<LocalDate, Long> prices(Security security)
    {
        var map = new TreeMap<LocalDate, Long>();
        security.getPricesIncludingLatest().forEach(p -> map.put(p.getDate(), p.getValue()));
        return map;
    }

    public static List<Series> prepare(Client client, LocalDate today)
    {
        var de = reference(client, DE);
        var result = new ArrayList<Series>();
        for (String country : List.of(US, FR))
        {
            var left = reference(client, country);
            var points = calculate(country, prices(left), prices(de), today);
            var last = points.getLast();
            String code = US.equals(country) ? "US" : "FR";
            String note = "[XAPA-SPREAD-V1]\nUnité : points de base (1 point de cours = 1 pb), indice sans devise.\n"
                            + "Formule : 10000 × (YTM effectif annuel " + code + " − YTM effectif annuel DE).\n"
                            + "Références : " + country + " − " + DE + "; dates de cours strictement communes, sans interpolation.\n"
                            + "Dernière date commune : " + last.date() + "; YTM " + code + " : " + last.countryYield() + "; YTM DE : " + last.bundYield() + ".\n"
                            + "Bund : premier coupon long 15/08/2027, jouissance 10/07/2026, ACT/ACT ICMA ; OAT : ACT/ACT annuel, TARGET T+2.\n"
                            + "US : coupons semestriels, rendement converti en effectif annuel ; référence indicative FedInvest T+0.\n"
                            + "FR : échéance 25/05/2036, 82 jours avant le Bund. US : échéance 15/08/2036 comme le Bund.\n"
                            + "Écarts de rendements souverains ; US-DE inclut deux courbes de devises différentes, ce n'est pas un spread de crédit couvert du change.\n"
                            + "Source " + code + " : " + Objects.toString(left.getNote(), "") + "\nSource DE : " + Objects.toString(de.getNote(), "");
            result.add(new Series("XAPA-SPREAD-" + code + "-DE", "Spread " + code + "-DE", points, note));
        }
        // Validate every destination before mutating any asset.
        for (var series : result)
        {
            var matches = destinations(client, series);
            if (matches.size() > 1) throw new IllegalArgumentException("Indice de spread dupliqué : " + series.name());
            if (!matches.isEmpty())
            {
                var s = matches.getFirst();
                if (s.getCurrencyCode() != null || !"MANUAL".equals(s.getFeed()) || !Objects.toString(s.getNote(), "").contains("[XAPA-SPREAD-V1]"))
                    throw new IllegalArgumentException("Indice de spread de convention inconnue : " + series.name());
                if (!s.getPricesIncludingLatest().isEmpty() && s.getPricesIncludingLatest().getLast().getDate().isAfter(series.points().getLast().date()))
                    throw new IllegalArgumentException("La date du spread reculerait : " + series.name());
            }
        }
        return List.copyOf(result);
    }

    private static List<Security> destinations(Client client, Series series)
    {
        return client.getSecurities().stream().filter(s -> series.ticker().equals(s.getTickerSymbol()) || series.name().equals(s.getName())).toList();
    }

    public static void update(Client client, LocalDate today)
    {
        var seriesList = prepare(client, today);
        for (var series : seriesList)
        {
            var existing = destinations(client, series);
            var security = existing.isEmpty() ? new Security(series.name(), null) : existing.getFirst();
            if (existing.isEmpty()) client.addSecurity(security);
            security.setName(series.name()); security.setTickerSymbol(series.ticker());
            security.setFeed("MANUAL"); security.setLatestFeed("MANUAL"); security.setNote(series.note());
            for (var p : series.points()) security.addPrice(new SecurityPrice(p.date(), p.value()));
            var last = series.points().getLast();
            security.setLatest(new LatestSecurityPrice(last.date(), last.value(), -1, -1, -1));
        }
        client.markDirty();
    }
}
