package name.abuchen.portfolio.updates.yields;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import name.abuchen.portfolio.model.*;
import name.abuchen.portfolio.datatransfer.pdf.PDFInputFile;
import name.abuchen.portfolio.updates.bonds.*;

public final class YieldSources
{
    public record Fund(String url, String expression, String dateExpression, boolean net, Double defaultFees, boolean ytw)
    {
        public Fund(String url, String expression, String dateExpression, boolean net, Double defaultFees)
        { this(url, expression, dateExpression, net, defaultFees, false); }
    }
    public static final Map<String, Fund> FUNDS = Map.ofEntries(
        Map.entry("FR001400S425", new Fund("https://storage.googleapis.com/amiral-3924b.appspot.com/amiral/website/sextant_regatta_2031_FR001400S425_monthly_fr.pdf", "Rendement à maturité \\(YTM\\)\\s*\\*?\\s*([-\\d.,]+)%", "Amiral Gestion au (\\d{2}/\\d{2}/\\d{4})", false, null)),
        Map.entry("LU1165644672", new Fund("https://www.ivocapital.com/wp-content/docs/Reporting_IVO_EMCD_LU3094324954_EN_R-USD%20Share.pdf", "portfolio offered a yield to worst of ([-\\d.,]+)% in EUR", "Factsheet - (\\d{1,2} [A-Za-z]+ \\d{4})", false, null, true)),
        Map.entry("IT0005554982", new Fund("https://www.borsaitaliana.it/borsa/obbligazioni/mot/cct/scheda/IT0005554982-MOTX.html?lang=it", "Rendimento effettivo a scadenza lordo\\s+([-\\d.,]+)", "Data di riferimento\\s+(\\d{2}/\\d{2}/\\d{4})", false, 0d)),
        Map.entry("FR001400TS84", new Fund("https://montpensier-arbevel.com/wp-content/uploads/fonds/pluvalca-credit-opportunities-2031/pluvalca-credit-opportunities-2031-i_focus.pdf", "Rendement brut du portefeuille à maturité\\s+([-\\d.,]+)%", "PLUVALCA CREDIT OPPORTUNITIES 2031\\s*\\|\\s*(\\d{1,2} \\p{L}+ \\d{4})", false, null)),
        Map.entry("LU1041599405", new Fund("https://am.jpmorgan.com/content/dam/jpm-am-aem/asiapacific/sg/en/literature/fact-sheet/factsheet-jpmorgan-income-fund.pdf", "Yield to maturity \\(%\\)\\s*([-\\d.,]+)", "FACT SHEET \\| ([A-Za-z]+ \\d{1,2}, \\d{4})", false, null)),
        Map.entry("FR001400UG93", new Fund("https://www.arkea-am.com/docs/web/fr/rg_FR001400UG93.pdf", "Rendement à Maturité \\(EUR\\)\\s+([-\\d.,]+)%", "^(\\d{1,2} \\p{L}+ \\d{4})", false, null)),
        Map.entry("IE00BG47KH54", new Fund("https://www.vanguard.co.uk/professional/product/etf/bond/9443/global-aggregate-bond-ucits-etf-hedged-accumulating", "YTM \\(Yield to Maturity\\) effective\\s+([-\\d.,]+)%", "YTM \\(Yield to Maturity\\) effective.{0,100}?([0-9]{1,2} [A-Za-z]+ 20[0-9]{2})", false, null)),
        Map.entry("FR001400U4U9", new Fund("https://www.carmignac.com/FLFPRO_CC31_FR001400U4U9_LU_fr.pdf", "Yield to Maturity \\(EUR\\)\\s*(?:\\(1\\))?\\s*([-\\d.,]+)%", "Reporting mensuel\\s*-\\s*(\\d{2}/\\d{2}/\\d{4})", false, null)),
        Map.entry("FR001400S0P1", new Fund("https://admin.eiffel-ig.com/wp-content/uploads/Reporting-Eiffel-Rendement-2030.pdf", "Rendement actuariel à maturité\\s+([-\\d.,]+)\\s*%", "Rapport mensuel au (\\d{2}/\\d{2}/\\d{4})", false, null)),
        Map.entry("FR001400MCQ6", new Fund("https://en.sycomore-am.com/download/reporting/53/169", "Yield to maturity\\*{0,2}\\s+([-\\d.,]+)%", "Performance as of (\\d{2}\\.\\d{2}\\.\\d{4})", false, null)),
        Map.entry("FR001400UP68", new Fund("https://funds.tikehaucapital.com/docs/newsletters/newsletter_tikehau-2031_R-Acc-EUR_FR", "rendement à maturité s[’']établit à\\s+([-\\d.,]+)%", "CHIFFRES.CLÉS\\s*[–-]\\s*(\\d{2}/\\d{2}/\\d{4})", false, null)));
    private final BooleanSupplier cancelled;
    private final LocalDate date;
    private final BondSources bonds;
    public YieldSources(BooleanSupplier cancelled, LocalDate date)
    { this.cancelled = cancelled; this.date = date; bonds = new BondSources(cancelled, date); }

    public BondYields.Observation fetch(Client client, Security security) throws IOException, InterruptedException
    {
        var spec = BondSpec.find(security.getIsin());
        if (spec.isPresent())
        {
            var b = spec.get(); var prices = bonds.fetch(b);
            var q = prices.stream().filter(p -> !p.date().isAfter(date)).max(Comparator.comparing(BondQuote::date)).orElseThrow(() -> new IOException("Aucun cours à la date choisie."));
            LocalDate maturity = b.maturity(); double coupon;
            if (b.indexed()) coupon = b.coupon().doubleValue();
            else switch (b.isin())
            {
                case "DE000BU2Z072" -> { maturity = LocalDate.of(2036, 8, 15); coupon = .03; }
                case "DE000BU27014" -> { maturity = LocalDate.of(2032, 11, 15); coupon = .025; }
                case "DE0001102622" -> { maturity = LocalDate.of(2029, 11, 15); coupon = .021; }
                default -> throw new IOException("Convention de coupon non renseignée.");
            }
            double price = b.indexed() ? q.clean().add(q.accrued()).doubleValue() : q.dirty().doubleValue();
            var settlement = q.settlement();
            if (settlement == null)
            {
                settlement = BondMath.settlement(q.date());
                // Reconstruct accrued interest at T+2 from the clean quote; the
                // Bundesbank dirty series does not carry its settlement date.
                var previous = maturity.withYear(settlement.getYear());
                if (previous.isAfter(settlement)) previous = previous.minusYears(1);
                price = q.clean().doubleValue() + 100 * coupon * java.time.temporal.ChronoUnit.DAYS.between(previous, settlement)
                                / java.time.temporal.ChronoUnit.DAYS.between(previous, previous.plusYears(1));
            }
            double ytm = YieldMath.annualBond(price, coupon, settlement, maturity);
            return new BondYields.Observation(security.getUUID(), q.date(), ytm, 0d, false, b.indexed(), q.source(),
                            b.indexed() ? "YTM réel calculé, annualisation ACT/ACT ; conversion IPCH EU" : "YTM nominal calculé, annualisation ACT/ACT");
        }
        if ("LU0234688595".equals(security.getIsin())) return goldman(client, security);
        var fund = FUNDS.get(security.getIsin());
        if (fund == null) throw new IOException("Source YTM automatique non disponible ; renseigner une observation sourcée.");
        String text = text(fund.url());
        Double fees = BondYields.direct(security) ? Double.valueOf(0) : BondYields.attribute(client, security, "Frais");
        if ("IE00BG47KH54".equals(security.getIsin()) && (fees == null || fees == 0))
        {
            var ocf = Pattern.compile("OCF/TER\\s+([\\d.,]+)%").matcher(text);
            fees = ocf.find() ? Double.parseDouble(ocf.group(1).replace(',', '.')) / 100 : null;
        }
        if (fees == null) fees = fund.defaultFees();
        return parse(security, text, fund, fees, date);
    }
    public static BondYields.Observation parse(Security security, String text, Fund fund, Double fees, LocalDate date) throws IOException
    {
        if (!text.contains(security.getIsin())) throw new IOException("ISIN de la part absent du document.");
        String flat = text.replace('\u00a0', ' ').replaceAll("\\s+", " ");
        var rate = Pattern.compile(fund.expression(), Pattern.CASE_INSENSITIVE).matcher(flat);
        var when = Pattern.compile(fund.dateExpression(), Pattern.CASE_INSENSITIVE).matcher(flat);
        if (!rate.find() || !when.find()) throw new IOException("YTM ou date non identifiables dans la source.");
        double value = Double.parseDouble(rate.group(1).replace(',', '.')) / 100;
        String d = when.group(1); LocalDate asOf;
        try { asOf = parseDate(d); }
        catch (RuntimeException e) { throw new IOException("Date de publication illisible : " + d, e); }
        if (asOf.isAfter(date)) throw new IOException("Publication postérieure à la date choisie.");
        if (asOf.isBefore(date.minusDays(62))) throw new IOException("Publication trop ancienne (" + asOf + ") : observation précédente conservée.");
        return new BondYields.Observation(security.getUUID(), asOf, value, fees, fund.net(), false, fund.url(), (fund.ytw() ? "YTW utilisé à défaut de YTM — " : "YTM publié — ") + security.getCurrencyCode() + "; frais annuels du portefeuille ou de la source" + ("FR001400S425".equals(security.getIsin()) ? "; rendement sur la partie investie" : ""));
    }
    public static LocalDate parseDate(String value)
    {
        String d = value.strip().replace("Sept", "Sep");
        for (var locale : List.of(Locale.ENGLISH, Locale.FRENCH))
            for (String format : List.of("dd/MM/uuuu", "dd.MM.uuuu", "d MMM uuuu", "d MMMM uuuu", "MMMM d, uuuu"))
                try { return LocalDate.parse(d, new java.time.format.DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(format).toFormatter(locale)); }
                catch (java.time.format.DateTimeParseException e) { /* try next explicit format */ }
        throw new IllegalArgumentException("Date illisible : " + value);
    }
    private BondYields.Observation goldman(Client client, Security security) throws IOException, InterruptedException
    {
        String body = """
            {"documentType_s":[],"documentCategory_s":[],"audience_s":["institutions"],"language_s":["en"],
            "country_s":["dk"],"documentSource_s":[],"pvNumber_s":["PV100313"],"latest":true,"startIndex":0,"count":100}
            """;
        var request = HttpRequest.newBuilder(URI.create("https://am.gs.com/api/documents")).timeout(Duration.ofSeconds(25))
                        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        var documents = com.google.gson.JsonParser.parseString(new String(bytes(request), StandardCharsets.UTF_8)).getAsJsonArray();
        LocalDate latest = null; String id = null;
        for (var element : documents)
        {
            var fields = new HashMap<String, List<String>>();
            for (var field : element.getAsJsonObject().getAsJsonArray("LexFields"))
            {
                var f = field.getAsJsonObject(); var values = new ArrayList<String>();
                f.getAsJsonArray("values").forEach(v -> values.add(v.getAsString())); fields.put(f.get("name").getAsString(), values);
            }
            if (!fields.getOrDefault("isin_s", List.of()).contains(security.getIsin())
                            || !fields.getOrDefault("documentType_s", List.of()).contains("monthlyFundUpdate")) continue;
            var when = LocalDate.parse(fields.get("displayDateTime_t").getFirst().substring(0, 10));
            if (!when.isAfter(date) && (latest == null || when.isAfter(latest)))
            { latest = when; id = fields.get("documentId_s").getFirst(); }
        }
        if (latest == null || id == null || !id.matches("[a-zA-Z0-9-]+")) throw new IOException("Reporting Goldman Sachs daté introuvable.");
        String url = "https://am.gs.com/public-assets/documents/" + id;
        String content = "Date source : " + latest.format(DateTimeFormatter.ofPattern("dd/MM/uuuu")) + " " + text(url);
        var fee = Pattern.compile("Ongoing Charges \\(%\\)(?:\\(1\\))?\\s+([\\d.,]+)").matcher(content);
        Double annualFees = BondYields.attribute(client, security, "Frais");
        if (annualFees == null && fee.find()) annualFees = Double.parseDouble(fee.group(1).replace(',', '.')) / 100;
        return parse(security, content, new Fund(url, "Yield To Maturity of Portfolio \\(%\\)\\s+([-\\d.,]+)",
                        "Date source : (\\d{2}/\\d{2}/\\d{4})", false, null), annualFees, date);
    }
    private byte[] bytes(HttpRequest request) throws IOException, InterruptedException
    {
        if (cancelled.getAsBoolean()) throw new InterruptedException();
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(12)).followRedirects(HttpClient.Redirect.NORMAL).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        byte[] data = response.body();
        if (cancelled.getAsBoolean()) throw new InterruptedException();
        if (response.statusCode() != 200 || data.length > 20_000_000) throw new IOException("Source indisponible : HTTP " + response.statusCode());
        return data;
    }

    private String text(String url) throws IOException, InterruptedException
    {
        if (cancelled.getAsBoolean()) throw new InterruptedException();
        var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(25)).header("User-Agent", "PortfolioPerformance/1.0").GET().build();
        byte[] bytes = bytes(request);
        if (bytes.length > 4 && new String(bytes, 0, 4, StandardCharsets.US_ASCII).equals("%PDF"))
        {
            var file = Files.createTempFile("ytm-", ".pdf");
            try { Files.write(file, bytes); var pdf = new PDFInputFile(file.toFile()); pdf.convertPDFtoText(); return pdf.getText(); }
            finally { Files.deleteIfExists(file); }
        }
        return Jsoup.parse(new String(bytes, StandardCharsets.UTF_8)).text();
    }
}
