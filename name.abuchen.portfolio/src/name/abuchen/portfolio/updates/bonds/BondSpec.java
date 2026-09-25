package name.abuchen.portfolio.updates.bonds;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Explicit instrument conventions, ported from the on-demand script. Not a universal bond feed. */
public record BondSpec(String isin, LocalDate issued, String category, LocalDate maturity, BigDecimal coupon, boolean euroIndex)
{
    public static final List<BondSpec> SUPPORTED = List.of(
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
        return maturity != null;
    }
}
