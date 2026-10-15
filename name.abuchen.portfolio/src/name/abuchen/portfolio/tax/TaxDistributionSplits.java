package name.abuchen.portfolio.tax;

import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.JsonParser;

import name.abuchen.portfolio.model.Client;

/** Taxable part of a PE distribution; the remainder reduces its fiscal capital basis. */
@SuppressWarnings("nls")
public final class TaxDistributionSplits
{
    public static final String PROPERTY = "fork.tax.distribution-splits.v1";

    private TaxDistributionSplits()
    {
    }

    public static Map<String, Long> load(Client client)
    {
        var answer = new TreeMap<String, Long>();
        var json = client.getProperty(PROPERTY);
        if (json == null)
            return Map.of();
        try
        {
            JsonParser.parseString(json).getAsJsonObject().entrySet().forEach(e -> {
                UUID.fromString(e.getKey());
                long amount = e.getValue().getAsBigDecimal().longValueExact();
                if (amount < 0)
                    throw new IllegalArgumentException("Part imposable négative.");
                answer.put(e.getKey(), amount);
            });
            return Map.copyOf(answer);
        }
        catch (RuntimeException e)
        {
            throw new IllegalArgumentException("Ventilation fiscale des distributions illisible.", e);
        }
    }

    public static void save(Client client, String transaction, long taxable)
    {
        UUID.fromString(transaction);
        if (taxable < 0)
            throw new IllegalArgumentException("Part imposable négative.");
        var values = new TreeMap<>(load(client));
        values.put(transaction, taxable);
        client.setProperty(PROPERTY, new Gson().toJson(values));
        client.touch();
    }
}
