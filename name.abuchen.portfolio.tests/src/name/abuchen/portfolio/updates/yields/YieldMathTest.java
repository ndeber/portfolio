package name.abuchen.portfolio.updates.yields;

import static org.junit.Assert.*;
import java.time.LocalDate;
import java.util.*;
import org.junit.Test;

public class YieldMathTest
{
    @Test public void annualParBond() { assertEquals(.03, YieldMath.annualBond(100, .03, LocalDate.of(2026, 8, 15), LocalDate.of(2036, 8, 15)), 1e-10); }
    @Test public void zeroCoupon() { assertEquals(Math.pow(100d / 90, .2) - 1, YieldMath.annualBond(90, 0, LocalDate.of(2026, 8, 15), LocalDate.of(2031, 8, 15)), 1e-10); }
    @Test public void negativeYield() { assertTrue(YieldMath.annualBond(110, 0, LocalDate.of(2026, 8, 15), LocalDate.of(2031, 8, 15)) < 0); }
    @Test public void finalCouponFraction()
    {
        var settlement = LocalDate.of(2026, 9, 28); var maturity = LocalDate.of(2027, 7, 25);
        double fraction = java.time.temporal.ChronoUnit.DAYS.between(settlement, maturity) / 365d;
        assertEquals(.04, YieldMath.annualBond(103 / Math.pow(1.04, fraction), .03, settlement, maturity), 1e-10);
    }
    @Test public void noDoubleFees() { assertEquals(.039, YieldMath.net(.045, .006, false), 1e-10); assertEquals(.045, YieldMath.net(.045, .006, true), 1e-10); }
    @Test public void nominalInflationCompounds() { assertEquals(.0404, YieldMath.nominal(.02, .02), 1e-10); }
    @Test public void trailingDoesNotUseFuture()
    {
        var data = new TreeMap<LocalDate, Long>(); data.put(LocalDate.of(2025, 9, 28), 100L); data.put(LocalDate.of(2026, 9, 28), 103L); data.put(LocalDate.of(2026, 10, 1), 109L);
        var result = YieldMath.trailing(data, LocalDate.of(2026, 9, 28)); assertEquals(.03, result.rate(), 1e-10); assertEquals(LocalDate.of(2026, 9, 28), result.date());
    }
    @Test public void missingAnniversaryFails() { assertThrows(IllegalArgumentException.class, () -> YieldMath.trailing(new TreeMap<>(Map.of(LocalDate.of(2026, 9, 28), 103L)), LocalDate.of(2026, 9, 28))); }
    @Test public void weightedCoverageKeepsZeroAndExcludesMissing()
    {
        var result = YieldMath.average(List.of(new YieldMath.Weighted(100, 0d, 0d), new YieldMath.Weighted(300, .08, .07), new YieldMath.Weighted(100, null, null)));
        assertEquals(.06, result.gross(), 1e-10); assertEquals(.0525, result.net(), 1e-10); assertEquals(500, result.total(), 0); assertEquals(400, result.netCovered(), 0);
    }
    @Test public void emptyAverageUnknown() { assertNull(YieldMath.average(List.of()).gross()); }
    @Test public void onlyNetDoesNotInventGross() { var a = YieldMath.average(List.of(new YieldMath.Weighted(100, null, .03))); assertNull(a.gross()); assertEquals(.03, a.net(), 0); }
    @Test public void noShortWeight() { assertThrows(IllegalArgumentException.class, () -> YieldMath.average(List.of(new YieldMath.Weighted(-1, .03, .03)))); }
}
