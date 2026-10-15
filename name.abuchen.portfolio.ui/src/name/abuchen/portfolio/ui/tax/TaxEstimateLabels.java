package name.abuchen.portfolio.ui.tax;

import java.math.BigDecimal;

import name.abuchen.portfolio.tax.PositionTaxEstimates.Status;
import name.abuchen.portfolio.tax.TaxEstimateSettings.Profile;

@SuppressWarnings("nls")
public final class TaxEstimateLabels
{
    public static final String TITLE = "Fiscalité estimée";

    private TaxEstimateLabels()
    {
    }

    public static String profile(Profile profile)
    {
        return switch (profile)
        {
            case CTO -> "CTO";
            case PEA -> "PEA — retrait après cinq ans";
            case LIFE_INSURANCE -> "Assurance-vie (convention)";
            case HOLDING_ANNUAL -> "Holding — imposition annuelle";
            case HOLDING_DISPOSAL -> "Holding — à la cession";
            case EXEMPT -> "Exonéré";
            case CUSTOM -> "Autre régime";
        };
    }

    public static String status(Status status)
    {
        return switch (status)
        {
            case READY -> "Estimé";
            case NOT_CONFIGURED -> "À paramétrer";
            case BASIS_CHANGED -> "Base à revalider";
            case REVIEW_DUE -> "Clôture à traiter";
            case CLOSED -> "Position clôturée";
            case UNAVAILABLE -> "Indisponible";
        };
    }

    /** Accept a decimal comma or point, but never round user input silently. */
    public static long cents(String text)
    {
        var normalized = text.strip().replace(" ", "").replace("\u00a0", "").replace("\u202f", "")
                        .replace(',', '.');
        if (!normalized.matches("[0-9]+([.][0-9]{1,2})?"))
            throw new IllegalArgumentException("Saisir un nombre positif ou nul, avec deux décimales au maximum.");
        try
        {
            return new BigDecimal(normalized).movePointRight(2).longValueExact();
        }
        catch (ArithmeticException e)
        {
            throw new IllegalArgumentException("Le montant est trop élevé.", e);
        }
    }
}
