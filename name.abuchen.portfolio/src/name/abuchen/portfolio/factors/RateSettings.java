package name.abuchen.portfolio.factors;

import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import com.google.gson.Gson;
import name.abuchen.portfolio.model.Client;

/** Duration contributions per unit of the entire security NAV, never notional percentages. */
@SuppressWarnings("nls")
public final class RateSettings
{
    public static final String PROPERTY = "fork.rate-sensitivity.v1";

    public enum Curve
    {
        US("Taux nominaux US"), DE("Taux nominaux Allemagne"), FR("Taux nominaux France"),
        REAL_EUR("Taux réels EUR"), MONEY_EUR("Monétaire EUR"), NONE("Aucune exposition obligataire directe"),
        COMMON_ONLY("Duration nominale · géographie non documentée");

        private final String label;
        Curve(String label) { this.label = label; }
        public String label() { return label; }
    }

    public record Mapping(String date, String source, Curve curve, Double shortDuration, Double intermediateDuration,
                    Double longDuration)
    {
        public Mapping
        {
            LocalDate.parse(date);
            Objects.requireNonNull(curve);
            if (source == null || source.isBlank())
                throw new IllegalArgumentException("Une source / justification est obligatoire.");
            for (Double value : new Double[] { shortDuration, intermediateDuration, longDuration })
                if (value != null && (!Double.isFinite(value) || Math.abs(value) > 100))
                    throw new IllegalArgumentException("Duration invalide (maximum ±100 années par segment).");
            if (curve == Curve.NONE && (shortDuration == null || intermediateDuration == null || longDuration == null
                            || shortDuration != 0 || intermediateDuration != 0 || longDuration != 0))
                throw new IllegalArgumentException("Une absence d'exposition doit être confirmée par trois zéros.");
        }

        public Double[] durations() { return new Double[] { shortDuration, intermediateDuration, longDuration }; }
    }

    public record Settings(int version, Map<String, Mapping> mappings)
    {
        public Settings
        {
            if (version != 1) throw new IllegalArgumentException("Version de sensibilité taux inconnue.");
            mappings = Map.copyOf(mappings);
        }

        public Settings with(String id, Mapping mapping)
        {
            var copy = new TreeMap<>(mappings);
            if (mapping == null) copy.remove(id); else copy.put(id, mapping);
            return new Settings(1, copy);
        }
    }

    private RateSettings() { }

    public static Settings load(Client client)
    {
        String json = client.getProperty(PROPERTY);
        return json == null ? new Settings(1, Map.of()) : Objects.requireNonNull(new Gson().fromJson(json, Settings.class));
    }

    public static void save(Client client, Settings settings, String expected)
    {
        load(client);
        if (!Objects.equals(expected, client.getProperty(PROPERTY)))
            throw new IllegalArgumentException("Les paramètres ont changé. Rouvrir la fenêtre.");
        client.setProperty(PROPERTY, new Gson().toJson(settings));
        client.touch();
    }
}
