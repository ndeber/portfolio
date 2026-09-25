package name.abuchen.portfolio.commitments;

import java.time.LocalDate;
import java.util.*;
import name.abuchen.portfolio.model.*;
import name.abuchen.portfolio.money.CurrencyConverter;
import name.abuchen.portfolio.snapshot.ClientSnapshot;

/** Current Xapa holdings split into availability years, not forecast investment returns. */
public final class Availability
{
    public static final String NAME = "Date de disponibilité";
    public static final String PREFIX = "Xapa - ";
    public static final int IMMEDIATE = 0, UNPLANNED = -1;
    public record Result(Map<Integer, Long> amounts, long total, Commitments.Summary commitments)
    {
        public Result { amounts = Collections.unmodifiableMap(new TreeMap<>(amounts)); }
        public long amount(int year) { return amounts.getOrDefault(year, 0L); }
    }
    private Availability() { }
    public static boolean excluded(InvestmentVehicle vehicle)
    {
        return vehicle.getName().toLowerCase(Locale.ROOT).matches("phacet(?:\\b.*)?");
    }
    public static Client scope(Client client)
    {
        var accounts = client.getAccounts().stream().filter(a -> a.getName().startsWith(PREFIX)).toList();
        var portfolios = client.getPortfolios().stream().filter(p -> p.getName().startsWith(PREFIX)).toList();
        if (accounts.isEmpty() && portfolios.isEmpty()) throw new IllegalArgumentException("Aucun compte ou dépôt « Xapa - ».");
        var securities = new HashSet<Security>();
        accounts.forEach(a -> a.getTransactions().forEach(t -> { if (t.getSecurity() != null) securities.add(t.getSecurity()); }));
        portfolios.forEach(p -> p.getTransactions().forEach(t -> securities.add(t.getSecurity())));
        var selected = client.getSecurities().stream().filter(securities::contains).filter(s -> !excluded(s)).toList();
        // A read-only view retains actual transactions and security identity. No cloning,
        // relinking cross entries or reattaching securities' valuation clients is needed.
        return new Client()
        {
            @Override public List<Account> getAccounts() { return accounts; }
            @Override public List<Portfolio> getPortfolios() { return portfolios; }
            @Override public List<Security> getSecurities() { return selected; }
            @Override public List<Taxonomy> getTaxonomies() { return client.getTaxonomies(); }
            @Override public String getBaseCurrency() { return client.getBaseCurrency(); }
        };
    }
    public static Taxonomy taxonomy(Client client, String id)
    {
        var matches = client.getTaxonomies().stream().filter(t -> id == null ? NAME.equalsIgnoreCase(t.getName()) : id.equals(t.getId())).toList();
        if (matches.size() != 1) throw new IllegalArgumentException(matches.isEmpty() ? "Taxonomie « Date de disponibilité » introuvable." : "Plusieurs taxonomies de disponibilité : choisir celle du widget.");
        return matches.getFirst();
    }
    private static int horizon(Classification category, Classification root)
    {
        while (category.getParent() != null && category.getParent() != root) category = category.getParent();
        String name = category.getName().strip();
        if (name.equalsIgnoreCase("Immédiate")) return IMMEDIATE;
        if (name.matches("20\\d{2}")) return Integer.parseInt(name);
        return UNPLANNED;
    }
    public static Result calculate(Client client, Taxonomy taxonomy, CurrencyConverter converter, LocalDate date)
    {
        var scoped = scope(client);
        var weights = new HashMap<InvestmentVehicle, Map<Integer, Integer>>();
        for (var category : taxonomy.getAllClassifications())
            for (var assignment : category.getAssignments())
            {
                if (excluded(assignment.getInvestmentVehicle())) continue;
                if (assignment.getWeight() < 0) throw new IllegalArgumentException("Poids négatif dans la taxonomie.");
                weights.computeIfAbsent(assignment.getInvestmentVehicle(), v -> new TreeMap<>())
                                .merge(horizon(category, taxonomy.getRoot()), assignment.getWeight(), Math::addExact);
            }
        var amounts = new TreeMap<Integer, Long>();
        var positions = ClientSnapshot.create(scoped, converter.with("EUR"), date).getPositionsByVehicle();
        var eur = Commitments.strictEur(converter); long total = 0;
        for (var entry : positions.entrySet())
        {
            if (excluded(entry.getKey())) continue;
            long value = eur.convert(date, entry.getValue().getPosition().calculateValue()).getAmount();
            if (value == 0) continue;
            var allocation = new TreeMap<>(weights.getOrDefault(entry.getKey(), Map.of()));
            int sum = allocation.values().stream().reduce(0, Math::addExact);
            if (sum > Classification.ONE_HUNDRED_PERCENT) throw new IllegalArgumentException("Répartition supérieure à 100 % : " + entry.getKey().getName());
            allocation.merge(UNPLANNED, Classification.ONE_HUNDRED_PERCENT - sum, Integer::sum);
            int largest = allocation.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
            long allocated = 0;
            for (var weight : allocation.entrySet())
            {
                long part = Math.round(value * (weight.getValue() / (double) Classification.ONE_HUNDRED_PERCENT));
                allocated = Math.addExact(allocated, part); amounts.merge(weight.getKey(), part, Math::addExact);
            }
            amounts.merge(largest, Math.subtractExact(value, allocated), Math::addExact);
            total = Math.addExact(total, value);
        }
        return new Result(amounts, total, Commitments.summary(scoped, converter, date));
    }
}
