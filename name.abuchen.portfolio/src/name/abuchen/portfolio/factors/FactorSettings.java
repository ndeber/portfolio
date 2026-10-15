package name.abuchen.portfolio.factors;

import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import com.google.gson.Gson;
import name.abuchen.portfolio.model.Client;

/** Explicit, dated overrides. Missing axes remain unknown; zero is an actual assumption. */
@SuppressWarnings("nls")
public final class FactorSettings
{
    public static final String PROPERTY = "fork.factor-map.v1";

    public record Mapping(String date, String source, Map<Factor, Double> notional)
    {
        public Mapping
        {
            LocalDate.parse(date);
            if (source == null || source.isBlank())
                throw new IllegalArgumentException("Une source / justification est obligatoire.");
            notional = Map.copyOf(notional);
            notional.forEach((factor, value) -> {
                // A migrated USD leg can combine two legacy coefficients of up to 100 each.
                double limit = factor == Factor.USD_EUR ? 200 : 100;
                if (!Double.isFinite(value) || Math.abs(value) > limit)
                    throw new IllegalArgumentException("Exposition invalide (maximum ±" + (int) (limit * 100) + " %).");
            });
        }
    }

    public record Settings(int version, Map<String, Mapping> mappings)
    {
        public Settings
        {
            if (version != 1 && version != 2)
                throw new IllegalArgumentException("Version Factor Map inconnue.");
            if (version == 1)
            {
                var migrated = new TreeMap<String, Mapping>();
                mappings.forEach((id, mapping) -> {
                    boolean fx = mapping.notional().containsKey(Factor.EUR_USD) || mapping.notional().containsKey(Factor.JPY_USD);
                    if (mapping.notional().containsKey(Factor.USD_EUR) || mapping.notional().containsKey(Factor.JPY_EUR))
                        throw new IllegalArgumentException("Axes EUR inattendus dans des paramètres anciens.");
                    migrated.put(id, new Mapping(mapping.date(), mapping.source()
                                    + (fx ? " · Conversion FX en EUR depuis EUR/USD=" + mapping.notional().get(Factor.EUR_USD)
                                                    + ", JPY/USD=" + mapping.notional().get(Factor.JPY_USD)
                                                    + " (coefficients ; null=inconnu). USD inconnu si une jambe manque." : ""),
                                    Factor.inEuros(mapping.notional())));
                });
                mappings = migrated;
                version = 2;
            }
            for (var mapping : mappings.values())
                if (mapping.notional().containsKey(Factor.EUR_USD) || mapping.notional().containsKey(Factor.JPY_USD))
                    throw new IllegalArgumentException("Utiliser USD/EUR et JPY/EUR pour le portefeuille.");
            mappings = Map.copyOf(mappings);
        }

        public Settings with(String id, Mapping mapping)
        {
            var copy = new TreeMap<>(mappings);
            if (mapping == null)
                copy.remove(id);
            else
                copy.put(id, mapping);
            return new Settings(2, copy);
        }
    }

    private FactorSettings()
    {
    }

    public static Settings load(Client client)
    {
        String json = client.getProperty(PROPERTY);
        return json == null ? new Settings(2, Map.of())
                        : Objects.requireNonNull(new Gson().fromJson(json, Settings.class));
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
