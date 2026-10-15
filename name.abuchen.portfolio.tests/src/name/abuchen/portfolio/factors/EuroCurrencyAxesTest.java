package name.abuchen.portfolio.factors;

import static org.junit.Assert.*;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import name.abuchen.portfolio.model.Classification;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Security;

@SuppressWarnings("nls")
public class EuroCurrencyAxesTest
{
    private final LocalDate date = LocalDate.of(2026, 9, 30);

    @Test
    public void japaneseFundIsLongYenWithoutDollarExposure()
    {
        var client = new Client();
        var fund = new Security("Japan EUR listing", "EUR");
        var taxonomy = CurrencyExposure.ensure(client);
        taxonomy.getAllClassifications().stream().filter(c -> c.getKey().equals("factor.fx.JPY"))
                        .findFirst().orElseThrow().addAssignment(new Classification.Assignment(fund));
        var exposures = new EnumMap<Factor, Double>(Factor.class);
        var coverage = new EnumMap<Factor, Double>(Factor.class);
        CurrencyExposure.apply(taxonomy, fund, exposures, coverage);
        assertEquals(1, exposures.get(Factor.JPY_EUR), 1e-12);
        assertEquals(0, exposures.get(Factor.USD_EUR), 1e-12);
        assertEquals(1, coverage.get(Factor.USD_EUR), 1e-12);
        assertFalse(exposures.containsKey(Factor.JPY_USD));
    }

    @Test
    public void dbiPreservesBothLegsAndFirstOrderCurrencySensitivity()
    {
        var data = new DbiData.Snapshot(FactorMap.DBMF, date.toString(), DbiData.DBMF_URL, 100,
                        List.of(new DbiData.Holding("Euro", "EC", "1", 30, Factor.EUR_USD),
                                        new DbiData.Holding("Yen", "JY", "1", 20, Factor.JPY_USD)),
                        date.toString(), DbiData.VA_URL, Map.of(Factor.EUR_USD, .18, Factor.JPY_USD, -.28));
        assertEquals(-.5, data.exposure(Factor.USD_EUR, false, date), 1e-12);
        assertEquals(.2, data.exposure(Factor.JPY_EUR, false, date), 1e-12);
        // USD rises 1% versus EUR, JPY rises 3% versus EUR:
        // old EUR/USD return=-1%, JPY/USD return=+2%.
        double original = .3 * -.01 + .2 * .02;
        double converted = data.exposure(Factor.USD_EUR, false, date) * .01
                        + data.exposure(Factor.JPY_EUR, false, date) * .03;
        assertEquals(original, converted, 1e-12);
        assertEquals(.18, data.exposure(Factor.EUR_USD, true, date), 1e-12);
        assertNull(data.exposure(Factor.USD_EUR, true, date));
        assertNull(data.exposure(Factor.JPY_EUR, true, date));
        assertNull(data.exposure(Factor.USD_EUR, false, date.minusDays(1)));
        var bundled = DbiData.bundled();
        assertEquals(-bundled.exposure(Factor.EUR_USD, false, date) - bundled.exposure(Factor.JPY_USD, false, date),
                        bundled.exposure(Factor.USD_EUR, false, date), 1e-12);
        var partial = new DbiData.Snapshot(data.isin(), data.date(), data.source(), 100,
                        List.of(data.holdings().getLast()), null, null, Map.of());
        assertNull(partial.exposure(Factor.USD_EUR, false, date));
        assertEquals(.2, partial.exposure(Factor.JPY_EUR, false, date), 1e-12);
    }

    @Test
    public void legacySettingsConvertOnceWithoutWritingOnRead()
    {
        var client = new Client();
        String json = """
                        {"version":1,"mappings":{
                          "mixed":{"date":"2026-09-30","source":"Report","notional":{"EUR_USD":-0.75,"JPY_USD":0.12,"GOLD":0.05}},
                          "yen":{"date":"2026-09-30","source":"Report","notional":{"EUR_USD":-1,"JPY_USD":1}},
                          "partial":{"date":"2026-09-30","source":"Report","notional":{"JPY_USD":0.2}},
                          "euroOnly":{"date":"2026-09-30","source":"Report","notional":{"EUR_USD":-0.5}}
                        }}
                        """;
        client.setProperty(FactorSettings.PROPERTY, json);
        var settings = FactorSettings.load(client);
        assertEquals(json, client.getProperty(FactorSettings.PROPERTY));
        assertEquals(2, settings.version());
        assertEquals(.63, settings.mappings().get("mixed").notional().get(Factor.USD_EUR), 1e-12);
        assertEquals(.12, settings.mappings().get("mixed").notional().get(Factor.JPY_EUR), 1e-12);
        assertEquals(.05, settings.mappings().get("mixed").notional().get(Factor.GOLD), 1e-12);
        assertEquals(0, settings.mappings().get("yen").notional().get(Factor.USD_EUR), 1e-12);
        assertEquals(1, settings.mappings().get("yen").notional().get(Factor.JPY_EUR), 1e-12);
        assertNull(settings.mappings().get("partial").notional().get(Factor.USD_EUR));
        assertEquals(.2, settings.mappings().get("partial").notional().get(Factor.JPY_EUR), 1e-12);
        assertTrue(settings.mappings().get("euroOnly").notional().isEmpty());
        FactorSettings.save(client, settings, json);
        assertEquals(settings, FactorSettings.load(client));
        assertEquals(10, Factor.portfolioAxes().size());
        assertFalse(Factor.portfolioAxes().contains(Factor.EUR_USD));
        assertTrue(Factor.publishedAxes().contains(Factor.EUR_USD));
    }
}
