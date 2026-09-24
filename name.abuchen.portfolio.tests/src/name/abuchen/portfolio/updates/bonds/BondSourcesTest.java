package name.abuchen.portfolio.updates.bonds;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.Test;

public class BondSourcesTest
{
    static byte[] xlsx(String rows) throws IOException
    {
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out))
        {
            zip.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));
            zip.write(("<worksheet><sheetData>" + rows + "</sheetData></worksheet>").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return out.toByteArray();
    }

    @Test
    public void readsLegacyBinaryXlsWithoutOfficeInstallation() throws Exception
    {
        try (var input = getClass().getResourceAsStream("synthetic-aft.xls"))
        {
            var sheets = BondWorkbook.read(input.readAllBytes());
            assertEquals("1.23456", sheets.getFirst().get(5).get("C"));
            assertEquals(BondWorkbook.date("46287"), BondWorkbook.date(sheets.getFirst().get(5).get("A")));
        }
    }

    @Test
    public void choosesLatestAftPublicationDespiteOldHiddenDownload() throws Exception
    {
        String page = "https://www.aft.gouv.fr/fr/oati-principaux-chiffres";
        String html = "<a href='/files/2024-08_coef_oati_oct24.xls'>Old</a>"
                        + "<a href='/files/2026-09_coef_oati-novembre26.xls'>Current</a>"
                        + "<a href='/files/coef_oati_histo_1998_2016.xls'>Archive</a>";
        assertEquals("https://www.aft.gouv.fr/files/2026-09_coef_oati-novembre26.xls", BondSources.coefficientFile(html, page, false));
        assertThrows(IOException.class, () -> BondSources.coefficientFile(html, page, true));
    }

    @Test
    public void csvSkipsMissingButRejectsContradictoryAndMalformedPrices()
    {
        var data = BondSources.csv("\ufeffHeader;value\n2026-09-18;101,25;\n2026-09-19;.;\n2026-09-20;;\n");
        assertEquals(Map.of(LocalDate.parse("2026-09-18"), new BigDecimal("101.25")), data);
        assertEquals(data, BondSources.csv("Header,value\n2026-09-18,101.25,\n2026-09-19,.,\n"));
        assertThrows(IllegalArgumentException.class, () -> BondSources.csv("2026-09-18;1\n2026-09-18;2"));
        assertThrows(IllegalArgumentException.class, () -> BondSources.csv("2026-09-18;abc"));
    }

    @Test
    public void borsaUsesOfficialSettlementNotIntraday()
    {
        var history = BondSources.borsaHistory("{\"history\":{\"historyDt\":[{\"dt\":\"20260918\",\"setPx\":98.5,\"lastPx\":300},"
                        + "{\"dt\":\"20260919\",\"setPx\":null}]}}");
        assertEquals(new BigDecimal("98.5"), history.get(LocalDate.parse("2026-09-18")));
        assertEquals(1, history.size());
        assertThrows(IOException.class, () -> BondSources.supplementOfficial(history, "maintenance"));
    }

    @Test
    public void workbookReadsAftCachedFormulaValuesAndMaturityColumns() throws Exception
    {
        var maturity = LocalDate.parse("2029-07-25");
        long serial = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.of(1899, 12, 30), maturity);
        var bytes = xlsx("<row r=\"4\"><c r=\"C4\"><v>" + serial + "</v></c></row>"
                        + "<row r=\"5\"><c r=\"A5\"><v>46287</v></c><c r=\"C5\"><f>1+1</f><v>1.23456</v></c></row>");
        var coefficients = BondSources.coefficients(bytes, maturity);
        assertEquals(new BigDecimal("1.23456"), coefficients.get(BondWorkbook.date("46287")));
        assertThrows(IOException.class, () -> BondSources.coefficients(bytes, maturity.plusYears(1)));
    }

    @Test
    public void parsesBothBundesbankWorkbookLayouts() throws Exception
    {
        var spec = BondSpec.find("DE0001102622").orElseThrow();
        var old = xlsx("<row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>Bundeswertpapiere vom 18.09.2026</t></is></c></row>"
                        + "<row r=\"5\"><c r=\"A5\" t=\"inlineStr\"><is><t>DE000</t></is></c><c r=\"B5\"><v>110262</v></c>"
                        + "<c r=\"C5\"><v>2</v></c><c r=\"I5\"><v>99.1</v></c><c r=\"K5\"><v>100.2</v></c></row>");
        var modern = xlsx("<row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>Bundeswertpapiere vom 18.09.26</t></is></c></row>"
                        + "<row r=\"5\"><c r=\"A5\" t=\"inlineStr\"><is><t>DE0001102622</t></is></c>"
                        + "<c r=\"G5\"><v>99.1</v></c><c r=\"I5\"><v>100.2</v></c></row>");
        assertEquals(BondSources.germanWorkbook(old, spec), BondSources.germanWorkbook(modern, spec));
        assertEquals(10020000000L, BondSources.germanWorkbook(modern, spec).getFirst().value());
    }

    @Test
    public void indexHistoryUsesContiguousWindowAndRequiresLatestSettlementCoefficient() throws Exception
    {
        var spec = BondSpec.find("FR0013410552").orElseThrow();
        var coefficients = new TreeMap<LocalDate, BigDecimal>();
        coefficients.put(LocalDate.parse("2026-01-01"), BigDecimal.ONE);
        for (var date = LocalDate.parse("2026-09-01"); !date.isAfter(LocalDate.parse("2026-09-23")); date = date.plusDays(1))
            coefficients.put(date, new BigDecimal("1.2"));
        var clean = new TreeMap<LocalDate, BigDecimal>();
        clean.put(LocalDate.parse("2026-01-05"), BigDecimal.valueOf(100));
        clean.put(LocalDate.parse("2026-09-18"), BigDecimal.valueOf(99));
        assertEquals(1, BondSources.indexHistory(spec, clean, coefficients, "test").size());
        clean.put(LocalDate.parse("2026-09-23"), BigDecimal.valueOf(99));
        assertThrows(IOException.class, () -> BondSources.indexHistory(spec, clean, coefficients, "test"));
    }

    @Test
    public void cancelledFetchDoesNotStartNetwork() throws Exception
    {
        assertThrows(InterruptedException.class, () -> new BondSources(() -> true, LocalDate.now()).fetch(BondSpec.SUPPORTED.getFirst()));
    }
}
