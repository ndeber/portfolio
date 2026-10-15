package name.abuchen.portfolio.factors;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import com.google.gson.Gson;
import com.google.gson.JsonParser;

import name.abuchen.portfolio.model.Client;

/** Official index exposure, not the ETF's substitute/collateral basket. */
@SuppressWarnings("nls")
public final class InvescoData
{
    public static final String ISIN = "IE00BD6FTQ80";
    public static final String PAGE = "https://www.invesco.com/uk/en/financial-products/etfs/invesco-bloomberg-commodity-ucits-etf.html";
    public static final String URL = "https://dng-api.invesco.com/cache/v1/accounts/en_GB/shareclasses/IE00BD6FTQ80/holdings/index?idType=isin";
    public static final String PROPERTY = "fork.factor-map.invesco.v1";

    public record Holding(String name, double weight)
    {
        public Holding
        {
            if (name == null || name.isBlank() || !Double.isFinite(weight) || weight <= 0 || weight > 1)
                throw new IllegalArgumentException("Ligne Invesco invalide.");
            commodity(name); // Reject unrecognized instruments, in particular collateral.
        }

        public String label() { return commodity(name); }
        public Factor factor()
        {
            return switch (label()) {
                case "Or" -> Factor.GOLD;
                case "Pétrole WTI" -> Factor.OIL;
                default -> null;
            };
        }
    }

    public record Snapshot(String isin, String date, String source, List<Holding> holdings)
    {
        public Snapshot
        {
            if (!ISIN.equals(isin) || !URL.equals(source))
                throw new IllegalArgumentException("Source Invesco non reconnue.");
            LocalDate.parse(date);
            holdings = List.copyOf(holdings);
            var names = new HashSet<String>();
            for (var h : holdings)
                if (!names.add(h.name()))
                    throw new IllegalArgumentException("Ligne Invesco dupliquée.");
            double total = holdings.stream().mapToDouble(Holding::weight).sum();
            if (holdings.size() < 15 || Math.abs(total - 1) > .0005
                            || holdings.stream().noneMatch(h -> h.factor() == Factor.GOLD)
                            || holdings.stream().noneMatch(h -> h.factor() == Factor.OIL))
                throw new IllegalArgumentException("Composition Invesco incomplète : aucune normalisation automatique.");
        }

        public Double exposure(Factor factor, LocalDate asof)
        {
            if (LocalDate.parse(date).isAfter(asof) || (factor != Factor.GOLD && factor != Factor.OIL))
                return null;
            return holdings.stream().filter(h -> h.factor() == factor).mapToDouble(Holding::weight).sum();
        }
    }

    private InvescoData() { }

    public static Snapshot parse(String json, LocalDate today)
    {
        var object = JsonParser.parseString(json).getAsJsonObject();
        var rows = new java.util.ArrayList<Holding>();
        for (var element : object.getAsJsonArray("holdings"))
        {
            var h = element.getAsJsonObject();
            rows.add(new Holding(h.get("name").getAsString(), h.get("weight").getAsDouble() / 100));
        }
        var result = new Snapshot(ISIN, object.get("effectiveDate").getAsString(), URL, rows);
        if (LocalDate.parse(result.date()).isAfter(today))
            throw new IllegalArgumentException("Publication Invesco future.");
        return result;
    }

    public static Snapshot bundled()
    {
        try (var stream = InvescoData.class.getResourceAsStream("invesco-index.json");
                        var reader = new InputStreamReader(Objects.requireNonNull(stream), StandardCharsets.UTF_8))
        {
            return parse(JsonParser.parseReader(reader).toString(), LocalDate.MAX);
        }
        catch (IOException e) { throw new IllegalStateException(e); }
    }

    public static Snapshot load(Client client)
    {
        String value = client.getProperty(PROPERTY);
        return value == null ? bundled() : Objects.requireNonNull(new Gson().fromJson(value, Snapshot.class));
    }

    public static void save(Client client, Snapshot snapshot, String expected)
    {
        if (!Objects.equals(expected, client.getProperty(PROPERTY)))
            throw new IllegalArgumentException("La composition a été modifiée pendant la lecture. Relancer l'actualisation.");
        client.setProperty(PROPERTY, new Gson().toJson(snapshot));
        client.touch();
    }

    public static Snapshot fetch(Snapshot previous, LocalDate today) throws IOException, InterruptedException
    {
        var request = HttpRequest.newBuilder(URI.create(URL)).timeout(Duration.ofSeconds(35))
                        .header("User-Agent", "Mozilla/5.0 PortfolioPerformance").GET().build();
        try (var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build())
        {
            var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (var body = response.body())
            {
                if (response.statusCode() != 200)
                    throw new IOException("Invesco HTTP " + response.statusCode());
                byte[] bytes = body.readNBytes(1_000_001);
                if (bytes.length > 1_000_000)
                    throw new IOException("Réponse Invesco trop volumineuse.");
                var result = parse(new String(bytes, StandardCharsets.UTF_8), today);
                if (previous != null && LocalDate.parse(result.date()).isBefore(LocalDate.parse(previous.date())))
                    throw new IOException("Publication plus ancienne ; dernière composition conservée.");
                return result;
            }
        }
    }

    private static String commodity(String name)
    {
        String n = name.toLowerCase(Locale.ROOT);
        if (!n.contains("future"))
            throw new IllegalArgumentException("Instrument Invesco non reconnu : " + name);
        String[][] tokens = { {"gold", "Or"}, {"brent", "Pétrole Brent"}, {"(wti)", "Pétrole WTI"},
                        {"natural gas", "Gaz naturel"}, {"copper", "Cuivre"}, {"soybean oil", "Huile de soja"},
                        {"soybean meal", "Tourteau de soja"}, {"soybeans", "Soja"}, {"corn", "Maïs"},
                        {"gasoil", "Gazole"}, {"aluminium", "Aluminium"}, {"gasoline", "Essence"},
                        {"live cattle", "Bovins"}, {"ulsd", "Fioul / ULSD"}, {"silver", "Argent"},
                        {"hard red winter wheat", "Blé d'hiver"}, {"wheat", "Blé"}, {"sugar", "Sucre"},
                        {"nickel", "Nickel"}, {"zinc", "Zinc"}, {"coffee", "Café"}, {"lean hogs", "Porcs"},
                        {"cotton", "Coton"}, {"cocoa", "Cacao"}, {"lead", "Plomb"} };
        for (var token : tokens)
            if (n.contains(token[0]))
                return token[1];
        throw new IllegalArgumentException("Matière première Invesco non reconnue : " + name);
    }
}
