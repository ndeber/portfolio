package name.abuchen.portfolio.updates.bonds;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

public final class BondMath
{
    private BondMath()
    {
    }

    public static LocalDate easter(int year)
    {
        int a = year % 19, b = year / 100, c = year % 100, d = b / 4, e = b % 4;
        int f = (b + 8) / 25, g = (b - f + 1) / 3, h = (19 * a + b - d - g + 15) % 30;
        int i = c / 4, k = c % 4, l = (32 + 2 * e + 2 * i - h - k) % 7;
        int m = (a + 11 * h + 22 * l) / 451, n = h + l - 7 * m + 114;
        return LocalDate.of(year, n / 31, n % 31 + 1);
    }

    public static boolean businessDay(LocalDate date)
    {
        int month = date.getMonthValue(), day = date.getDayOfMonth();
        var easter = easter(date.getYear());
        return date.getDayOfWeek() != DayOfWeek.SATURDAY && date.getDayOfWeek() != DayOfWeek.SUNDAY
                        && !(month == 1 && day == 1) && !(month == 5 && day == 1)
                        && !(month == 12 && (day == 25 || day == 26))
                        && !date.equals(easter.minusDays(2)) && !date.equals(easter.plusDays(1));
    }

    public static LocalDate settlement(LocalDate trade)
    {
        var date = trade;
        for (int days = 0; days < 2;)
        {
            date = date.plusDays(1);
            if (businessDay(date))
                days++;
        }
        return date;
    }

    public static BondQuote indexed(BondSpec bond, LocalDate trade, BigDecimal clean, BigDecimal coefficient, String source)
    {
        var settlement = settlement(trade);
        if (!settlement.isBefore(bond.maturity()))
            throw new IllegalArgumentException("Date de règlement à l'échéance ou après celle-ci : " + bond.isin());
        var previous = bond.maturity().withYear(settlement.getYear());
        if (previous.isAfter(settlement))
            previous = previous.minusYears(1);
        long days = ChronoUnit.DAYS.between(previous, settlement);
        long year = ChronoUnit.DAYS.between(previous, previous.plusYears(1));
        var accrued = bond.coupon().multiply(BigDecimal.valueOf(100 * days)).divide(BigDecimal.valueOf(year), MathContext.DECIMAL128);
        return new BondQuote(trade, clean, accrued, clean.add(accrued).multiply(coefficient), settlement, coefficient, source);
    }
}
