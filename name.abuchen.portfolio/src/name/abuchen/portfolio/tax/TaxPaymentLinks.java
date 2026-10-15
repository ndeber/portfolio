package name.abuchen.portfolio.tax;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.JsonParser;

import name.abuchen.portfolio.model.Client;

/** Explicit reconciliation only. No account transaction is created or changed. */
@SuppressWarnings("nls")
public final class TaxPaymentLinks
{
    public static final String PROPERTY = "fork.tax.payment-links.v1";

    public record Link(String account, String transaction, String position, long euros)
    {
        public Link
        {
            UUID.fromString(account);
            UUID.fromString(transaction);
            var parts = position.split("/", -1);
            if (parts.length != 2 || euros <= 0)
                throw new IllegalArgumentException("Rapprochement fiscal invalide.");
            UUID.fromString(parts[0]);
            UUID.fromString(parts[1]);
        }
    }

    public record Settings(int version, List<Link> links, Map<String, Long> openingDebt)
    {
        public Settings
        {
            if (version != 1)
                throw new IllegalArgumentException("Version du rapprochement fiscal inconnue.");
            links = List.copyOf(links);
            openingDebt = Map.copyOf(openingDebt);
            openingDebt.forEach((k, v) -> {
                var ids = k.split("/", -1);
                if (ids.length != 2)
                    throw new IllegalArgumentException("Dette initiale invalide.");
                UUID.fromString(ids[0]);
                UUID.fromString(ids[1]);
            });
        }
    }

    private TaxPaymentLinks()
    {
    }

    public static Settings load(Client client)
    {
        String json = client.getProperty(PROPERTY);
        if (json == null)
            return new Settings(1, List.of(), Map.of());
        try
        {
            var root = JsonParser.parseString(json).getAsJsonObject();
            for (var link : root.getAsJsonArray("links"))
                link.getAsJsonObject().get("euros").getAsBigDecimal().longValueExact();
            root.getAsJsonObject("openingDebt").entrySet().forEach(e -> e.getValue().getAsBigDecimal().longValueExact());
            return Objects.requireNonNull(new Gson().fromJson(json, Settings.class));
        }
        catch (RuntimeException e)
        {
            throw new IllegalArgumentException("Rapprochements fiscaux illisibles.", e);
        }
    }

    public static void save(Client client, Settings settings, String expected)
    {
        load(client);
        if (!Objects.equals(expected, client.getProperty(PROPERTY)))
            throw new IllegalArgumentException("Les rapprochements ont changé. Rouvrir le formulaire.");
        client.setProperty(PROPERTY, new Gson().toJson(settings));
        client.touch();
    }
}
