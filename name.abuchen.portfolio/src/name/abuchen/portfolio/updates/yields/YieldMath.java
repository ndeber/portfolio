package name.abuchen.portfolio.updates.yields;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.NavigableMap;

/** Rates are fractions, prices are per 100 nominal. */
public final class YieldMath
{
    private YieldMath() { }
    public record Inflation(LocalDate date, LocalDate previous, double rate) { }
    public record Weighted(double value, Double gross, Double net) { }
    public record Average(double total, double grossCovered, double netCovered, Double gross, Double net) { }

    public static Inflation trailing(NavigableMap<LocalDate, Long> index, LocalDate date)
    {
        var now = index.floorEntry(date);
        if (now == null || ChronoUnit.DAYS.between(now.getKey(), date) > 7)
            throw new IllegalArgumentException("IPCH EU absent ou ancien à la date choisie. Actualiser l’inflation EU (IPCH).");
        LocalDate previous = now.getKey().minusYears(1);
        Long before = index.get(previous);
        if (before == null || before <= 0 || now.getValue() <= 0)
            throw new IllegalArgumentException("Référence IPCH EU manquante un an auparavant : " + previous);
        return new Inflation(now.getKey(), previous, now.getValue() / (double) before - 1);
    }

    public static double nominal(double real, double inflation)
    {
        rate(real); rate(inflation);
        return (1 + real) * (1 + inflation) - 1;
    }
    public static void rate(double value)
    {
        if (!Double.isFinite(value) || value <= -1 || value > 10)
            throw new IllegalArgumentException("Rendement invalide.");
    }
    public static double net(double published, double fees, boolean alreadyNet)
    {
        rate(published);
        if (!Double.isFinite(fees) || fees < 0 || fees > 1) throw new IllegalArgumentException("Frais annuels invalides.");
        return alreadyNet ? published : published - fees;
    }
    public static Average average(List<Weighted> rows)
    {
        double total = 0, gc = 0, nc = 0, g = 0, n = 0;
        for (var row : rows)
        {
            if (!Double.isFinite(row.value()) || row.value() < 0) throw new IllegalArgumentException("Pondération négative ou invalide.");
            total += row.value();
            if (row.gross() != null) { rate(row.gross()); gc += row.value(); g += row.value() * row.gross(); }
            if (row.net() != null) { rate(row.net()); nc += row.value(); n += row.value() * row.net(); }
        }
        return new Average(total, gc, nc, gc == 0 ? null : g / gc, nc == 0 ? null : n / nc);
    }

    /** Annual coupon, ACT/ACT fraction to the next coupon, repayment at par. */
    public static double annualBond(double dirty, double coupon, LocalDate settlement, LocalDate maturity)
    {
        return periodicBond(dirty, coupon, settlement, maturity, 1);
    }

    /** Returns nominal annual yield compounded frequency times per year. */
    public static double periodicBond(double dirty, double coupon, LocalDate settlement, LocalDate maturity, int frequency)
    {
        if (frequency != 1 && frequency != 2) throw new IllegalArgumentException("Fréquence de coupon invalide.");
        if (!Double.isFinite(dirty) || dirty <= 0 || coupon < 0 || !Double.isFinite(coupon) || !settlement.isBefore(maturity))
            throw new IllegalArgumentException("Prix, coupon ou échéance invalide.");
        LocalDate next = maturity;
        int payments = 1;
        while (next.minusMonths(12 / frequency).isAfter(settlement)) { next = next.minusMonths(12 / frequency); payments++; }
        double fraction = ChronoUnit.DAYS.between(settlement, next) / (double) ChronoUnit.DAYS.between(next.minusMonths(12 / frequency), next);
        double low = -.9999, high = 1;
        while (price(high, coupon / frequency, payments, fraction) > dirty && high < 1024) high *= 2;
        if (price(low, coupon / frequency, payments, fraction) < dirty || price(high, coupon / frequency, payments, fraction) > dirty)
            throw new IllegalArgumentException("Rendement non résolu.");
        for (int i = 0; i < 160; i++)
        {
            double mid = (low + high) / 2;
            if (price(mid, coupon / frequency, payments, fraction) > dirty) low = mid; else high = mid;
        }
        return frequency * (low + high) / 2;
    }
    private static double price(double yield, double coupon, int count, double fraction)
    {
        double value = 0;
        for (int i = 0; i < count; i++) value += (100 * coupon + (i == count - 1 ? 100 : 0)) / Math.pow(1 + yield, fraction + i);
        return value;
    }

    /** Annual ACT/ACT ICMA, including the long first coupon (no phantom payment). */
    public static double longFirstCouponBond(double dirty, double coupon, LocalDate settlement, LocalDate maturity,
                    LocalDate interestStart, LocalDate firstCoupon)
    {
        if (!settlement.isBefore(firstCoupon)) return annualBond(dirty, coupon, settlement, maturity);
        if (!Double.isFinite(dirty) || dirty <= 0 || settlement.isBefore(interestStart))
            throw new IllegalArgumentException("Prix ou date invalide.");
        var times = new java.util.ArrayList<Double>();
        var amounts = new java.util.ArrayList<Double>();
        for (var pay = firstCoupon; !pay.isAfter(maturity); pay = pay.plusYears(1))
        {
            var next = pay; int years = 0;
            while (next.minusYears(1).isAfter(settlement)) { next = next.minusYears(1); years++; }
            times.add(years + ChronoUnit.DAYS.between(settlement, next) / (double) ChronoUnit.DAYS.between(next.minusYears(1), next));
            double amount = 100 * coupon;
            if (pay.equals(firstCoupon)) amount *= 1 + ChronoUnit.DAYS.between(interestStart, firstCoupon.minusYears(1))
                            / (double) ChronoUnit.DAYS.between(firstCoupon.minusYears(2), firstCoupon.minusYears(1));
            amounts.add(amount + (pay.equals(maturity) ? 100 : 0));
        }
        double low = -.99, high = 10;
        for (int i = 0; i < 160; i++)
        {
            double mid = (low + high) / 2, value = 0;
            for (int j = 0; j < times.size(); j++) value += amounts.get(j) / Math.pow(1 + mid, times.get(j));
            if (value > dirty) low = mid; else high = mid;
        }
        return (low + high) / 2;
    }
}
