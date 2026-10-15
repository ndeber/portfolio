package name.abuchen.portfolio.updates.bonds;

import static org.junit.Assert.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.Test;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.updates.yields.YieldMath;

public class NominalReferenceTest
{
    private final BondSpec us = BondSpec.find("US91282CRF04").orElseThrow();
    private final BondSpec oat = BondSpec.find("FR0013154044").orElseThrow();
    private final LocalDate date = LocalDate.of(2026, 10, 1);
    private String html(String end)
    {
        return "<h2>Prices For: October 1, 2026</h2><table><tr><td>91282CRF0</td><td>MARKET BASED NOTE</td>"
                        + "<td>4.625%</td><td>08/15/2036</td><td></td><td>95.093750</td><td>95.093750</td><td>" + end + "</td></tr></table>";
    }

    @Test public void readsDatedEndOfDayAndRejectsWrongIdentity() throws Exception
    {
        var q = TreasurySources.parse(html("95.312500"), us, date).orElseThrow();
        assertEquals(95.3125, q.clean().doubleValue(), 1e-10);
        assertEquals(2.3125 * 47 / 184, q.accrued().doubleValue(), 1e-10);
        assertEquals(date, q.settlement());
        assertTrue(TreasurySources.parse(html("0.000000"), us, date).isEmpty());
        assertThrows(IOException.class, () -> TreasurySources.parse(html("95"), us, date.plusDays(1)));
        assertThrows(IOException.class, () -> TreasurySources.parse(html("95").replace("4.625%", "4.25%"), us, date));
        assertThrows(IOException.class, () -> TreasurySources.parse(html("95").replace("91282CRF0", "91282CRG8"), us, date));
        assertThrows(IOException.class, () -> TreasurySources.parse("maintenance", us, date));
    }

    @Test public void nominalIsNotInflationLinkedAndUsesCorrectCouponFrequency()
    {
        assertFalse(oat.indexed()); assertFalse(us.indexed());
        var q = BondMath.nominal(oat, date, new BigDecimal("74"), "test");
        assertEquals(LocalDate.of(2026, 10, 5), q.settlement());
        assertEquals(1.25 * 133 / 365, q.accrued().doubleValue(), 1e-10);
        assertNull(q.coefficient());
        assertEquals(0, BondMath.nominal(us, LocalDate.of(2027, 2, 15), BigDecimal.valueOf(100), "test").accrued().signum());
        assertThrows(IllegalArgumentException.class, () -> BondMath.nominal(us, LocalDate.of(2026, 8, 10), BigDecimal.TEN, "test"));
        assertEquals(.04625, YieldMath.periodicBond(100, .04625, LocalDate.of(2027, 2, 15), us.maturity(), 2), 1e-10);
        // Independent pricing of a 10-year semiannual bond at 5% bond-equivalent yield.
        double price = 0;
        for (int i = 1; i <= 20; i++) price += (2.3125 + (i == 20 ? 100 : 0)) / Math.pow(1.025, i);
        assertEquals(.05, YieldMath.periodicBond(price, .04625, LocalDate.of(2026, 8, 15), us.maturity(), 2), 1e-10);
    }

    @Test public void acceptsUsdPreservesHistoryAndRefusesWrongCurrency() throws Exception
    {
        var c = new Client(); var s = new Security("US reference", "USD"); s.setIsin(us.isin()); c.addSecurity(s);
        var first = BondMath.nominal(us, date.minusDays(1), BigDecimal.valueOf(95), "test");
        BondUpdate.apply(c, List.of(BondUpdate.prepare(s, List.of(first), false)));
        var q = TreasurySources.parse(html("95.312500"), us, date).orElseThrow();
        BondUpdate.apply(c, List.of(BondUpdate.prepare(s, List.of(q), false)));
        assertEquals(2, s.getPrices().size()); assertEquals("USD", s.getCurrencyCode());
        assertTrue(s.getNote().contains("FedInvest")); assertFalse(s.getNote().contains("Bundesbank"));
        assertThrows(IllegalArgumentException.class, () -> BondUpdate.prepare(s, List.of(first), false));
        s.setCurrencyCode("EUR");
        assertThrows(IllegalArgumentException.class, () -> BondUpdate.prepare(s, List.of(q), false));
    }
}
