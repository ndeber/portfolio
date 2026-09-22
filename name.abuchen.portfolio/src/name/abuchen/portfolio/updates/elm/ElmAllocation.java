package name.abuchen.portfolio.updates.elm;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URISyntaxException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

import org.jsoup.Jsoup;
import org.jsoup.parser.Parser;

import com.google.gson.JsonParser;

import name.abuchen.portfolio.util.WebAccess;

/** Published ELM target allocation, matching the Ruby script's bucket rules. */
public record ElmAllocation(LocalDate date, int cash, int bonds, int equities)
{
    public static final String SOURCE = "https://www.elmfunds.com/elm-market-navigator-etf";

    public ElmAllocation
    {
        if (date == null || cash < 0 || bonds < 0 || equities < 0 || (long) cash + bonds + equities != 10000)
            throw new IllegalArgumentException("Allocation ELM invalide.");
    }

    public static ElmAllocation fetch() throws IOException
    {
        try
        {
            return parse(new WebAccess(SOURCE).get(), LocalDate.now(ZoneOffset.UTC));
        }
        catch (URISyntaxException e)
        {
            throw new IOException(e);
        }
    }

    public static ElmAllocation parse(String html, LocalDate today) throws IOException
    {
        try
        {
            var blocks = Jsoup.parse(html).select("#alloc-json");
            if (blocks.size() != 1)
                throw new IllegalArgumentException("Bloc alloc-json absent ou ambigu.");
            var block = blocks.first();
            var json = block.data().isBlank() ? block.html() : block.data();
            var data = JsonParser.parseString(Parser.unescapeEntities(json, false)).getAsJsonObject();
            var stamp = OffsetDateTime.parse(data.get("datestamp").getAsString());
            var date = stamp.withOffsetSameInstant(ZoneOffset.UTC).toLocalDate();
            if (date.isAfter(today))
                throw new IllegalArgumentException("La date publiée est dans le futur.");
            var totals = new BigDecimal[] { BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO };
            var seen = new HashSet<Integer>();
            for (var element : data.getAsJsonArray("rows"))
            {
                var row = element.getAsJsonObject();
                int bucket = row.get("bucket").getAsBigDecimal().intValueExact();
                if (bucket < 1 || bucket > 17 || !seen.add(bucket))
                    throw new IllegalArgumentException("Classe ELM inconnue ou répétée : " + bucket);
                var target = row.get("target").getAsBigDecimal();
                if (target.signum() < 0 || target.compareTo(BigDecimal.ONE) > 0)
                    throw new IllegalArgumentException("Poids ELM hors limites.");
                if (row.has("datestamp") && !stamp.toInstant().equals(
                                OffsetDateTime.parse(row.get("datestamp").getAsString()).toInstant()))
                    throw new IllegalArgumentException("Les dates des lignes ELM ne correspondent pas.");
                int group = bucket == 17 ? 0 : bucket >= 13 ? 1 : 2;
                totals[group] = totals[group].add(target);
            }
            if (!seen.contains(17))
                throw new IllegalArgumentException("La part monétaire est absente.");
            var total = totals[0].add(totals[1]).add(totals[2]);
            if (total.subtract(BigDecimal.ONE).abs().compareTo(new BigDecimal("0.000001")) >= 0)
                throw new IllegalArgumentException("L'allocation publiée ne totalise pas 100 %.");

            int[] points = new int[3];
            var remainders = new ArrayList<BigDecimal>();
            int remaining = 10000;
            for (int i = 0; i < 3; i++)
            {
                var raw = totals[i].movePointRight(4);
                points[i] = raw.setScale(0, RoundingMode.FLOOR).intValueExact();
                remaining -= points[i];
                remainders.add(raw.subtract(BigDecimal.valueOf(points[i])));
            }
            if (remaining < 0 || remaining > 3)
                throw new IllegalArgumentException("Arrondi de l'allocation ELM impossible.");
            var order = new ArrayList<>(List.of(0, 1, 2));
            order.sort(Comparator.<Integer, BigDecimal>comparing(remainders::get).reversed().thenComparingInt(i -> i));
            for (int i = 0; i < remaining; i++)
                points[order.get(i)]++;
            return new ElmAllocation(date, points[0], points[1], points[2]);
        }
        catch (RuntimeException e)
        {
            throw new IOException("Lecture de l'allocation ELM impossible : " + e.getMessage(), e);
        }
    }
}
