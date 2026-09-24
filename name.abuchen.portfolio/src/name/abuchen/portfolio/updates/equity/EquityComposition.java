package name.abuchen.portfolio.updates.equity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Source data and the original script's look-through conventions, without model mutations. */
public final class EquityComposition
{
    public enum Family
    {
        REGIONS("Actions - Régions (MSCI)"), SECTORS("Actions - Secteurs (MSCI)"), HOLDINGS("Actions - Transparence");
        private final String title;
        Family(String title) { this.title = title; }
        public String title() { return title; }
    }

    public record Item(String name, BigDecimal percent)
    {
        public Item
        {
            if (name == null || name.isBlank() || percent == null || percent.signum() < 0 || percent.compareTo(new BigDecimal("100")) > 0)
                throw new IllegalArgumentException("Libellé ou poids de composition invalide.");
        }
    }

    public record Slice(List<Item> items, LocalDate date, String source, boolean staticProfile)
    {
        public Slice
        {
            items = List.copyOf(items);
            if (items.isEmpty() || items.stream().map(Item::percent).reduce(BigDecimal.ZERO, BigDecimal::add).signum() <= 0)
                throw new IllegalArgumentException("Composition vide ou nulle : affectations conservées.");
            if (items.stream().map(Item::percent).reduce(BigDecimal.ZERO, BigDecimal::add).compareTo(new BigDecimal("100")) > 0)
                throw new IllegalArgumentException("La composition dépasse 100 % : affectations conservées.");
            if (date != null && date.isAfter(LocalDate.now()))
                throw new IllegalArgumentException("Date de composition future.");
            Objects.requireNonNull(source);
        }
    }

    public record Outcome(String securityId, Family family, Slice slice, String error)
    {
        public Outcome
        {
            Objects.requireNonNull(securityId);
            Objects.requireNonNull(family);
            if ((slice == null) == (error == null))
                throw new IllegalArgumentException("Résultat ambigu.");
        }
    }

    private EquityComposition() { }

    public static String holdingKey(String name)
    {
        String key = name.toUpperCase(Locale.ROOT).replaceAll("\\s*\\([A-Z]{2}\\)\\s*$", "")
                        .replaceAll("[^A-Z0-9]+", " ").replaceAll("\\bGRP\\b", "GROUP")
                        .replaceAll("\\bHLDG\\b", "HOLDING").replaceAll("\\bMFG\\b", "MANUFACTURING")
                        .replaceAll("\\bBK\\b", "BANK")
                        .replaceAll("\\b(?:INC|CORP(?:ORATION)?|CO(?:MPANY)?|LTD|LIMITED|PLC|SA|SE|AG|NV|SPA|TBK|JSC)\\b", " ")
                        .replaceAll("\\bCLASS\\s+[A-Z0-9]+\\b", " ")
                        .replaceAll("\\b(?:ORDINARY|COMMON|REGISTERED|SHARES?|SHS|PFD|PREF|PREFERRED|PREFERENCE|NON|VOTING|PARTICIPATING|NEW)\\b", " ")
                        .replaceAll("\\s+", " ").strip();
        key = EquitySources.alias("TRANSPARENCY_HOLDING_KEY_ALIASES", key);
        return key.startsWith("TAIWAN SEMICONDUCTOR") ? "TAIWAN SEMICONDUCTOR MANUFACTURING" : key;
    }

    public static boolean nonEquity(String name)
    {
        String n = name.toUpperCase(Locale.ROOT);
        return n.matches(".*\\b(?:TRS|TOTAL RETURN SWAP|SWAP|ETF|ETC|FUND|SICAV|MONEY MARKET|MM|FUTURE|FUTURES|EMINI|CME)\\b.*")
                        || n.matches(".*\\b(?:MSCI|FTSE|STOXX|RUSSELL|NASDAQ|S&P)\\b.*\\b(?:IDX|INDEX)\\b.*");
    }

    public static Map<String, Integer> weights(Slice slice, Family family, String residual)
    {
        var percentages = new LinkedHashMap<String, BigDecimal>();
        for (var item : slice.items())
        {
            if (family == Family.HOLDINGS && nonEquity(item.name()))
                continue;
            String name = family == Family.HOLDINGS ? holdingKey(item.name()) : item.name();
            if (name.isBlank())
                throw new IllegalArgumentException("Nom d'émetteur non reconnu.");
            percentages.merge(name, item.percent(), BigDecimal::add);
        }
        if (percentages.isEmpty())
            throw new IllegalArgumentException("Aucune exposition Actions exploitable : affectations conservées.");
        var result = new LinkedHashMap<String, Integer>();
        percentages.forEach((name, p) -> result.put(name, p.movePointRight(2).setScale(0, RoundingMode.HALF_UP).intValueExact()));
        int total = result.values().stream().mapToInt(Integer::intValue).sum();
        if (total > 10000)
            throw new IllegalArgumentException("Les poids arrondis dépassent 100 %.");
        if (total < 10000)
            result.merge(family == Family.HOLDINGS ? holdingKey("Other") : residual, 10000 - total, Integer::sum);
        result.values().removeIf(v -> v == 0);
        return Map.copyOf(result);
    }

    public static String holdingLabel(String key, Slice slice)
    {
        String known = EquitySources.alias("TRANSPARENCY_HOLDING_LABELS", key);
        if (!known.equals(key)) return known;
        if (key.equals("OTHER")) return "Other";
        return slice.items().stream().map(Item::name).filter(n -> holdingKey(n).equals(key))
                        .sorted(java.util.Comparator.comparingInt(String::length).thenComparing(String::compareTo))
                        .findFirst().orElse(key);
    }
}
