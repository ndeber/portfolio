package name.abuchen.portfolio.tax;

import java.time.LocalDate;
import java.util.Objects;

import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.tax.PositionTaxEstimates.Row;

/**
 * Period performance for unchanged holdings. Until the fiscal event ledger is
 * available, periods containing trades, income or fiscal resets are not inferred.
 */
@SuppressWarnings("nls")
public final class PositionTaxPerformance
{
    public record Result(Long beforeTax, Long accruedTax, Long latentTaxChange, Long afterTax, String detail)
    {
        public boolean isComplete()
        {
            return afterTax != null;
        }
    }

    private PositionTaxPerformance()
    {
    }

    public static Result reconcile(long beforeTax, long accruedTax, long openingLatentTax, long closingLatentTax)
    {
        long change = Math.subtractExact(closingLatentTax, openingLatentTax);
        long afterTax = Math.subtractExact(Math.subtractExact(beforeTax, accruedTax), change);
        return new Result(beforeTax, accruedTax, change, afterTax,
                        "Performance avant impôts − fiscalité acquise − variation de fiscalité latente.");
    }

    public static Result between(Client client, Row opening, Row closing, LocalDate start, LocalDate end)
    {
        if (end.isBefore(start))
            throw new IllegalArgumentException("La fin de période précède son début.");
        if (opening == null || closing == null || !opening.key().equals(closing.key())
                        || opening.shares() <= 0 || opening.shares() != closing.shares()
                        || opening.marketValue() == null || closing.marketValue() == null)
            return unavailable("Performance indisponible : il faut une position et une valorisation aux deux dates.");

        // Quantity alone would miss a sale followed by a repurchase of the same
        // number of shares. Compare the actual movements at both endpoints.
        if (!PositionTaxEstimates.movements(client, closing.portfolio(), closing.security(), start)
                        .equals(PositionTaxEstimates.movements(client, closing.portfolio(), closing.security(), end)))
            return unavailable("Performance à compléter : mouvement sur la période (vente, achat, transfert ou flux PE).");

        // Account-level income cannot always be allocated unambiguously between
        // portfolios holding the same security. Never discard it or guess its tax.
        boolean hasAccountFlow = client.getAccounts().stream().flatMap(a -> a.getTransactions().stream())
                        .anyMatch(t -> t.getSecurity() == closing.security()
                                        && t.getDateTime().toLocalDate().isAfter(start)
                                        && !t.getDateTime().toLocalDate().isAfter(end));
        if (hasAccountFlow)
            return unavailable("Performance à compléter : revenu, frais ou impôt rattaché au titre sur la période.");

        long beforeTax = Math.subtractExact(closing.marketValue(), opening.marketValue());
        if (opening.estimate() == null || closing.estimate() == null
                        || !Objects.equals(opening.basis(), closing.basis()))
            return new Result(beforeTax, null, null, null,
                            "Performance nette indisponible : les paramètres fiscaux validés doivent couvrir toute la période. "
                                            + (closing.estimate() == null ? closing.detail() : opening.detail()));

        // These are periods with no recorded fiscal event or income, and a
        // still-valid basis at both endpoints. Payments without a security are
        // intentionally not reallocated as new charges to individual holdings.
        var result = reconcile(beforeTax, 0, opening.estimate().contribution(), closing.estimate().contribution());
        return new Result(result.beforeTax(), result.accruedTax(), result.latentTaxChange(), result.afterTax(),
                        "Du " + start + " au " + end + ". Période sans mouvement, revenu ni clôture à traiter. "
                                        + "Fiscalité acquise : 0 sur cette période ; les paiements globaux ne sont pas réattribués.");
    }

    private static Result unavailable(String detail)
    {
        return new Result(null, null, null, null, detail);
    }
}
