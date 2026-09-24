package name.abuchen.portfolio.updates.equity;

import static org.junit.Assert.*;
import java.io.IOException;
import java.time.LocalDate;
import org.junit.Test;
import name.abuchen.portfolio.updates.equity.EquityComposition.Family;

public class EquitySourcesTest
{
    @Test public void parsesDatedChartsAndCountryAliases() throws Exception
    {
        String html="<p>Date du portefeuille : 31/08/2026</p>\"id\":\"regional\",\"amChartData\":[{\"name\":\"Etats-Unis\",\"value\":62.4}]";
        var data=EquitySources.parseBoursorama(html,Family.REGIONS,"url");
        assertEquals(LocalDate.of(2026,8,31),data.date());assertEquals("États-Unis",data.items().getFirst().name());
        assertEquals(Integer.valueOf(3760),EquityComposition.weights(data,Family.REGIONS,"Other").get("Other"));
    }
    @Test public void rejectsMissingDatesAndUnknownSyntheticHoldings()
    {
        assertThrows(IOException.class,()->EquitySources.parseBoursorama("not data",Family.REGIONS,"url"));
        String html="Date du portefeuille : 31/08/2026<tr><td class=\"c-table-gauge__cell--header\">MSCI WORLD TRS</td><td data-gauge-current-step=\"80\">";
        assertThrows(IOException.class,()->EquitySources.parseBoursorama(html,Family.HOLDINGS,"url"));
    }
    @Test public void divvyKeepsPublicationDateUnknown() throws Exception
    {
        var data=EquitySources.parseDivvy("\"countryWeightings\":[{\"country\":\"TW\",\"weight\":0.2}]",Family.REGIONS,"url");
        assertNull(data.date());assertFalse(data.staticProfile());assertEquals("Taïwan",data.items().getFirst().name());
    }
    @Test public void incompleteMsciCannotReplaceHoldings()
    {
        assertThrows(IOException.class,()->EquitySources.parseMsci("TOP 10 CONSTITUENTS\nAPPLE 500.00 5.00 Info Tech\nSECTOR WEIGHTS","msci_world","url"));
    }
    @Test public void vanguardAcceptsAbbreviatedAndFullMonthNames() throws Exception
    {
        String table = "Market allocation Country Region Fund Benchmark ";
        for (String country : java.util.List.of("Taiwan", "China", "India", "Brazil", "South Africa", "Saudi Arabia", "Mexico",
                        "United Arab Emirates", "Malaysia", "Thailand", "Turkey", "Greece", "Indonesia", "Chile", "Kuwait"))
            table += country + " Emerging Markets 1.00% ";
        for (String month : java.util.List.of("Aug", "August"))
        {
            var data = EquitySources.parseVanguard(table + "As at 31 " + month + " 2026", "url");
            assertEquals(LocalDate.of(2026, 8, 31), data.date());
            assertEquals(15, data.items().size());
        }
    }
    private String fixture(String name) throws Exception
    {
        try (var input = getClass().getResourceAsStream(name))
        {
            return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
    @Test public void msciMergedColumnsKeepExactNamesAndWeights() throws Exception
    {
        var world = EquitySources.parseMsci(fixture("msci-world-columns.txt"), "msci_world", "url");
        assertEquals(java.util.List.of("NVIDIA", "APPLE", "MICROSOFT CORP", "AMAZON.COM", "ALPHABET A", "BROADCOM",
                        "ALPHABET C", "META PLATFORMS A", "MICRON TECHNOLOGY", "TESLA"), world.items().stream().map(i -> i.name()).toList());
        assertEquals(java.util.List.of("5.56", "5.07", "3.90", "2.74", "2.15", "1.82", "1.69", "1.37", "1.18", "1.13"),
                        world.items().stream().map(i -> i.percent().toPlainString()).toList());
        var em = EquitySources.parseMsci(fixture("msci-em-columns.txt"), "msci_em_ex_egypt", "url");
        assertEquals(java.util.List.of("TAIWAN SEMICONDUCTOR MFG", "SAMSUNG ELECTRONICS CO", "SK HYNIX", "TENCENT HOLDINGS LI (CN)",
                        "ALIBABA GRP HLDG (HK)", "MEDIATEK INC", "SAMSUNG ELECTRONICS PREF", "CHINA CONSTRUCTION BK H", "DELTA ELECTRONICS", "RELIANCE INDUSTRIES"),
                        em.items().stream().map(i -> i.name()).toList());
        assertEquals(java.util.List.of("15.22", "7.16", "5.55", "2.79", "1.94", "1.42", "1.03", "1.01", "1.00", "0.84"),
                        em.items().stream().map(i -> i.percent().toPlainString()).toList());
        assertEquals(Integer.valueOf(7339), name.abuchen.portfolio.updates.equity.EquityComposition.weights(world, Family.HOLDINGS, "Other").get("OTHER"));
    }
    @Test public void unknownMsciColumnsAndIncorrectTotalsFailClosed() throws Exception
    {
        String text = fixture("msci-world-columns.txt");
        assertThrows(IOException.class, () -> EquitySources.parseMsci(text.replace("Constituents NVIDIA", "Unknown statistic NVIDIA"), "msci_world", "url"));
        assertThrows(IOException.class, () -> EquitySources.parseMsci(text.replace("26.61", "30.61"), "msci_world", "url"));
        assertThrows(IOException.class, () -> EquitySources.parseMsci(text.replace("TESLA 1,035.53", "APPLE 1,035.53"), "msci_world", "url"));
        assertThrows(IOException.class, () -> EquitySources.parseMsci(text, "unknown", "url"));
    }
}
