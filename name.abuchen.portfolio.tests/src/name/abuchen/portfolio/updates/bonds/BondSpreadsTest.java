package name.abuchen.portfolio.updates.bonds;

import static org.junit.Assert.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.Test;
import name.abuchen.portfolio.model.*;
import name.abuchen.portfolio.money.Values;
import name.abuchen.portfolio.updates.yields.YieldMath;

public class BondSpreadsTest
{
    private final LocalDate date = LocalDate.of(2026, 10, 2);
    private long price(double value) { return Math.round(value * Values.Quote.factor()); }

    @Test public void signedSpreadUsesCommonDatesAndAnnualEffectiveYields()
    {
        var us = new TreeMap<LocalDate, Long>(); var de = new TreeMap<LocalDate, Long>();
        us.put(date,price(100));us.put(date.minusDays(1),price(99));de.put(date,price(95));de.put(date.minusDays(2),price(94));
        var points = BondSpreads.calculate(BondSpreads.US,us,de,date);
        assertEquals(1,points.size());
        var p = points.getFirst();
        double nominal = YieldMath.periodicBond(100,.04625,date,LocalDate.of(2036,8,15),2);
        assertEquals(Math.pow(1+nominal/2,2)-1,p.countryYield(),1e-12);
        assertEquals((p.countryYield()-p.bundYield())*10000,p.value()/(double)Values.Quote.factor(),1e-8);
        us.put(date,price(130));assertTrue(BondSpreads.calculate(BondSpreads.US,us,de,date).getFirst().value()<0);
        assertThrows(IllegalArgumentException.class,()->BondSpreads.calculate(BondSpreads.US,new TreeMap<>(),de,date));
        assertThrows(IllegalArgumentException.class,()->BondSpreads.calculate(BondSpreads.US,us,de,date.plusDays(8)));
    }

    @Test public void bundLongFirstCouponHasNoAugust2026PhantomCoupon()
    {
        var maturity=LocalDate.of(2036,8,15);var start=LocalDate.of(2026,7,10);var first=LocalDate.of(2027,8,15);
        // Independent exact coupon-date fixture; first payment is 3 * (1 + 36/365).
        double dirty=0;
        for(int i=1;i<=10;i++)dirty+=(3+(i==1?3*36d/365:0)+(i==10?100:0))/Math.pow(1.04,i);
        assertEquals(.04,YieldMath.longFirstCouponBond(dirty,.03,LocalDate.of(2026,8,15),maturity,start,first),1e-12);
        // Before the regular coupon anniversary there is still no payment in 2026.
        var settle=LocalDate.of(2026,7,15);double fraction=31d/365;dirty=0;
        for(int i=1;i<=10;i++)dirty+=(3+(i==1?3*36d/365:0)+(i==10?100:0))/Math.pow(1.04,i+fraction);
        assertEquals(.04,YieldMath.longFirstCouponBond(dirty,.03,settle,maturity,start,first),1e-12);
    }

    @Test public void createsTwoIndicesIdempotentlyAndReloadsSignedPoints() throws Exception
    {
        var c=new Client();
        for(String isin:List.of(BondSpreads.DE,BondSpreads.FR,BondSpreads.US))
        {
            var b=BondSpec.find(isin).orElseThrow();var s=new Security(isin,b.currency());s.setIsin(isin);c.addSecurity(s);
            var q=new BondQuote(date,BigDecimal.valueOf(100),BigDecimal.ZERO,BigDecimal.valueOf(isin.equals(BondSpreads.US)?130:100),null,null,"test");
            BondUpdate.apply(c,List.of(BondUpdate.prepare(s,List.of(q),false)));
        }
        BondSpreads.update(c,date);BondSpreads.update(c,date);
        assertEquals(5,c.getSecurities().size());
        var copy=ClientFactory.duplicate(c);
        var spread=copy.getSecurities().stream().filter(s->"Spread US-DE".equals(s.getName())).findFirst().orElseThrow();
        assertNull(spread.getCurrencyCode());assertEquals(1,spread.getPrices().size());assertTrue(spread.getPrices().getFirst().getValue()<0);
        spread.setCurrencyCode("USD");
        assertThrows(IllegalArgumentException.class,()->BondSpreads.update(copy,date));
    }

    @Test public void treasuryBackfillsOneQuoteAndAcceptsDatedHoliday() throws Exception
    {
        var b=BondSpec.find(BondSpreads.US).orElseThrow();var s=new Security("US","USD");s.setIsin(b.isin());
        s.addPrice(new SecurityPrice(date,price(100)));
        assertEquals(b.issued(),TreasurySources.historyStart(s,b));
        s.addPrice(new SecurityPrice(b.issued(),price(100)));
        assertEquals(date.minusDays(7),TreasurySources.historyStart(s,b));
        String html="<h2>Prices For: September 7, 2026</h2><table class='data1'><tr><th>CUSIP</th><th>END OF DAY</th></tr></table>";
        assertTrue(TreasurySources.parse(html,b,LocalDate.of(2026,9,7)).isEmpty());
    }
}
