package name.abuchen.portfolio.updates.bonds;

import static org.junit.Assert.*;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.Test;

public class BondMathTest
{
    @Test
    public void settlementSkipsEasterMayDayAndYearEnd()
    {
        assertEquals(LocalDate.parse("2026-04-07"), BondMath.settlement(LocalDate.parse("2026-04-01")));
        assertEquals(LocalDate.parse("2026-05-05"), BondMath.settlement(LocalDate.parse("2026-04-30")));
        assertEquals(LocalDate.parse("2026-12-29"), BondMath.settlement(LocalDate.parse("2026-12-24")));
        assertEquals(LocalDate.parse("2027-01-05"), BondMath.settlement(LocalDate.parse("2026-12-31")));
    }

    @Test
    public void actActUsesLeapYearAndResetsAtCouponDate()
    {
        var spec = BondSpec.find("FR0000188799").orElseThrow();
        var quote = BondMath.indexed(spec, LocalDate.parse("2024-07-22"), new BigDecimal("100"), new BigDecimal("1.5"), "test");
        assertEquals(LocalDate.parse("2024-07-24"), quote.settlement());
        assertEquals(3.15 * 365 / 366, quote.accrued().doubleValue(), 1e-12);
        assertEquals((100 + 3.15 * 365 / 366) * 1.5, quote.dirty().doubleValue(), 1e-12);
        var coupon = BondMath.indexed(spec, LocalDate.parse("2024-07-23"), new BigDecimal("100"), new BigDecimal("1.5"), "test");
        assertEquals(0, coupon.accrued().signum());
    }

    @Test
    public void scalingAndInvalidInputs()
    {
        assertEquals(12345678901L, BondQuote.scaled(new BigDecimal("123.456789005")));
        assertThrows(IllegalArgumentException.class, () -> new BondQuote(LocalDate.now(), BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ONE, null, null, "source"));
        assertThrows(IllegalArgumentException.class, () -> BondMath.indexed(BondSpec.find("FR0013410552").orElseThrow(),
                        LocalDate.parse("2029-03-01"), BigDecimal.TEN, BigDecimal.ONE, "test"));
    }
    @Test
    public void euroIndexed2031UsesJulyCouponAndEuroInflation()
    {
        var spec = BondSpec.find("FR0014001N38").orElseThrow();
        assertTrue(spec.euroIndex());
        assertEquals(LocalDate.of(2031, 7, 25), spec.maturity());
        var quote = BondMath.indexed(spec, LocalDate.of(2026, 9, 23), new BigDecimal("98"), new BigDecimal("1.25"), "test");
        assertEquals(LocalDate.of(2026, 9, 25), quote.settlement());
        assertEquals(.1 * 62 / 365, quote.accrued().doubleValue(), 1e-12);
        assertEquals((98 + .1 * 62 / 365) * 1.25, quote.dirty().doubleValue(), 1e-12);
    }

}
