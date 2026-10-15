package name.abuchen.portfolio.updates.bondallocation;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import com.google.gson.JsonParser;

import name.abuchen.portfolio.updates.bondallocation.BondComposition.Family;
import name.abuchen.portfolio.updates.bondallocation.BondComposition.Slice;

public final class GoldmanBondAllocation
{
    public static final String ISIN = "LU0234688595";
    public static final String DOCUMENTS = "https://am.gs.com/api/documents";
    public static final String REQUEST = """
                    {"documentType_s":["monthlyFundUpdate"],"documentCategory_s":[],
                    "audience_s":["institutions"],"language_s":["en"],"country_s":["dk"],
                    "documentSource_s":[],"pvNumber_s":["PV100313"],"isin_s":["LU0234688595"],
                    "latest":true,"startIndex":0,"count":100}
                    """;

    public record Document(LocalDate date, String url) { }

    private GoldmanBondAllocation() { }

    public static Document latestDocument(String json, LocalDate reference) throws IOException
    {
        Document latest = null;
        for (var element : JsonParser.parseString(json).getAsJsonArray())
        {
            var fields = new LinkedHashMap<String, List<String>>();
            for (var field : element.getAsJsonObject().getAsJsonArray("LexFields"))
            {
                var row = field.getAsJsonObject();
                var values = new ArrayList<String>();
                row.getAsJsonArray("values").forEach(value -> values.add(value.getAsString()));
                fields.put(row.get("name").getAsString(), values);
            }
            if (!fields.getOrDefault("isin_s", List.of()).contains(ISIN)
                            || !fields.getOrDefault("documentType_s", List.of()).contains("monthlyFundUpdate")
                            || !fields.getOrDefault("pvNumber_s", List.of()).contains("PV100313"))
                continue;
            var dates = fields.getOrDefault("displayDateTime_t", List.of());
            var ids = fields.getOrDefault("documentId_s", List.of());
            if (dates.size() != 1 || ids.size() != 1 || !ids.getFirst().matches("[a-zA-Z0-9-]+"))
                throw new IOException("Métadonnées du reporting Goldman Sachs incomplètes.");
            var date = LocalDate.parse(dates.getFirst().substring(0, 10));
            if (date.isAfter(reference)) continue;
            var candidate = new Document(date, "https://am.gs.com/public-assets/documents/" + ids.getFirst());
            if (latest == null || date.isAfter(latest.date())) latest = candidate;
            else if (date.equals(latest.date()) && !candidate.equals(latest))
                throw new IOException("Plusieurs reportings Goldman Sachs pour la même part et la même date.");
        }
        if (latest == null) throw new IOException("Reporting mensuel Goldman Sachs de la part " + ISIN + " introuvable.");
        return latest;
    }

    public static void requirePublished(Family family) throws IOException
    {
        if (family == Family.MATURITY)
            throw new IOException("Goldman Sachs ne publie pas les tranches d'échéance dans ce reporting ; la duration n'est pas une échéance, répartition conservée.");
        if (family == Family.ISSUER)
            throw new IOException("Le reporting Goldman Sachs ne sépare pas les entreprises financières et non financières ni les émetteurs de la dette émergente ; répartition conservée.");
    }

    public static Slice parse(String fullText, String column, Family family, Document document) throws IOException
    {
        requirePublished(family);
        if (!fullText.contains(ISIN) || !fullText.contains("Goldman Sachs Global Credit Portfolio (Hedged)"))
            throw new IOException("ISIN de la part Goldman Sachs non confirmé dans le reporting.");
        var asOf = Pattern.compile("All performance and (?:fund )?holdings data (?:is )?as at\\s+(\\d{1,2}-[A-Za-z]{3}-\\d{2})")
                        .matcher(fullText.replaceAll("\\s+", " "));
        if (!asOf.find() || !LocalDate.parse(asOf.group(1), DateTimeFormatter.ofPattern("d-MMM-uu", Locale.ENGLISH)).equals(document.date()))
            throw new IOException("La date de composition du PDF Goldman Sachs ne correspond pas au reporting sélectionné.");
        if (family == Family.RATING)
        {
            String table = table(column, "Credit Allocation (%)", "Goldman Sachs");
            var values = new LinkedHashMap<String, BigDecimal>();
            for (String label : List.of("AAA", "AA", "A", "BBB", "BB", "B", "NR", "Cash", "Derivatives"))
            {
                // The portfolio bar is above its label; the benchmark bar is below it.
                var match = Pattern.compile("(?m)^\\s*([-\\d.]+)\\s*\\R[ \\t]*" + Pattern.quote(label) + "[ \\t]*$").matcher(table);
                if (!match.find()) throw new IOException("Ligne de notation Goldman Sachs absente : " + label);
                BigDecimal value = new BigDecimal(match.group(1));
                if (value.signum() < 0 || match.find()) throw new IOException("Notation Goldman Sachs négative ou ambiguë : " + label);
                values.merge(List.of("NR", "Derivatives").contains(label) ? "Non noté" : label, value, BigDecimal::add);
            }
            requireComplete(values);
            return new Slice(BondAllocationSources.percentages(values, "Non noté", true), document.date(), document.url(),
                            "Notations du portefeuille, indice de référence exclu ; dérivés classés Non noté ; arrondis normalisés à 100 %.");
        }
        String table = table(column, "Geographical Bond Allocation (%)", "\n\n");
        var rows = Pattern.compile("(?m)^\\s*(-?\\d+(?:\\.\\d+)?)%[ \\t]+([^\\r\\n]+)$").matcher(table);
        var values = new LinkedHashMap<String, BigDecimal>();
        var published = new LinkedHashMap<String, BigDecimal>();
        while (rows.find())
        {
            String name = rows.group(2).strip();
            BigDecimal value = new BigDecimal(rows.group(1));
            if (published.put(name, value) != null) throw new IOException("Pays Goldman Sachs dupliqué : " + name);
            String region = switch (name)
            {
                case "US" -> "États-Unis";
                case "UK" -> "Grande-Bretagne";
                case "Eurozone" -> "Europe";
                default -> BondAllocationSources.region(name);
            };
            // Exclude short exposures before grouping, so they cannot offset a long country.
            if (value.signum() > 0) values.merge(region, value, BigDecimal::add);
        }
        requireComplete(published);
        return new Slice(BondAllocationSources.percentages(values, "Autres", true), document.date(), document.url(),
                        "Géographie obligataire publiée ; zone euro regroupée sous Europe, sans ventilation par pays inventée ; expositions positives normalisées à 100 %, positions négatives exclues.");
    }

    private static String table(String text, String heading, String end) throws IOException
    {
        text = text.replace("\r\n", "\n").replace('\u00a0', ' ');
        int start = text.indexOf(heading);
        if (start < 0) throw new IOException("Table Goldman Sachs absente : " + heading);
        int finish = text.indexOf(end, start + heading.length());
        if (finish < 0 || text.indexOf(heading, start + heading.length()) >= 0)
            throw new IOException("Table Goldman Sachs incomplète ou ambiguë : " + heading);
        return text.substring(start + heading.length(), finish);
    }

    private static void requireComplete(Map<String, BigDecimal> values) throws IOException
    {
        BigDecimal sum = values.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (values.isEmpty() || sum.subtract(new BigDecimal("100")).abs().compareTo(new BigDecimal("0.5")) > 0)
            throw new IOException("Répartition Goldman Sachs incomplète ou incohérente : " + sum + " %.");
    }
}
