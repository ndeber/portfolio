package name.abuchen.portfolio.factors;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import com.google.gson.JsonParser;
import name.abuchen.portfolio.pdfbox3.PDFBox3Adapter;

/** Reviewed net currency disclosures, deliberately distinct from geographical allocations. */
@SuppressWarnings("nls")
public final class CurrencyReports
{
    public static final String VANGUARD = "https://fund-docs.vanguard.com/etf-annual-report.pdf";
    public static final String IVO = "https://www.h24finance.com/opcvm/573/LU1165644672";
    private CurrencyReports() { }

    public static CurrencyProposals.Evidence reviewed(String isin, LocalDate today)
    {
        if ("LU1165644672".equals(isin))
            return parseIvo("LU1165644672 IVO EM CORPORATE DEBT - PART R CAP EUR Rapport Mensuel - 31 août 2026 "
                            + "Exposition EUR après couverture 99,4% Exposition USD après couverture 0,6%",
                            "https://www.h24finance.com/documents/pdfs/opc.69e62902154968.33165640/rapport_mensuel", today,
                            "Relevé archivé et vérifié le 30/09/2026 ; date de publication conservée.");
        try (var in = CurrencyReports.class.getResourceAsStream("vanguard-currencies.json"))
        {
            if (in == null) throw new IllegalStateException("Référentiel Vanguard absent.");
            var rows = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonArray();
            for (var entry : rows)
            {
                var row = entry.getAsJsonObject();
                if (!isin.equals(row.get("isin").getAsString())) continue;
                var date = LocalDate.parse(row.get("date").getAsString());
                if (date.isAfter(today)) return CurrencyProposals.missing("Tableau daté après la date d’analyse.");
                double nav = row.get("nav").getAsDouble();
                var amounts = new TreeMap<String, Double>();
                row.getAsJsonObject("netAmounts").entrySet().forEach(e -> amounts.put(e.getKey(), e.getValue().getAsDouble()));
                return new CurrencyProposals.Evidence(netAmounts(amounts, nav), "Vanguard · net publié, historique", date,
                                VANGUARD + "#page=" + row.get("page").getAsInt(),
                                row.get("fund").getAsString() + ". Rapport annuel 30/06/2025, colonne Net après contrats de change. "
                                                + "Montants divisés par l’actif net total du même compartiment et à la même date (page "
                                                + row.get("navPage").getAsInt() + "). Devise de base " + row.get("base").getAsString()
                                                + " calculée par solde ; parts capitalisante/distribuante non couvertes du même portefeuille. "
                                                + "CNH distingue le renminbi offshore de CNY. Relevé fixe vérifié le 30/09/2026, "
                                                + "pas une actualisation quotidienne. Données historiques à valider explicitement. "
                                                + "Ce tableau mesure le risque de change financier, pas les devises des revenus des entreprises.", false);
            }
        }
        catch (IOException e) { throw new IllegalStateException(e); }
        return diagnostic(isin);
    }

    static Map<String, Integer> netAmounts(Map<String, Double> amounts, double nav)
    {
        if (!Double.isFinite(nav) || nav <= 0 || amounts.isEmpty()
                        || Math.abs(amounts.values().stream().mapToDouble(Double::doubleValue).sum() - nav) > nav * 1e-9)
            throw new IllegalArgumentException("Actif net ou réconciliation des devises invalide.");
        var fractions = new TreeMap<String, Double>();
        amounts.forEach((key, value) -> fractions.put(key, value / nav));
        return CurrencySources.points(fractions);
    }

    public static CurrencyProposals.Evidence parseIvo(String text, String source, LocalDate today, String note)
    {
        String value = text.replaceAll("\\s+", " ");
        if (!value.contains("LU1165644672") || !value.contains("PART R CAP EUR"))
            throw new IllegalArgumentException("Part IVO R EUR non confirmée.");
        var matcher = Pattern.compile("Rapport Mensuel\\s*[-–]?\\s*(\\d{1,2} [\\p{L}]+ \\d{4})").matcher(value);
        if (!matcher.find()) throw new IllegalArgumentException("Date de reporting IVO absente.");
        var date = LocalDate.parse(matcher.group(1), DateTimeFormatter.ofPattern("d MMMM uuuu", Locale.FRENCH));
        if (date.isAfter(today)) throw new IllegalArgumentException("Reporting IVO futur.");
        var weights = new TreeMap<String, Double>();
        matcher = Pattern.compile("Exposition (EUR|USD) après couverture\\s+(-?\\d+(?:[,.]\\d+)?)\\s*%").matcher(value);
        while (matcher.find())
            if (weights.put(matcher.group(1), Double.parseDouble(matcher.group(2).replace(',', '.')) / 100) != null)
                throw new IllegalArgumentException("Devise IVO dupliquée.");
        if (weights.size() != 2 || Math.abs(weights.values().stream().mapToDouble(Double::doubleValue).sum() - 1) > .000001)
            throw new IllegalArgumentException("Tableau IVO après couverture incomplet.");
        return new CurrencyProposals.Evidence(CurrencySources.points(weights), "IVO · après couverture publié", date, source,
                        "Reporting de la part R Cap EUR, ISIN contrôlé. Deux lignes explicites « Exposition … après couverture ». " + note, false);
    }

    static CurrencyProposals.Evidence fetchIvo(LocalDate today) throws IOException, InterruptedException
    {
        var page = Jsoup.parse(new String(bytes(IVO), StandardCharsets.UTF_8), IVO);
        var links = page.select("a[href]").stream().filter(a -> a.text().strip().equalsIgnoreCase("Rapport Mensuel")).toList();
        if (!page.text().contains("LU1165644672") || links.size() != 1) throw new IOException("Lien du reporting IVO absent ou ambigu.");
        String url = links.getFirst().absUrl("href");
        var uri = URI.create(url);
        if (!"https".equals(uri.getScheme()) || !"www.h24finance.com".equals(uri.getHost()) || !uri.getPath().startsWith("/documents/pdfs/"))
            throw new IOException("Adresse du reporting IVO non reconnue.");
        var path = Files.createTempFile("ivo-currencies-", ".pdf");
        try
        {
            Files.write(path, bytes(url));
            return parseIvo(new PDFBox3Adapter().convertToText(path.toFile()), url, today,
                            "PDF du gérant diffusé par H24 Finance ; lien découvert sur " + IVO + ". Consultation : " + today + ".");
        }
        finally { Files.deleteIfExists(path); }
    }

    private static byte[] bytes(String url) throws IOException, InterruptedException
    {
        try (var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build())
        {
            var response = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
                            .header("User-Agent", "Mozilla/5.0").GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            try (var body = response.body())
            {
                if (response.statusCode() != 200) throw new IOException("Source HTTP " + response.statusCode());
                byte[] data = body.readNBytes(20_000_001);
                if (data.length > 20_000_000) throw new IOException("Document trop volumineux.");
                return data;
            }
        }
    }

    private static CurrencyProposals.Evidence diagnostic(String isin)
    {
        String source = "", note = "Aucun tableau de devises nettes validé pour cette part. Reporting du gérant requis.";
        LocalDate date = null;
        switch (isin)
        {
            case "IE00B1FZS467":
                source = "https://www.ishares.com/uk/individual/en/products/251809/ishares-global-infrastructure-ucits-etf";
                date = LocalDate.of(2026, 9, 28);
                note = "Positions officielles récupérées : devise de chaque titre, mais présence de contrats FX. "
                                + "Leur valeur de marché ne remplace pas les deux jambes notionnelles. Ventilation brute non importée comme exposition nette.";
                break;
            case "FR001400TS84":
                source = "https://montpensier-arbevel.com/wp-content/uploads/fonds/pluvalca-credit-opportunities-2031/pluvalca-credit-opportunities-2031_rapport-semestriel.pdf#page=199";
                date = LocalDate.of(2025, 6, 30);
                note = "Tableau par devise des titres : EUR 89,93 % de l’actif net. Périmètre partiel (hors OPC, trésorerie et autres actifs) ; "
                                + "ni EUR 100 %, ni ventilation nette complète déductible. Tableau conservé comme justificatif, sans affectation.";
                break;
            case "LU0565135745", "LU1941681014", "FR0011408798", "FR0011176627":
                source = "https://www.amundi.com/globaldistributor/dl/doc/monthly-factsheet/" + isin + "/FRA/FRA/INSTITUTIONNEL/AMUNDI";
                note = "Reporting consulté : pas de ventilation nette des devises identifiée. Géographie, devise de part et politique de couverture ne constituent pas une mesure actuelle.";
                break;
            case "LU1983261782":
                source = "https://cdn.janushenderson.com/webdocs/HHFGSC_LU1983261782_MAR191_M_31082026_XX(EN-XU).pdf";
                date = LocalDate.of(2026, 8, 31);
                note = "Fiche de la part A2 USD contrôlée : pays/secteurs, mais pas de tableau net de devises. La fiche du fonds australien homonyme n’est pas applicable.";
                break;
            case "IE00BJCW7490":
                source = "https://www.nomura-asset.co.uk/download/factsheets/Japan_Small_Cap_Equity_Fund_Class_A_JPY_EN.pdf";
                date = LocalDate.of(2026, 8, 31);
                note = "Fiche A JPY consultée, sans ventilation nette des devises. JPY 100 % non déduit du nom ou du pays.";
                break;
            case "FR001400UG93":
                source = "https://www.arkea-am.com/docs/web/fr/rg_FR001400UG93.pdf";
                note = "Reporting de la part P consulté, sans tableau net de devises identifié.";
                break;
            case "FR001400UP68":
                source = "https://www.h24finance.com/files/opcvm/911.pdf";
                date = LocalDate.of(2026, 6, 30);
                note = "Reporting : risque de change indiqué couvert, mais résidu non chiffré. EUR 100 % non proposé comme mesure réelle.";
                break;
            case "FR001400U4U9", "FR001400S0P1":
                note = "Politique de couverture / mandat EUR documentés précédemment, mais pas de mesure nette actuelle : ancienne proposition EUR 100 % retirée.";
                break;
            default:
                break;
        }
        return new CurrencyProposals.Evidence(Map.of(), "À documenter · aucune déduction géographique", date, source,
                        note + " Affectation actuelle conservée ; aucune déduction à partir du pays ou de la devise de cotation.", false);
    }
}
