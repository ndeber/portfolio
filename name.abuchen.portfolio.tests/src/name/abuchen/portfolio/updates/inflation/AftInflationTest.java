package name.abuchen.portfolio.updates.inflation;

import static org.junit.Assert.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.zip.*;
import org.junit.Test;
import name.abuchen.portfolio.model.*;

@SuppressWarnings("nls")
public class AftInflationTest
{
    private final LocalDate today = LocalDate.of(2026, 9, 25);
    private final String url = "https://www.aft.gouv.fr/files/2026_coef_oatei.xlsx";
    private byte[] book(String value, int secondDay) throws IOException
    {
        var out = new ByteArrayOutputStream();
        long day = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.of(1899, 12, 30), today);
        try (var zip = new ZipOutputStream(out))
        {
            zip.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));
            zip.write(("<worksheet><sheetData><row r='1'><c r='A1' t='inlineStr'><is><t>jour / day</t></is></c>"
                            + "<c r='B1' t='inlineStr'><is><t>référence quotidienne / daily inflation reference</t></is></c></row>"
                            + "<row r='10'><c r='A10'><v>" + day + "</v></c><c r='B10'><v>" + value + "</v></c><c r='C10'><v>1.27</v></c></row>"
                            + "<row r='11'><c r='A11'><v>" + (day + secondDay) + "</v></c><c r='B11'><v>103.63</v></c></row>"
                            + "</sheetData></worksheet>").getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }
    @Test public void readsDailyReferenceNotBondCoefficientAndAllowsPublishedFuture() throws Exception
    {
        var points = AftInflation.parse(book("103.62000000000001", 1), today);
        assertEquals(10362000000L, points.getFirst().value());
        assertEquals(today.plusDays(1), points.getLast().date());
        assertThrows(IOException.class, () -> AftInflation.parse(book("0", 1), today));
        assertThrows(IOException.class, () -> AftInflation.parse(book("103.62", 2), today));
        assertThrows(IOException.class, () -> AftInflation.parse(book("103.62", 0), today));
        assertThrows(IOException.class, () -> AftInflation.parse(book("", 1), today));
    }
    @Test public void discoversCurrentOfficialWorkbookAndRejectsNewBaseOrAmbiguity() throws Exception
    {
        String html = "<p>base 2025 = 100</p><a href='/files/2026_coef_oatei.xlsx'>Télécharger</a>";
        assertEquals(url, AftInflation.workbookLink(html));
        assertThrows(IOException.class, () -> AftInflation.workbookLink(html.replace("2025", "2030")));
        assertThrows(IOException.class, () -> AftInflation.workbookLink(html + "<a href='/files/other_coef_oatei.xlsx'>Other</a>"));
    }
    @Test public void appliesOnlySelectedIndexAndRejectsChangedPreviewOrBase() throws Exception
    {
        var client = new Client(); var index = new Security(); index.setName(AftInflation.NAME); index.setCurrencyCode(null); index.setFeed("MANUAL"); client.addSecurity(index);
        var other = new Security(); other.setName("EU IPCH - Indice pour OATi€"); client.addSecurity(other);
        other.addPrice(new SecurityPrice(today, 123));
        index.addPrice(new SecurityPrice(today, 10361000000L));
        var download = new AftInflation.Download(url, AftInflation.parse(book("103.62", 1), today));
        var plan = AftInflation.prepare(index, download);
        assertEquals(1, plan.added()); assertEquals(1, plan.corrected());
        assertEquals(10361000000L, index.getPrices().getFirst().getValue());
        index.setNote("changed"); assertThrows(IllegalArgumentException.class, () -> AftInflation.apply(client, plan));
        AftInflation.apply(client, AftInflation.prepare(index, download));
        assertEquals(2, index.getPrices().size()); assertEquals(10362000000L, index.getPrices().getFirst().getValue());
        assertEquals(123, other.getPrices().getFirst().getValue()); assertNull(index.getCurrencyCode());
        assertEquals(0, AftInflation.prepare(index, download).added());
        assertEquals(0, AftInflation.prepare(index, download).corrected());
        index.getPrices().getFirst().setValue(14000000000L);
        assertThrows(IllegalArgumentException.class, () -> AftInflation.prepare(index, download));
    }
    @Test public void neverSelectsOtherNamedIndexOrAmbiguousNames()
    {
        var client = new Client(); var other = new Security(); other.setName("EU IPCH - Indice pour OATi€"); client.addSecurity(other);
        assertThrows(IllegalArgumentException.class, () -> AftInflation.security(client));
        for (int i=0;i<2;i++) { var s=new Security(); s.setName(AftInflation.NAME);client.addSecurity(s); }
        assertThrows(IllegalArgumentException.class, () -> AftInflation.security(client));
    }
}
