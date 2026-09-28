package name.abuchen.portfolio.updates.yields;

import static org.junit.Assert.*;
import java.io.IOException;
import java.time.LocalDate;
import org.junit.Test;
import name.abuchen.portfolio.model.Security;

public class YieldSourcesTest
{
    private final Security security = security();
    private final LocalDate date = LocalDate.of(2026, 9, 28);
    private static Security security()
    { var s = new Security("Synthetic fund", "EUR"); s.setIsin("LU0000000001"); return s; }
    private YieldSources.Fund fund(boolean ytw)
    { return new YieldSources.Fund("https://example.org/report", "Yield ([\\d.,]+)%", "As of (\\d{2}/\\d{2}/\\d{4})", false, null, ytw); }
    @Test public void ytwIsExplicitAndFeesAreNotSubtractedByParser() throws Exception
    {
        var o = YieldSources.parse(security, "LU0000000001 As of 31/08/2026 Yield 6,7%", fund(true), .015, date);
        assertTrue(o.ytw()); assertEquals(.067, o.published(), 1e-12); assertEquals(.015, o.fees(), 1e-12);
    }
    @Test public void wrongShareIsRejected()
    { assertThrows(IOException.class, () -> YieldSources.parse(security, "LU0000000002 As of 31/08/2026 Yield 6.7%", fund(false), 0d, date)); }
    @Test public void futureAndStaleDocumentsAreRejected()
    {
        for (String day : new String[] {"30/09/2026", "31/03/2026"})
            assertThrows(IOException.class, () -> YieldSources.parse(security, "LU0000000001 As of " + day + " Yield 6.7%", fund(false), 0d, date));
    }
    @Test public void missingYieldDoesNotBecomeZero()
    { assertThrows(IOException.class, () -> YieldSources.parse(security, "LU0000000001 As of 31/08/2026 Coupon 6.7%", fund(false), 0d, date)); }
    @Test public void missingFeesRemainMissing() throws Exception
    { assertNull(YieldSources.parse(security, "LU0000000001 As of 31/08/2026 Yield 0%", fund(false), null, date).fees()); }
    @Test public void frenchMonthAndEnglishDates()
    {
        assertEquals(LocalDate.of(2026, 8, 31), YieldSources.parseDate("31 août 2026"));
        assertEquals(LocalDate.of(2026, 8, 31), YieldSources.parseDate("August 31, 2026"));
    }
    @Test public void ivoSelectsEurYtwNotUsd() throws Exception
    {
        var ivo = new Security("IVO", "EUR"); ivo.setIsin("LU1165644672");
        var source = YieldSources.FUNDS.get(ivo.getIsin());
        String text = "LU1165644672 Factsheet - 31 August 2026 Yield to Worst USD 9.0% 8.6% "
                        + "portfolio offered a yield to worst of 7.2% in EUR / 8.6% in USD";
        var o = YieldSources.parse(ivo, text, source, .015, date);
        assertEquals(.072, o.published(), 1e-12); assertTrue(o.ytw());
        assertThrows(IOException.class, () -> YieldSources.parse(ivo, "LU1165644672 Factsheet - 31 August 2026 Yield to Worst USD 8.6%", source, .015, date));
    }

}
