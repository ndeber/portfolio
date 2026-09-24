package name.abuchen.portfolio.updates.bonds;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

import name.abuchen.portfolio.money.Values;

/** Prices per 100 nominal, in the script's dirty convention. */
public record BondQuote(LocalDate date, BigDecimal clean, BigDecimal accrued, BigDecimal dirty,
                LocalDate settlement, BigDecimal coefficient, String source)
{
    public BondQuote
    {
        if (date == null || clean == null || dirty == null || accrued == null || source == null
                        || clean.signum() <= 0 || dirty.signum() <= 0 || accrued.signum() < 0
                        || (coefficient != null && coefficient.signum() <= 0))
            throw new IllegalArgumentException("Cours obligataire invalide.");
        scaled(dirty);
    }

    public long value()
    {
        return scaled(dirty);
    }

    public static long scaled(BigDecimal price)
    {
        return price.multiply(BigDecimal.valueOf(Values.Quote.factor())).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }
}
