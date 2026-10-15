package name.abuchen.portfolio.ui.tax;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

@SuppressWarnings("nls")
public class TaxEstimateLabelsTest
{
    @Test
    public void frenchAmountsAndExplicitZeroAreAcceptedWithoutRounding()
    {
        assertEquals(1234567, TaxEstimateLabels.cents("12\u202f345,67"));
        assertEquals(2500, TaxEstimateLabels.cents("25.00"));
        assertEquals(0, TaxEstimateLabels.cents("0"));
        assertEquals(123456, TaxEstimateLabels.cents("1\u00a0234,56"));
    }

    @Test
    public void blankNegativeAmbiguousOrOverpreciseInputDoesNotBecomeZero()
    {
        for (String value : new String[] { "", " ", "-10", "1.234,56", "25.001", "NaN", "1e4", "999999999999999999999" })
            assertThrows(value, IllegalArgumentException.class, () -> TaxEstimateLabels.cents(value));
    }
}
