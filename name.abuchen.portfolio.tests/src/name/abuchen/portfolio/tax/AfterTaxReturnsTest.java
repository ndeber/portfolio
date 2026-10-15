package name.abuchen.portfolio.tax;

import static org.junit.Assert.*;

import java.time.LocalDate;

import org.junit.Test;

public class AfterTaxReturnsTest
{
    private LocalDate[] annualDates(int count)
    {
        var dates = new LocalDate[count];
        for (int i = 0; i < count; i++)
            dates[i] = LocalDate.of(2020, 1, 1).plusDays(365L * i);
        return dates;
    }

    @Test
    public void mixedWithdrawalsAndContributionsCanHaveAUniqueZeroIrr()
    {
        var r = AfterTaxReturns.calculate(annualDates(4), new long[] { 1000, 0, 1000, 800 },
                        new long[] { 0, 0, 1000, 0 }, new long[] { 0, 1200, 0, 0 });
        assertEquals(0, r.irr(), 1e-8);
        assertEquals(Long.valueOf(0), r.gain());
    }

    @Test
    public void twoPossibleIrrsAreNotReportedAsOne()
    {
        // -100, +230, -132 has two annual roots: 10% and 20%.
        var r = AfterTaxReturns.calculate(annualDates(3), new long[] { 100, 0, 0 },
                        new long[] { 0, 0, 132 }, new long[] { 0, 230, 0 });
        assertNull(r.irr());
    }

    @Test
    public void ReturnWithNoPositiveCapitalDoesNotInventAPercentage()
    {
        var r = AfterTaxReturns.calculate(annualDates(2), new long[] { 0, 100 },
                        new long[] { 0, 0 }, new long[] { 0, 0 });
        assertEquals(Long.valueOf(100), r.gain());
        assertNull(r.percent());
        assertNull(r.twr());
        assertNull(r.annualized());
        assertNull(r.irr());
    }
}
