package name.abuchen.portfolio.factors;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Pattern;

/** Published UCITS duration, separate from both US notionals and volatility-adjusted data. */
@SuppressWarnings("nls")
public final class DbiRateData
{
    private DbiRateData() { }

    public static RateSettings.Mapping bundled()
    {
        return new RateSettings.Mapping("2026-08-31", DbiData.UCITS_FACTSHEET_URL,
                        RateSettings.Curve.COMMON_ONLY, -1.9, -2.7, -2.3);
    }

    public static RateSettings.Mapping parse(String text, LocalDate today)
    {
        if (!text.contains(FactorMap.UCITS_HEDGED) || !text.contains("Fixed Income Derivatives Duration"))
            throw new IllegalArgumentException("Reporting EUR couvert ou tableau de duration absent.");
        var date = Pattern.compile("Fact Sheet\\s*[–—-]\\s*([A-Za-z]+ \\d{1,2}, \\d{4})").matcher(text);
        if (!date.find()) throw new IllegalArgumentException("Date du reporting absente.");
        LocalDate observation = LocalDate.parse(date.group(1), DateTimeFormatter.ofPattern("MMMM d, uuuu", Locale.ENGLISH));
        if (observation.isAfter(today)) throw new IllegalArgumentException("Publication future refusée.");
        int start = text.indexOf("Fixed Income Derivatives Duration");
        int end = text.indexOf("Holdings and sector allocations", start);
        if (end < 0) throw new IllegalArgumentException("Tableau de duration incomplet.");
        // PDF text is read across chart columns: the adjacent equity labels can
        // appear between a rate segment and "Maturities". Remove only these two
        // observed non-numeric labels; never skip intervening numeric content.
        String table = text.substring(start, end).replaceAll("\\b(?:Markets|Equities)\\b", "");
        return new RateSettings.Mapping(observation.toString(), DbiData.UCITS_FACTSHEET_URL,
                        RateSettings.Curve.COMMON_ONLY, duration(table, "Short"), duration(table, "Intermediate"), duration(table, "Long"));
    }

    private static double duration(String table, String segment)
    {
        String number = "([-−]?\\d+(?:[.,]\\d+)?)";
        var matcher = Pattern.compile(segment + "\\s+(?:Maturities\\s+" + number + "|" + number + "\\s+Maturities)").matcher(table);
        if (!matcher.find()) throw new IllegalArgumentException("Duration " + segment + " non reconnue.");
        String value = matcher.group(1) == null ? matcher.group(2) : matcher.group(1);
        if (matcher.find()) throw new IllegalArgumentException("Duration dupliquée : " + segment);
        return Double.parseDouble(value.replace('−', '-').replace(',', '.'));
    }
}
