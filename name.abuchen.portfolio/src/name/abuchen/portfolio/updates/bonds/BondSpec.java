package name.abuchen.portfolio.updates.bonds;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Explicit instrument conventions, ported from the on-demand script. Not a universal bond feed. */
public record BondSpec(String isin, LocalDate issued, String category, LocalDate maturity, BigDecimal coupon, boolean euroIndex)
{
    public static final List<BondSpec> SUPPORTED = List.of(
                    new BondSpec("FR0013154044", LocalDate.of(2016, 4, 19), "NOMINAL", LocalDate.of(2036, 5, 25), new BigDecimal("0.0125"), false),
                    new BondSpec("US91282CRF04", LocalDate.of(2026, 8, 17), "NOMINAL", LocalDate.of(2036, 8, 15), new BigDecimal("0.04625"), false),
                    new BondSpec("DE000BU2Z072", LocalDate.of(2026, 7, 10), null, null, null, false),
                    new BondSpec("DE000BU27014", LocalDate.of(2025, 8, 27), "A607", null, null, false),
                    new BondSpec("DE0001102622", LocalDate.of(2022, 10, 18), "A607", null, null, false),
                    indexed("FR0014001N38", "2031-07-25", "0.001", true),
                    indexed("FR0013327491", "2036-07-25", "0.001", true),
                    indexed("FR0000188799", "2032-07-25", "0.0315", true),
                    indexed("FR0013410552", "2029-03-01", "0.001", true),
                    indexed("FR0000186413", "2029-07-25", "0.034", false));

    private static BondSpec indexed(String isin, String maturity, String coupon, boolean euro)
    {
        return new BondSpec(isin, null, null, LocalDate.parse(maturity), new BigDecimal(coupon), euro);
    }

    public static Optional<BondSpec> find(String isin)
    {
        return SUPPORTED.stream().filter(b -> b.isin().equals(isin)).findFirst();
    }

    public boolean indexed()
    {
        return maturity != null && !nominal();
    }

    public boolean nominal() { return "NOMINAL".equals(category); }
    public String currency() { return isin.startsWith("US") ? "USD" : "EUR"; }
    public int frequency() { return isin.startsWith("US") ? 2 : 1; }
}
