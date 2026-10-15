package name.abuchen.portfolio.tax;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import name.abuchen.portfolio.math.IRR;

/** Same dated flow convention as ClientIndex: inflows at start, outflows at end of day. */
@SuppressWarnings("nls")
public final class AfterTaxReturns
{
    public record Result(Long gain, Double percent, Double twr, Double annualized, Double irr, String detail)
    {
        public static Result unavailable(String reason)
        {
            return new Result(null, null, null, null, null, reason);
        }
    }

    private AfterTaxReturns()
    {
    }

    public static Result calculate(LocalDate[] dates, long[] values, long[] in, long[] out)
    {
        if (dates.length == 0 || values.length != dates.length || in.length != dates.length || out.length != dates.length)
            throw new IllegalArgumentException("Séries de performance incompatibles.");
        long gain = Math.subtractExact(values[values.length - 1], values[0]);
        long capital = values[0];
        double factor = 1;
        boolean validTwr = true;
        var cashDates = new ArrayList<LocalDate>();
        var cashValues = new ArrayList<Double>();
        cashDates.add(dates[0]);
        cashValues.add(-(double) values[0]);
        for (int i = 1; i < dates.length; i++)
        {
            if (!dates[i].isAfter(dates[i - 1]) || in[i] < 0 || out[i] < 0)
                throw new IllegalArgumentException("Dates ou flux invalides.");
            gain = Math.addExact(gain, Math.subtractExact(out[i], in[i]));
            capital = Math.addExact(capital, in[i]);
            long denominator = Math.addExact(values[i - 1], in[i]);
            long numerator = Math.addExact(values[i], out[i]);
            if (denominator > 0 && numerator >= 0)
                factor *= (double) numerator / denominator;
            else if (denominator != 0 || numerator != 0)
                validTwr = false;
            cashDates.add(dates[i]);
            cashValues.add((double) out[i] - in[i]);
        }
        cashDates.add(dates[dates.length - 1]);
        cashValues.add((double) values[values.length - 1]);
        long days = ChronoUnit.DAYS.between(dates[0], dates[dates.length - 1]);
        Double twr = validTwr && Double.isFinite(factor) ? factor - 1 : null;
        Double annual = twr != null && days > 0 ? finite(Math.pow(factor, 365d / days) - 1) : null;
        Double irr = uniqueIrr(cashDates, cashValues, days);
        return new Result(gain, capital > 0 ? (double) gain / capital : null, twr, annual, irr,
                        "Du " + dates[0] + " au " + dates[dates.length - 1]
                                        + ". % = gain / (valeur nette initiale + apports). %TW : chaînage quotidien. "
                                        + "Annualisation sur 365 jours ; TRI sur les flux datés. Estimation, pas un revenu garanti.");
    }

    private static Double finite(double number)
    {
        return Double.isFinite(number) ? number : null;
    }

    private static Double uniqueIrr(List<LocalDate> dates, List<Double> flows, long days)
    {
        if (days <= 0)
            return null;
        // Multiple sign changes can produce multiple roots. Do not publish an arbitrary one.
        var net = new java.util.TreeMap<LocalDate, Double>();
        for (int i = 0; i < dates.size(); i++)
            net.merge(dates.get(i), flows.get(i), Double::sum);
        net.entrySet().removeIf(e -> e.getValue() == 0);
        int previous = 0;
        int changes = 0;
        for (double amount : net.values())
        {
            int sign = (int) Math.signum(amount);
            if (sign == 0)
                continue;
            if (previous != 0 && sign != previous)
                changes++;
            previous = sign;
        }
        if (changes == 0)
            return null;
        if (changes > 1)
            return bracketedIrr(net);
        try
        {
            double result = IRR.calculate(new ArrayList<>(net.keySet()), new ArrayList<>(net.values()));
            if (!Double.isFinite(result) || result <= -1)
                return null;
            double residual = 0;
            double scale = 0;
            var first = net.firstKey();
            for (var flow : net.entrySet())
            {
                double amount = flow.getValue() / Math.pow(1 + result, ChronoUnit.DAYS.between(first, flow.getKey()) / 365d);
                residual += amount;
                scale += Math.abs(amount);
            }
            return Math.abs(residual) <= Math.max(0.01, scale * 1e-7) ? result : null;
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }
    private static Double bracketedIrr(java.util.SortedMap<LocalDate, Double> flows)
    {
        var first = flows.firstKey();
        var dates = new ArrayList<>(flows.keySet());
        var amounts = new ArrayList<>(flows.values());
        java.util.function.DoubleUnaryOperator npv = logRate -> {
            double maximum = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < dates.size(); i++)
                if (amounts.get(i) != 0)
                    maximum = Math.max(maximum, -logRate * ChronoUnit.DAYS.between(first, dates.get(i)) / 365d);
            double sum = 0;
            for (int i = 0; i < dates.size(); i++)
                if (amounts.get(i) != 0)
                    sum += amounts.get(i) * Math.exp(-logRate * ChronoUnit.DAYS.between(first, dates.get(i)) / 365d - maximum);
            return sum;
        };
        Double root = null;
        double left = -24;
        double fLeft = npv.applyAsDouble(left);
        for (int i = 1; i <= 4096; i++)
        {
            double right = -24 + i * (48d / 4096);
            double fRight = npv.applyAsDouble(right);
            if (fRight == 0 || fLeft != 0 && Math.signum(fLeft) != Math.signum(fRight))
            {
                double lo = left;
                double hi = right;
                double sign = Math.signum(fLeft);
                for (int j = 0; j < 80; j++)
                {
                    double middle = (lo + hi) / 2;
                    double value = npv.applyAsDouble(middle);
                    if (Math.signum(value) == sign)
                        lo = middle;
                    else
                        hi = middle;
                }
                double candidate = Math.expm1((lo + hi) / 2);
                if (root != null && Math.abs(root - candidate) > 1e-7)
                    return null;
                root = candidate;
            }
            left = right;
            fLeft = fRight;
        }
        return root;
    }

}
