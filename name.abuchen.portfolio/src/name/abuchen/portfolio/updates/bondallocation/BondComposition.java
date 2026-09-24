package name.abuchen.portfolio.updates.bondallocation;

import java.text.Normalizer;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Locale;

public final class BondComposition
{
    public enum Family
    {
        RATING("Obligations - Notation"), MATURITY("Obligations - Échéance"),
        REGIONS("Obligations - Région/Pays"), ISSUER("Obligations - Type d'émetteur");
        private final String title;
        Family(String title) { this.title = title; }
        public String title() { return title; }
        public boolean matches(String name) { return normalize(title).equals(normalize(name)); }
    }
    public static String normalize(String name)
    {
        return Normalizer.normalize(name.strip(), Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                        .replace('’', '\'').toLowerCase(Locale.ROOT);
    }
    public record Slice(Map<String, Integer> weights, LocalDate date, String source, String note)
    {
        public Slice
        {
            weights = Collections.unmodifiableMap(new LinkedHashMap<>(weights));
            if (weights.isEmpty() || weights.entrySet().stream().anyMatch(e -> e.getKey().isBlank() || e.getValue() < 0)
                            || weights.values().stream().mapToLong(Integer::longValue).sum() != 10000)
                throw new IllegalArgumentException("L'allocation doit totaliser 100 % sans poids négatif.");
            if (date != null && date.isAfter(LocalDate.now())) throw new IllegalArgumentException("Date de composition future.");
            Objects.requireNonNull(source); Objects.requireNonNull(note);
        }
    }
    public record Outcome(String securityId, Family family, Slice slice, String error)
    {
        public Outcome
        {
            Objects.requireNonNull(securityId); Objects.requireNonNull(family);
            if ((slice == null) == (error == null)) throw new IllegalArgumentException("Résultat ambigu.");
        }
    }
    private BondComposition() { }
}
