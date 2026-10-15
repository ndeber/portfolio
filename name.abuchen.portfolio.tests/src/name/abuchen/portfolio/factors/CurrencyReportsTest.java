package name.abuchen.portfolio.factors;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;
import org.junit.Test;

@SuppressWarnings("nls")
public class CurrencyReportsTest
{
    private final LocalDate today = LocalDate.of(2026, 9, 30);
    private String ivo() throws Exception
    {
        try (var in = getClass().getResourceAsStream("ivo-currencies.txt"))
        { return new String(in.readAllBytes(), StandardCharsets.UTF_8); }
    }

    @Test public void ivoUsesExactShareDateAndAfterHedgeLines() throws Exception
    {
        var result = CurrencyReports.parseIvo(ivo(), "source", today, "");
        assertEquals(Map.of("EUR", 9940, "USD", 60), result.weights());
        assertEquals(LocalDate.of(2026, 8, 31), result.date());
        assertFalse(result.estimated());
        assertEquals(result.weights(), CurrencyReports.reviewed("LU1165644672", today).weights());
    }

    @Test public void ivoRejectsWrongPartGrossExposureFutureAndIncompleteReports() throws Exception
    {
        String text = ivo();
        for (String bad : new String[] {text.replace("LU1165644672", "LU1165649999"),
                        text.replace("PART R CAP EUR", "PART R CAP USD"), text.replace("après couverture", "avant couverture"),
                        text.replace("99,4%", "99,5%"), text.replace("99,4%", "100,6%").replace("0,6%", "-0,6%"),
                        text + " Exposition EUR après couverture 99,4%"})
            assertThrows(IllegalArgumentException.class, () -> CurrencyReports.parseIvo(bad, "source", today, ""));
        assertThrows(IllegalArgumentException.class, () -> CurrencyReports.parseIvo(text, "source", today.minusMonths(2), ""));
    }

    @Test public void historicalNetTablesUseNavAndKeepOffshoreRenminbiSeparate()
    {
        var world = CurrencyReports.reviewed("IE00BK5BQT80", today);
        assertEquals(LocalDate.of(2025, 6, 30), world.date());
        assertFalse(world.estimated());
        assertEquals(6326, (int)world.weights().get("USD"));
        assertEquals(569, (int)world.weights().get("JPY"));
        var emerging = CurrencyReports.reviewed("IE00BK5BR733", today);
        assertEquals(2535, (int)emerging.weights().get("HKD"));
        assertEquals(562, (int)emerging.weights().get("CNH"));
        assertFalse(emerging.weights().containsKey("CNY")); // <0.005%, rounds to zero; never merged into CNH.
        for (String isin : new String[] {"IE00BK5BQT80", "IE00BK5BR733", "IE00BK5BQX27", "IE00BK5BQW10", "IE00BK5BQZ41"})
        {
            var e = CurrencyReports.reviewed(isin, today);
            assertEquals(10000, e.weights().values().stream().mapToInt(Integer::intValue).sum());
            assertFalse(e.weights().containsKey("UNKNOWN"));
            assertTrue(CurrencyReports.reviewed(isin, LocalDate.of(2025, 1, 1)).weights().isEmpty());
        }
    }

    @Test public void netAmountsRejectLeverageAndDoNotNormalizeToHideMissingData()
    {
        assertThrows(IllegalArgumentException.class, () -> CurrencyReports.netAmounts(Map.of("EUR", 110d, "USD", -10d), 100));
        assertThrows(IllegalArgumentException.class, () -> CurrencyReports.netAmounts(Map.of("EUR", 90d), 100));
        assertThrows(IllegalArgumentException.class, () -> CurrencyReports.netAmounts(Map.of("EUR", Double.NaN), 100));
        assertThrows(IllegalArgumentException.class, () -> CurrencyReports.netAmounts(Map.of("EUR", 1d), 0));
    }

    @Test public void GrossTablesAndHedgingPoliciesDoNotBecomeNetProposals()
    {
        for (String isin : new String[] {"IE00B1FZS467", "FR001400TS84", "FR0011176627", "FR001400U4U9", "FR001400S0P1", "UNKNOWN"})
            assertTrue(CurrencyReports.reviewed(isin, today).weights().isEmpty());
    }
}
