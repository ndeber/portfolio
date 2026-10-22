package name.abuchen.portfolio.updates.bonds;

import static org.junit.Assert.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;

import name.abuchen.portfolio.model.AttributeType;
import name.abuchen.portfolio.model.Security;

public class BondSourcesRecoveryTest
{
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 22);
    private static final BondSpec BOND = BondSpec.find("FR0013327491").orElseThrow();
    private static final String PAGE = "https://www.aft.gouv.fr/fr/oateuroi-principaux-chiffres";
    private static final String FILE = "https://www.aft.gouv.fr/files/medias-aft/3_Dette/3.3_OATEi/2026-09_coef_oatei_novembre_2026.xlsx";
    private static final String OLDER = "https://www.aft.gouv.fr/files/2026-08_coef_oatei_octobre_2026.xls";
    private static final String FRANCE = "https://www.aft.gouv.fr/files/2026-09_coef_oati-novembre26.xls";

    private static final class Download implements BondSources.Downloader
    {
        private final Map<String, byte[]> responses = new HashMap<>();
        private final Map<String, IOException> failures = new HashMap<>();
        private final List<String> requests = new ArrayList<>();

        @Override
        public byte[] request(String url, String token, String referer) throws IOException
        {
            requests.add(url);
            if (failures.containsKey(url))
                throw failures.get(url);
            var response = responses.get(url);
            if (response == null)
                throw new AssertionError("Unexpected request: " + url);
            return response;
        }

        void text(String url, String value)
        {
            responses.put(url, value.getBytes(StandardCharsets.UTF_8));
        }

        long count(String url)
        {
            return requests.stream().filter(url::equals).count();
        }
    }

    private static byte[] workbook(LocalDate lastCoefficient) throws IOException
    {
        long maturity = ChronoUnit.DAYS.between(LocalDate.of(1899, 12, 30), BOND.maturity());
        long date = ChronoUnit.DAYS.between(LocalDate.of(1899, 12, 30), lastCoefficient);
        return BondSourcesTest.xlsx("<row r=\"4\"><c r=\"C4\"><v>" + maturity + "</v></c></row>"
                        + "<row r=\"5\"><c r=\"A5\"><v>" + date + "</v></c>"
                        + "<c r=\"C5\"><v>1.23456</v></c></row>");
    }

    private static Download downloads(LocalDate lastCoefficient) throws IOException
    {
        var download = new Download();
        download.text(PAGE, "<a href='" + FILE + "'>Coefficients</a>");
        download.responses.put(FILE, workbook(lastCoefficient));
        String chart = "https://grafici.borsaitaliana.it/summary-chart/" + BOND.isin() + "-MOTX?lang=it";
        download.text(chart, "<chart-allinone code='" + BOND.isin()
                        + "' exchcode='XMIL' token='test-token' url='https://grafici.borsaitaliana.it/api/'></chart-allinone>");
        download.text("https://grafici.borsaitaliana.it/api/instruments/" + BOND.isin()
                        + ",XMIL,ISIN/history/period?period=10Y&adjustment=true&add-last-price=true",
                        "{\"history\":{\"historyDt\":[{\"dt\":\"20261021\",\"setPx\":81,\"lastPx\":300}]}}");
        download.text("https://www.borsaitaliana.it/borsa/obbligazioni/mot/euro-obbligazioni/scheda/"
                        + BOND.isin() + "-MOTX.html?lang=it",
                        "Prezzo ufficiale <span class=\"t-text -right\">82,5</span>"
                                        + "Data Pr Ufficiale <span class=\"t-text -right\">21/10/26</span>");
        return download;
    }

    private static BondSources sources(Download download, String fallback) throws IOException
    {
        var sources = new BondSources(() -> false, TODAY, download);
        sources.setAftFallback(true, fallback);
        return sources;
    }

    @Test
    public void healthyDiscoveryWinsOverConfiguredFallback() throws Exception
    {
        var download = downloads(LocalDate.of(2026, 10, 23));
        var result = sources(download, OLDER).fetch(BOND).getFirst();
        assertEquals(new BigDecimal("82.5"), result.clean());
        assertEquals(new BigDecimal("1.23456"), result.coefficient());
        assertEquals(LocalDate.of(2026, 10, 23), result.settlement());
        assertTrue(result.source().contains("Coefficients AFT : " + FILE));
        assertFalse(result.source().contains("secours"));
        assertEquals(1, download.count(PAGE));
        assertEquals(0, download.count(OLDER));
    }

    @Test
    public void server526FallbackPreservesPriceAndCachesSharedPublication() throws Exception
    {
        var healthy = sources(downloads(LocalDate.of(2026, 10, 23)), "").fetch(BOND).getFirst();
        var download = downloads(LocalDate.of(2026, 10, 23));
        download.failures.put(PAGE, new BondSources.SourceException(526, "www.aft.gouv.fr"));
        var sources = sources(download, FILE);
        var fallback = sources.fetch(BOND).getFirst();
        assertEquals(healthy.value(), fallback.value());
        assertEquals(healthy.dirty(), fallback.dirty());
        assertEquals(healthy.accrued(), fallback.accrued());
        assertEquals(healthy.settlement(), fallback.settlement());
        assertTrue(fallback.source().contains("Coefficients AFT : " + FILE));
        assertTrue(fallback.source().contains("fichier officiel de secours"));
        assertEquals(fallback, sources.fetch(BOND).getFirst());
        assertEquals(2, download.count(PAGE));
        assertEquals(1, download.count(FILE));
    }

    @Test
    public void timedOutDiscoveryCanRecoverButMissingSettlementStillFails() throws Exception
    {
        var download = downloads(LocalDate.of(2026, 10, 22));
        download.failures.put(PAGE, new SocketTimeoutException("AFT timeout"));
        var failure = assertThrows(IOException.class, () -> sources(download, FILE).fetch(BOND));
        assertTrue(failure.getMessage().contains("Coefficient AFT manquant"));
        assertTrue(failure.getMessage().contains("2026-10-23"));
        assertEquals(2, download.count(PAGE));
        assertEquals(1, download.count(FILE));
    }

    @Test
    public void outageWithoutFallbackReportsRecoveryActionAndKeepsOriginalCause() throws Exception
    {
        var download = new Download();
        var cause = new BondSources.SourceException(526, "www.aft.gouv.fr");
        download.failures.put(PAGE, cause);
        var failure = assertThrows(IOException.class, () -> sources(download, "").fetch(BOND));
        assertSame(cause, failure.getCause());
        assertTrue(failure.getMessage().contains("Renseignez un lien"));
        assertEquals(List.of(PAGE, PAGE), download.requests);
    }

    @Test
    public void clientErrorsAndAmbiguousSuccessfulPageDoNotUseFallback() throws Exception
    {
        var forbidden = new Download();
        forbidden.failures.put(PAGE, new BondSources.SourceException(403, "www.aft.gouv.fr"));
        assertThrows(IOException.class, () -> sources(forbidden, FILE).fetch(BOND));
        assertEquals(List.of(PAGE), forbidden.requests);

        var ambiguous = new Download();
        ambiguous.text(PAGE, "<a href='" + FILE + "'>First</a><a href='"
                        + FILE.replace("novembre_2026", "novembre_2026_revision") + "'>Second</a>");
        var failure = assertThrows(IOException.class, () -> sources(ambiguous, FILE).fetch(BOND));
        assertTrue(failure.getMessage().contains("Plusieurs fichiers AFT"));
        assertEquals(List.of(PAGE), ambiguous.requests);
    }

    @Test
    public void cancellationDuringRetryDoesNotStartFallbackDownload() throws Exception
    {
        var cancelled = new AtomicBoolean();
        var requests = new ArrayList<String>();
        var sources = new BondSources(cancelled::get, TODAY, (url, token, referer) -> {
            requests.add(url);
            cancelled.set(true);
            throw new BondSources.SourceException(526, "www.aft.gouv.fr");
        });
        sources.setAftFallback(true, FILE);
        assertThrows(InterruptedException.class, () -> sources.fetch(BOND));
        assertEquals(List.of(PAGE), requests);
    }

    @Test
    public void fallbackUrlRequiresOfficialHttpsDatedPublicationAndCorrectFamily() throws Exception
    {
        BondSources.validateAftCoefficientFile(FILE, true);
        BondSources.validateAftCoefficientFile(FRANCE, false);
        BondSources.validateAftCoefficientFile(FILE.replace("www.aft.gouv.fr/", "www.aft.gouv.fr:443/"), true);
        for (String invalid : List.of(FILE.replace("https:", "http:"), FILE.replace("www.aft.gouv.fr", "example.org"),
                        FILE.replace("www.aft.gouv.fr/", "www.aft.gouv.fr:8443/"),
                        FILE.replace("https://", "https://user@"), FILE + "?download=1", FILE + "#fragment",
                        FILE.replace("/files/", "/other/"), FILE.replace("/files/", "/files/../other/"),
                        FILE.replace("/files/", "/files/%2e%2e/other/"), FILE.replace("/files/", "/files/%2e/"),
                        FILE.replace("/files/", "/files/%5cother/"),
                        FILE.replace("2026-09", "2026-13"), FILE.replace("2026-09_", ""), FRANCE, "not a URL"))
            assertThrows(invalid, IOException.class, () -> BondSources.validateAftCoefficientFile(invalid, true));
    }

    private static Security managedSecurity(String isin, String file, boolean attribute)
    {
        var security = new Security("Bond", "EUR");
        security.setIsin(isin);
        security.setFeed("MANUAL");
        String source = "Borsa Italiana\nCoefficients AFT : " + file;
        security.setNote("My note\n[XAPA-BOND-PRICES-BEGIN]\nConvention Portfolio Performance : prix dirty\n"
                        + "Source : " + source + "\n[XAPA-BOND-PRICES-END]");
        if (attribute)
            security.getAttributes().put(new AttributeType("xapa.bond.price-source"), source);
        return security;
    }

    @Test
    public void savedSourcesChooseLatestPublicationAndKeepIndexFamiliesSeparate()
    {
        var older = managedSecurity(BOND.isin(), OLDER, true);
        var current = managedSecurity("FR0014001N38", FILE, false);
        var france = managedSecurity("FR0000186413", FRANCE, true);
        assertEquals(FILE, BondSources.previousAftCoefficientFile(List.of(older, current, france), true));
        assertEquals(FRANCE, BondSources.previousAftCoefficientFile(List.of(older, current, france), false));
        assertEquals(OLDER, BondSources.previousAftCoefficientFile(List.of(older), true));
    }

    @Test
    public void arbitraryNotesUnmanagedTitlesAndInvalidSourcesCannotPrefillFallback()
    {
        var arbitrary = new Security("Free note", "EUR");
        arbitrary.setIsin(BOND.isin());
        arbitrary.setFeed("MANUAL");
        arbitrary.setNote("Coefficients AFT : " + FILE);
        var unmanaged = managedSecurity(BOND.isin(), FILE, true);
        unmanaged.setFeed("YAHOO");
        var outsideAudit = managedSecurity(BOND.isin(), "", false);
        outsideAudit.setNote("Coefficients AFT : " + FILE + "\n" + outsideAudit.getNote());
        var foreign = managedSecurity(BOND.isin(), FILE.replace("www.aft.gouv.fr", "example.org"), true);
        var wrongFamily = managedSecurity(BOND.isin(), FRANCE, true);
        var nominal = managedSecurity("DE0001102622", FILE, true);
        assertEquals("", BondSources.previousAftCoefficientFile(
                        List.of(arbitrary, unmanaged, outsideAudit, foreign, wrongFamily, nominal), true));
    }

    @Test
    public void conflictingLatestSavedPublicationsDoNotChooseAnArbitraryFallback()
    {
        var first = managedSecurity(BOND.isin(), FILE, true);
        var other = managedSecurity("FR0014001N38", FILE.replace("novembre_2026", "novembre_2026_revision"), true);
        var older = managedSecurity(BOND.isin(), OLDER, true);
        assertEquals("", BondSources.previousAftCoefficientFile(List.of(first, other, older), true));
    }
}
