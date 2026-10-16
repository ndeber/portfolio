package name.abuchen.portfolio.updates.bondallocation;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.jsoup.Jsoup;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.updates.bondallocation.BondComposition.Family;
import name.abuchen.portfolio.updates.bondallocation.BondComposition.Slice;
import name.abuchen.portfolio.updates.elm.ElmAllocation;

/** Actual holdings look-through. Every final weight is a percentage of the whole ELM holding. */
public final class ElmBondAllocation
{
    public static final String ISIN = "US81752T4296";
    public static final String SCHP = "https://www.schwabassetmanagement.com/products/schp";
    public static final String BND = "https://advisors.vanguard.com/investments/products/bnd/vanguard-total-bond-market-etf";
    public static final String AGG = "https://www.ishares.com/us/products/239458/ishares-core-u-s-aggregate-bond-etf";
    public static final String US_RATING_SOURCE = "https://www.moodys.com/web/en/us/about-us/usrating.html";
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final DateTimeFormatter US_DATE = DateTimeFormatter.ofPattern("M/d/uuuu").withResolverStyle(java.time.format.ResolverStyle.STRICT);
    private static final DateTimeFormatter ISHARES_DATE = DateTimeFormatter.ofPattern("MMM d, uuuu", Locale.US).withResolverStyle(java.time.format.ResolverStyle.STRICT);
    // Identity checked against the full official CSV, including the positions omitted from the top ten.
    private static final Map<String, String> CUSIPS = Map.ofEntries(
                    Map.entry("AGG", "464287226"), Map.entry("BND", "921937835"), Map.entry("SCHP", "808524870"),
                    Map.entry("BBCA", "46641Q225"), Map.entry("BBEU", "46641Q191"), Map.entry("FGXXX", "31846V336"),
                    Map.entry("IEMG", "46434G103"), Map.entry("IEUR", "46434V738"), Map.entry("IPAC", "46434V696"),
                    Map.entry("SCHA", "808524607"), Map.entry("SCHB", "808524102"), Map.entry("SCHH", "808524847"),
                    Map.entry("VB", "922908751"), Map.entry("VBIL", "922040845"), Map.entry("VGK", "922042874"),
                    Map.entry("VPL", "922042866"), Map.entry("VTI", "922908769"), Map.entry("VTV", "922908744"),
                    Map.entry("VWO", "922042858"), Map.entry("Cash&Other", "Cash&Other"));
    private static final List<String> BOND_FUNDS = List.of("AGG", "BND", "SCHP");

    public record Holdings(LocalDate date, Map<String, BigDecimal> bonds, BigDecimal fundPercent)
    {
        public Holdings { bonds = Collections.unmodifiableMap(new LinkedHashMap<>(bonds)); }
    }

    public record Fund(BigDecimal bondPercent, Map<String, BigDecimal> weights, LocalDate date, String source, String note)
    {
        public Fund
        {
            weights = Collections.unmodifiableMap(new LinkedHashMap<>(weights));
            if (bondPercent.signum() <= 0 || bondPercent.compareTo(HUNDRED) > 0 || weights.isEmpty()
                            || weights.values().stream().anyMatch(v -> v.signum() < 0)
                            || weights.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add).signum() <= 0)
                throw new IllegalArgumentException("Composition obligataire sous-jacente invalide.");
        }
    }

    private ElmBondAllocation() { }

    public static boolean matches(Security security)
    {
        String isin = security.getIsin();
        if (ISIN.equals(isin)) return true;
        if (isin != null && !isin.isBlank()) return false;
        return "ELM".equalsIgnoreCase(security.getTickerSymbol())
                        || "elm market navigator etf".equals(BondComposition.normalize(security.getName()));
    }

    public static Holdings parseHoldings(String csv, LocalDate reference) throws IOException
    {
        try (var parser = csv(csv))
        {
            requireHeaders(parser, "Date", "Account", "Stock Ticker", "CUSIP", "Security Name", "Market Value", "Weightings", "Net Assets");
            var seen = new HashSet<String>();
            var bonds = new LinkedHashMap<String, BigDecimal>();
            BigDecimal total = BigDecimal.ZERO, assets = null;
            LocalDate date = null;
            for (var row : parser)
            {
                if (!row.isConsistent()) throw new IOException("Ligne ELM incomplète.");
                var current = LocalDate.parse(row.get("Date"), US_DATE);
                if (date != null && !date.equals(current)) throw new IOException("Dates ELM incohérentes.");
                date = current;
                if (!"ELM".equals(row.get("Account"))) throw new IOException("Compte ELM non confirmé.");
                var nav = number(row.get("Net Assets"));
                if (nav.signum() <= 0 || assets != null && assets.compareTo(nav) != 0) throw new IOException("Actif net ELM incohérent.");
                assets = nav;
                String ticker = row.get("Stock Ticker");
                if (!seen.add(ticker) || !row.get("CUSIP").equals(CUSIPS.get(ticker)))
                    throw new IOException("Sous-jacent ELM inconnu, répété ou identité modifiée : " + ticker + ". Affectations conservées.");
                var value = number(row.get("Market Value"));
                if (value.signum() < 0 && !"Cash&Other".equals(ticker)) throw new IOException("Position ELM négative non prise en charge.");
                var percent = value.multiply(HUNDRED).divide(nav, 18, RoundingMode.HALF_UP);
                if (percent.subtract(number(row.get("Weightings").replace("%", ""))).abs().compareTo(new BigDecimal("0.011")) > 0)
                    throw new IOException("Poids et valeur ELM incohérents : " + ticker);
                total = total.add(value);
                if (BOND_FUNDS.contains(ticker) && value.signum() > 0) bonds.put(ticker, percent);
            }
            // Published holdings and NAV include a small accrual/rounding difference (0.00147% on 2026-10-16).
            if (date == null || assets == null || total.subtract(assets).abs().compareTo(assets.multiply(new BigDecimal("0.00005"))) > 0)
                throw new IOException("Le fichier complet des positions ELM ne reconstitue pas l'actif net.");
            requireRecent(date, reference, 45, "Positions ELM");
            return new Holdings(date, bonds, bonds.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add));
        }
        catch (RuntimeException e) { throw new IOException("Positions ELM illisibles : " + e.getMessage(), e); }
    }

    public static Slice combine(Holdings holdings, Map<String, Fund> funds, Family family)
    {
        var values = new LinkedHashMap<String, BigDecimal>();
        var notes = new ArrayList<String>();
        LocalDate oldest = holdings.date();
        for (var entry : holdings.bonds().entrySet())
        {
            var fund = funds.get(entry.getKey());
            if (fund == null) throw new IllegalArgumentException("Composition absente : " + entry.getKey());
            var total = fund.weights().values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            var exposure = entry.getValue().multiply(fund.bondPercent()).divide(HUNDRED, 18, RoundingMode.HALF_UP);
            fund.weights().forEach((category, weight) -> values.merge(category,
                            exposure.multiply(weight).divide(total, 18, RoundingMode.HALF_UP), BigDecimal::add));
            if (fund.date().isBefore(oldest)) oldest = fund.date();
            notes.add(entry.getKey() + " : " + entry.getValue().setScale(4, RoundingMode.HALF_UP) + " % d'ELM ; obligations "
                            + fund.bondPercent().setScale(4, RoundingMode.HALF_UP) + " % du sous-jacent ; composition " + fund.date()
                            + " ; " + fund.source() + " ; " + fund.note());
        }
        var weights = apportion(values);
        int coverage = weights.values().stream().mapToInt(Integer::intValue).sum();
        String note = "ELM — POSITIONS RÉELLES du " + holdings.date() + " ; " + family.title() + " : "
                        + BigDecimal.valueOf(coverage, 2).toPlainString() + " % du titre ELM affecté. Fonds obligataires avant exclusion du cash interne : "
                        + holdings.fundPercent().setScale(4, RoundingMode.HALF_UP) + " %. Actions, VBIL, FGXXX, Cash & Other et liquidités internes exclus. "
                        + "Le reste d'ELM demeure non affecté dans cette taxonomie. Classes d'actifs et Ajuster ELM (cibles) ne sont pas modifiés.\n"
                        + String.join("\n", notes);
        return new Slice(weights, oldest, ElmAllocation.SOURCE, note, coverage);
    }

    private static Map<String, Integer> apportion(Map<String, BigDecimal> values)
    {
        var weights = new LinkedHashMap<String, Integer>();
        var fractions = new LinkedHashMap<String, BigDecimal>();
        BigDecimal total = BigDecimal.ZERO;
        int assigned = 0;
        for (var e : values.entrySet())
        {
            if (e.getValue().signum() < 0) throw new IllegalArgumentException("Exposition ELM négative.");
            var exact = e.getValue().movePointRight(2);
            int floor = exact.intValue();
            weights.put(e.getKey(), floor); assigned += floor; total = total.add(exact);
            fractions.put(e.getKey(), exact.subtract(BigDecimal.valueOf(floor)));
        }
        int target = total.setScale(0, RoundingMode.HALF_UP).intValueExact();
        if (target > 10000) throw new IllegalArgumentException("Poche ELM supérieure à 100 %.");
        var order = fractions.keySet().stream().sorted(Comparator.<String, BigDecimal>comparing(fractions::get).reversed().thenComparing(k -> k)).toList();
        for (int i = 0; i < target - assigned; i++) weights.merge(order.get(i), 1, Integer::sum);
        weights.values().removeIf(v -> v == 0);
        return weights;
    }

    public static Fund parseSchwab(String csv, Family family, LocalDate reference, boolean fallback) throws IOException
    {
        try (var parser = csv(csv))
        {
            requireHeaders(parser, "As-Of-Date", "Symbol", "Percent of Assets", "Name", "BBG FIGI", "Country", "Maturity Date");
            LocalDate date = null;
            BigDecimal bonds = BigDecimal.ZERO, total = BigDecimal.ZERO;
            var weights = new LinkedHashMap<String, BigDecimal>();
            var ids = new HashSet<String>();
            int count = 0;
            for (var row : parser)
            {
                if (row.size() == 1 && count > 0 && !row.get(0).matches("\\d{4}-\\d{2}-\\d{2}.*")) continue; // Official footer.
                if (!row.isConsistent()) throw new IOException("Export SCHP tronqué.");
                var current = LocalDate.parse(row.get("As-Of-Date"));
                if (date != null && !date.equals(current)) throw new IOException("Dates SCHP incohérentes.");
                date = current; count++;
                if (!ids.add(row.get("BBG FIGI"))) throw new IOException("Ligne SCHP dupliquée.");
                var value = number(row.get("Percent of Assets"));
                if (value.signum() < 0) throw new IOException("Position SCHP négative non prise en charge.");
                total = total.add(value);
                if (!"TIPS".equals(row.get("Symbol")))
                {
                    if (!row.get("Name").contains("GOVERNMENT MM")) throw new IOException("Sous-jacent SCHP inconnu.");
                    continue;
                }
                if (!"US".equals(row.get("Country")) || !row.get("Name").contains("TREASURY (CPI)")) throw new IOException("Identité TIPS non confirmée.");
                bonds = bonds.add(value);
                String category = switch (family)
                {
                    case RATING -> "AA";
                    case REGIONS -> "États-Unis";
                    case ISSUER -> "Gouvernement";
                    case MATURITY -> maturity(row.get("Maturity Date"), reference, US_DATE);
                };
                weights.merge(category, value, BigDecimal::add);
            }
            if (date == null || count < 2 || total.subtract(HUNDRED).abs().compareTo(new BigDecimal("0.02")) > 0)
                throw new IOException("Positions SCHP incomplètes.");
            requireRecent(date, reference, fallback ? 45 : 90, "Export SCHP");
            return new Fund(bonds, weights, date, SCHP, (fallback ? "COPIE OFFICIELLE EMBARQUÉE ; lecture réseau indisponible ; validité maximale 45 jours. " : "Export officiel relu en ligne. ")
                            + "Cash interne exclu ; " + count + " lignes vérifiées. " + (family == Family.RATING ? sovereignNote() : ""));
        }
        catch (RuntimeException e) { throw new IOException("Export SCHP illisible : " + e.getMessage(), e); }
    }

    public static Fund parseVanguard(String holdingsJson, String details, Family family, LocalDate reference) throws IOException
    {
        try
        {
            var document = JsonParser.parseString(holdingsJson).getAsJsonObject();
            LocalDate date = LocalDate.parse(document.get("latestEffectiveDate").getAsString());
            requireRecent(date, reference, 90, "Positions BND");
            var holdings = document.getAsJsonObject(date.toString()).getAsJsonArray("fixedIncome");
            if (holdings.isEmpty()) throw new IOException("Positions obligataires BND absentes.");
            BigDecimal bonds = BigDecimal.ZERO, treasury = BigDecimal.ZERO;
            var weights = new LinkedHashMap<String, BigDecimal>();
            for (var item : holdings)
            {
                var row = item.getAsJsonObject();
                var percent = optionalNumber(row.get("percentOfFunds"));
                bonds = bonds.add(percent);
                if (percent.signum() <= 0) continue;
                String name = row.get("holdingName").getAsString();
                if (name.equals("United States Treasury Note/Bond")) treasury = treasury.add(percent);
                if (family == Family.MATURITY)
                {
                    // TBA delivery dates are settlement dates, not the underlying mortgage maturity.
                    String category = name.equals("Fannie Mae or Freddie Mac") ? "Non déterminée"
                                    : maturity(row.get("finalMaturityDate").getAsString(), reference, DateTimeFormatter.ISO_LOCAL_DATE);
                    weights.merge(category, percent, BigDecimal::add);
                }
            }
            String note = "Positions obligataires nettes ; liquidités internes exclues ; ventilation des expositions positives normalisée ; couvertures négatives exclues de la ventilation. ";
            if (family == Family.RATING)
            {
                for (var item : JsonParser.parseString(details).getAsJsonArray())
                {
                    var row = item.getAsJsonObject();
                    String label = row.get("qualityName").getAsString();
                    String category = switch (label)
                    {
                        case "AAA", "AA", "A", "BBB", "BB", "B" -> label;
                        case "CCC or Lower" -> "CCC et inférieur";
                        case "NR" -> "Non noté";
                        case "U.S. Government" -> "AA";
                        default -> throw new IOException("Notation Vanguard inconnue : " + label);
                    };
                    var value = row.get("value").getAsBigDecimal();
                    if (value.signum() > 0) weights.merge(category, value, BigDecimal::add);
                }
                requireTotal(weights, "Notations BND"); note += sovereignNote();
            }
            else if (family == Family.ISSUER)
            {
                var data = JsonParser.parseString(details).getAsJsonObject();
                if (!date.toString().equals(data.get("effectiveDate").getAsString())) throw new IOException("Dates émetteurs/positions BND incohérentes.");
                for (var item : data.getAsJsonArray("issuerExposures"))
                {
                    var row = item.getAsJsonObject();
                    String label = row.get("sectorName").getAsString();
                    var value = row.get("value").getAsBigDecimal();
                    if (label.equals("Treasury / Agency"))
                    {
                        if (treasury.signum() <= 0 || treasury.compareTo(value) > 0) throw new IOException("Ventilation Trésor/agences BND incohérente.");
                        weights.merge("Gouvernement", treasury, BigDecimal::add);
                        weights.merge("Agences publiques & supranationaux", value.subtract(treasury), BigDecimal::add);
                        continue;
                    }
                    String category = switch (label)
                    {
                        case "CMBS", "Government Mortgage-Backed", "Asset Backed" -> "Crédit titrisé";
                        case "Finance" -> "Banques & institutions financières";
                        case "Industrial", "Utilities" -> "Entreprises";
                        case "Foreign", "Other" -> "Autres";
                        default -> throw new IOException("Émetteur BND inconnu : " + label);
                    };
                    weights.merge(category, value, BigDecimal::add);
                }
                requireTotal(weights, "Émetteurs BND");
                note += "Trésor isolé par les positions ; solde Treasury / Agency classé agences ; Foreign non ventilé et Other classés Autres.";
            }
            else if (family == Family.REGIONS)
            {
                var data = JsonParser.parseString(details).getAsJsonObject().getAsJsonArray("fundMarketRegion");
                JsonObject latest = null;
                for (var item : data)
                {
                    var row = item.getAsJsonObject();
                    var current = LocalDate.parse(row.get("effectiveDate").getAsString());
                    if (!current.isAfter(reference) && (latest == null || current.isAfter(LocalDate.parse(latest.get("effectiveDate").getAsString())))) latest = row;
                }
                if (latest == null) throw new IOException("Régions BND absentes.");
                date = LocalDate.parse(latest.get("effectiveDate").getAsString());
                requireRecent(date, reference, 90, "Régions BND");
                for (var item : latest.getAsJsonArray("marketRegionExposures"))
                {
                    var row = item.getAsJsonObject();
                    String category = switch (row.get("regionName").getAsString())
                    {
                        case "North America" -> "Amérique du Nord";
                        case "Europe" -> "Europe";
                        case "Pacific" -> "Asie-Pacifique";
                        case "Emerging Markets" -> "Marchés émergents";
                        case "Middle East", "Other" -> "Autres";
                        default -> throw new IOException("Région BND inconnue.");
                    };
                    weights.merge(category, row.get("value").getAsBigDecimal(), BigDecimal::add);
                }
                requireTotal(weights, "Régions BND");
                note += "Régions publiées uniquement : aucun pays n'est déduit de la devise USD ; Moyen-Orient conservé dans Autres.";
            }
            else note += "Échéances finales, jamais duration ; TBA à date de livraison classés Non déterminée.";
            return new Fund(bonds, weights, date, BND, note);
        }
        catch (RuntimeException e) { throw new IOException("Composition BND illisible : " + e.getMessage(), e); }
    }

    public static Fund parseIshares(String csv, String html, Family family, LocalDate reference) throws IOException
    {
        int header = csv.indexOf("Name,Sector,Asset Class,");
        if (header < 0 || !csv.startsWith("iShares Core U.S. Aggregate Bond ETF")) throw new IOException("Identité ou en-tête AGG absent.");
        LocalDate date;
        try (var preamble = CSVParser.parse(csv.substring(0, header), CSVFormat.DEFAULT))
        {
            var dates = preamble.getRecords().stream().filter(r -> r.size() == 2 && "Fund Holdings as of".equals(r.get(0))).toList();
            if (dates.size() != 1) throw new IOException("Date AGG absente ou ambiguë.");
            date = LocalDate.parse(dates.getFirst().get(1), ISHARES_DATE);
        }
        requireRecent(date, reference, 90, "Positions AGG");
        try (var parser = csv(csv.substring(header)))
        {
            requireHeaders(parser, "Name", "Sector", "Asset Class", "Market Value", "Location", "Maturity");
            BigDecimal total = BigDecimal.ZERO, bonds = BigDecimal.ZERO;
            var weights = new LinkedHashMap<String, BigDecimal>();
            int rows = 0;
            for (var row : parser)
            {
                if (row.size() == 1 && rows > 0) continue; // Official legal footer, if present.
                if (!row.isConsistent()) throw new IOException("Export AGG tronqué.");
                rows++;
                var value = number(row.get("Market Value")); total = total.add(value);
                if (!row.get("Asset Class").equals("Fixed Income"))
                {
                    if (!List.of("Money Market", "Cash", "Cash Collateral and Margins").contains(row.get("Asset Class"))) throw new IOException("Classe AGG inconnue.");
                    continue;
                }
                bonds = bonds.add(value);
                if (value.signum() <= 0 || family == Family.RATING) continue;
                String category = switch (family)
                {
                    case MATURITY -> maturity(row.get("Maturity"), reference, ISHARES_DATE);
                    case REGIONS -> BondAllocationSources.region(row.get("Location"));
                    case ISSUER -> switch (row.get("Sector"))
                    {
                        case "Treasury", "Sovereign", "Local Authority" -> "Gouvernement";
                        case "Agency", "Supranational" -> "Agences publiques & supranationaux";
                        case "Financial Institutions" -> "Banques & institutions financières";
                        case "Industrial", "Utility" -> "Entreprises";
                        case "MBS Pass-Through", "CMBS", "ABS" -> "Crédit titrisé";
                        default -> throw new IOException("Secteur AGG inconnu : " + row.get("Sector"));
                    };
                    default -> throw new IOException("Famille AGG inconnue.");
                };
                weights.merge(category, value, BigDecimal::add);
            }
            if (rows < 2 || total.signum() <= 0) throw new IOException("Positions AGG vides.");
            String note = "Export complet : " + rows + " lignes ; cash, fonds monétaires et collatéral exclus ; expositions obligataires positives normalisées.";
            if (family == Family.RATING)
            {
                var block = Jsoup.parse(html).select("[data-id=exposureBreakdowns-rating]");
                if (block.size() != 1) throw new IOException("Table de notation AGG absente ou ambiguë.");
                var asof = block.select("[webqc-datapoint=exposureBreakdowns-rating-asOf]").text();
                int pos = asof.indexOf("as of ");
                if (pos < 0) throw new IOException("Date des notations AGG absente.");
                date = LocalDate.parse(asof.substring(pos + 6), ISHARES_DATE);
                requireRecent(date, reference, 90, "Notations AGG");
                BigDecimal ratingTotal = BigDecimal.ZERO;
                for (var row : block.select("tbody tr"))
                {
                    var cells = row.select("td");
                    if (cells.size() != 2) throw new IOException("Ligne de notation AGG invalide.");
                    String label = cells.get(0).text();
                    var value = number(cells.get(1).text().replace("%", "")); ratingTotal = ratingTotal.add(value);
                    if (label.equals("Cash and/or Derivatives")) continue;
                    String category = label.equals("Not Rated") ? "Non noté" : label.replace(" Rated", "");
                    if (!List.of("AAA", "AA", "A", "BBB", "BB", "B", "Non noté").contains(category)) throw new IOException("Notation AGG inconnue.");
                    if (value.signum() > 0) weights.merge(category, value, BigDecimal::add);
                }
                if (ratingTotal.subtract(HUNDRED).abs().compareTo(new BigDecimal("0.10")) > 0) throw new IOException("Notations AGG incomplètes.");
                note += " Notations de la table du portefeuille iShares ; date distincte des positions signalée.";
            }
            return new Fund(bonds.multiply(HUNDRED).divide(total, 16, RoundingMode.HALF_UP), weights, date, AGG, note);
        }
        catch (RuntimeException e) { throw new IOException("Composition AGG illisible : " + e.getMessage(), e); }
    }

    public static void requireRecent(LocalDate date, LocalDate reference, int days, String label) throws IOException
    {
        if (date.isAfter(reference) || date.isBefore(reference.minusDays(days))) throw new IOException(label + " : date future ou antérieure à " + days + " jours (" + date + ").");
    }

    private static String sovereignNote()
    {
        return "U.S. Government/TIPS → note souveraine US Aa1 (Moody's, action du 16/05/2025, vérifiée le 16/10/2026) → AA, selon votre règle ; profil daté, non relu automatiquement. " + US_RATING_SOURCE;
    }

    private static String maturity(String value, LocalDate reference, DateTimeFormatter format)
    {
        if (value == null || value.isBlank() || "-".equals(value)) return "Non déterminée";
        var date = LocalDate.parse(value, format);
        if (!date.isAfter(reference)) return "Non déterminée"; // A past settlement date is never cash or a new maturity.
        return BondAllocationSources.maturityBucket(date, reference, false);
    }

    private static void requireTotal(Map<String, BigDecimal> values, String label) throws IOException
    {
        var total = values.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.subtract(HUNDRED).abs().compareTo(new BigDecimal("0.10")) > 0) throw new IOException(label + " : table incomplète.");
    }

    private static BigDecimal optionalNumber(JsonElement value)
    {
        return value == null || value.isJsonNull() || value.getAsString().isBlank() ? BigDecimal.ZERO : value.getAsBigDecimal();
    }

    private static BigDecimal number(String value) { return new BigDecimal(value.strip().replace(",", "")); }

    private static CSVParser csv(String value) throws IOException
    {
        return CSVParser.parse(value.replace("\ufeff", ""), CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build());
    }

    private static void requireHeaders(CSVParser parser, String... names) throws IOException
    {
        if (new HashSet<>(parser.getHeaderNames()).size() != parser.getHeaderNames().size()) throw new IOException("Colonnes CSV dupliquées.");
        for (String name : names) if (!parser.getHeaderMap().containsKey(name)) throw new IOException("Colonne absente : " + name);
    }
}
