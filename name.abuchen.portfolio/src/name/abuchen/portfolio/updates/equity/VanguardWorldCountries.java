package name.abuchen.portfolio.updates.equity;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Properties;

import com.google.gson.JsonParser;

import name.abuchen.portfolio.updates.equity.EquityComposition.Item;
import name.abuchen.portfolio.updates.equity.EquityComposition.Slice;

/** Complete issuer geography; the public HTML table only contains the first 15 countries. */
final class VanguardWorldCountries
{
    static final String ISIN = "IE00BK5BQT80";
    static final String ENDPOINT = "https://www.vanguard.co.uk/gpx/graphql";
    static final String SOURCE = "https://www.vanguard.co.uk/professional/product/etf/equity/9679/ftse-all-world-ucits-etf-usd-accumulating";
    static final String REQUEST = """
                    {"query":"query { funds(portIds: [\\\"9679\\\"]) { profile { fundFullName } marketAllocation { portId date countryCode fundMktPercent holdingStatCode } } }"}
                    """;

    private VanguardWorldCountries() { }

    static Slice parse(String json) throws IOException
    {
        try
        {
            var root = JsonParser.parseString(json).getAsJsonObject();
            if (root.has("errors")) throw new IOException("Erreur de données Vanguard.");
            var funds = root.getAsJsonObject("data").getAsJsonArray("funds");
            if (funds.size() != 1) throw new IOException("Fonds Vanguard ambigu.");
            var fund = funds.get(0).getAsJsonObject();
            if (!"Vanguard FTSE All-World UCITS ETF (USD) Accumulating".equals(
                            fund.getAsJsonObject("profile").get("fundFullName").getAsString()))
                throw new IOException("Identité du fonds Vanguard non confirmée.");
            var names = new Properties();
            try (var input = VanguardWorldCountries.class.getResourceAsStream(
                            "/name/abuchen/portfolio/model/taxonomy_templates/regions-msci_fr.properties"))
            {
                names.load(input);
            }
            var items = new ArrayList<Item>();
            var codes = new HashSet<String>();
            LocalDate date = null;
            for (var element : fund.getAsJsonArray("marketAllocation"))
            {
                var row = element.getAsJsonObject();
                // The response also contains MSCI country weights and regional totals: never add these twice.
                if (!"FTCTYATPCS".equals(row.get("holdingStatCode").getAsString())) continue;
                if (!"9679".equals(row.get("portId").getAsString()))
                    throw new IOException("Identifiant Vanguard inattendu.");
                var rowDate = LocalDate.parse(row.get("date").getAsString());
                if (date != null && !date.equals(rowDate)) throw new IOException("Dates Vanguard incohérentes.");
                date = rowDate;
                String code = row.get("countryCode").getAsString();
                if (!codes.add(code)) throw new IOException("Pays Vanguard dupliqué : " + code);
                String name = "OT".equals(code) ? "Other" : names.getProperty("country_" + code + ".label");
                if (name == null) throw new IOException("Pays Vanguard inconnu : " + code);
                items.add(new Item(name, row.get("fundMktPercent").getAsBigDecimal()));
            }
            BigDecimal total = items.stream().map(Item::percent).reduce(BigDecimal.ZERO, BigDecimal::add);
            // Permit only source precision rounding, never fill a missing geography with a large Other bucket.
            if (date == null || codes.size() < 40 || !codes.containsAll(java.util.List.of("US", "JP", "KR", "CN", "GB", "TW"))
                            || total.subtract(new BigDecimal("100")).abs().compareTo(new BigDecimal("0.001")) > 0)
                throw new IOException("Répartition Vanguard incomplète : " + total + " %.");

            // PP stores hundredths of a percent. Largest remainders preserve 100% without inventing Other.
            int[] units = new int[items.size()];
            var order = new ArrayList<Integer>();
            int assigned = 0;
            for (int i = 0; i < items.size(); i++)
            {
                units[i] = items.get(i).percent().movePointRight(2).intValue();
                assigned += units[i];
                order.add(i);
            }
            order.sort(Comparator.<Integer, BigDecimal>comparing(i -> items.get(i).percent().movePointRight(2)
                            .remainder(BigDecimal.ONE)).reversed().thenComparing(i -> items.get(i).name()));
            int missing = 10000 - assigned;
            if (missing < 0 || missing > order.size()) throw new IOException("Arrondi Vanguard invalide.");
            for (int i = 0; i < missing; i++) units[order.get(i)]++;
            var rounded = new ArrayList<Item>();
            for (int i = 0; i < items.size(); i++)
                rounded.add(new Item(items.get(i).name(), BigDecimal.valueOf(units[i], 2)));
            return new Slice(rounded, date, SOURCE, false);
        }
        catch (RuntimeException e)
        {
            throw new IOException("Répartition Vanguard invalide : affectations conservées.", e);
        }
    }
}
