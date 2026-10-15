package name.abuchen.portfolio.factors;

/** Independent economic axes, not an allocation summing to 100%. */
@SuppressWarnings("nls")
public enum Factor
{
    US_EQUITY("Actions US / S&P 500"), EAFE("Actions EAFE"), EMERGING("Actions émergentes"),
    GOLD("Or"), OIL("Pétrole WTI"), EUR_USD("EUR/USD (+EUR / −USD)"), JPY_USD("JPY/USD (+JPY / −USD)"),
    US_2Y("Treasury 2 ans"), US_10Y("Treasury 10 ans"), US_LONG("Treasury long"),
    USD_EUR("USD/EUR (+USD / −EUR)"), JPY_EUR("JPY/EUR (+JPY / −EUR)");

    private final String label;

    Factor(String label)
    {
        this.label = label;
    }

    public String label()
    {
        return label;
    }

    /** Portfolio axes; source contracts and published VA retain their original pairs. */
    public static java.util.List<Factor> portfolioAxes()
    {
        return java.util.List.of(US_EQUITY, EAFE, EMERGING, GOLD, OIL, USD_EUR, JPY_EUR, US_2Y, US_10Y, US_LONG);
    }

    public static java.util.List<Factor> publishedAxes()
    {
        return java.util.List.of(US_EQUITY, EAFE, EMERGING, GOLD, OIL, EUR_USD, JPY_USD, US_2Y, US_10Y, US_LONG);
    }

    /** First-order notional FX decomposition. Missing legs are never assumed zero. */
    public static java.util.Map<Factor, Double> inEuros(java.util.Map<Factor, Double> original)
    {
        var result = new java.util.EnumMap<Factor, Double>(Factor.class);
        result.putAll(original);
        Double euro = result.remove(EUR_USD);
        Double yen = result.remove(JPY_USD);
        if (euro != null && yen != null)
            result.put(USD_EUR, -euro - yen);
        if (yen != null)
            result.put(JPY_EUR, yen);
        return java.util.Map.copyOf(result);
    }

    public boolean rates()
    {
        return this == US_2Y || this == US_10Y || this == US_LONG;
    }
}
