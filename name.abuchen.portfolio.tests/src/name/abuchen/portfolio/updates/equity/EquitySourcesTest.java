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
}
