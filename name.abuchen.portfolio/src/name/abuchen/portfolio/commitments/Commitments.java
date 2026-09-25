package name.abuchen.portfolio.commitments;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.text.Normalizer;
import java.util.Locale;

import name.abuchen.portfolio.model.*;
import name.abuchen.portfolio.money.*;
import name.abuchen.portfolio.snapshot.ClientSnapshot;

/** EUR commitments: inputs are attributes; paid and outstanding amounts are always derived. */
public final class Commitments
{
    public static final String PREFIX = "fork.pe.commitment.";
    public static final String TOTAL = PREFIX + "total.eur";
    public static final String PAID = PREFIX + "paid.eur";
    public static final String REMAINING = PREFIX + "remaining.eur";
    public static final List<String> YEARS = List.of(PREFIX + "2026.eur", PREFIX + "2027.eur", PREFIX + "2028.eur", PREFIX + "2029plus.eur", PREFIX + "2030.eur", PREFIX + "2031.eur", PREFIX + "2032.eur", PREFIX + "2033.eur");
    public static final List<String> LABELS = List.of("2026", "2027", "2028", "2029", "2030", "2031", "2032", "2033");
    public static final String RESERVE_NAME = "Réserves appels de fonds";
    public record Row(Security security, Long total, Long paid, Long remaining, List<Long> forecast, Long gap, List<String> warnings)
    {
        public Row { forecast = List.copyOf(forecast); warnings = List.copyOf(warnings); }
    }
    public record Summary(List<Row> rows, long total, long paid, long remaining, List<Long> forecast, long gap, boolean complete)
    {
        public Summary { rows = List.copyOf(rows); forecast = List.copyOf(forecast); }
    }
    public record Reserve(String taxonomyId, String categoryId, String path) { }
    private Commitments() { }
    public static boolean derived(String id) { return PAID.equals(id) || REMAINING.equals(id); }
    public static String label(String id)
    {
        if (TOTAL.equals(id)) return "Engagement total (EUR)";
        if (PAID.equals(id)) return "Engagement réalisé (EUR, calculé)";
        if (REMAINING.equals(id)) return "Engagement restant (EUR, calculé)";
        int index = YEARS.indexOf(id);
        return index < 0 ? id : "Appels prévus " + LABELS.get(index) + " (EUR)";
    }
    public static List<String> inputs()
    {
        var ids = new ArrayList<String>(); ids.add(TOTAL); ids.addAll(YEARS); return List.copyOf(ids);
    }
    public static void ensureAttributes(Client client)
    {
        var ids = new ArrayList<>(inputs()); ids.add(PAID); ids.add(REMAINING);
        for (var id : ids)
        {
            var existing = client.getSettings().getAttributeTypes().filter(a -> a.getId().equals(id)).toList();
            if (existing.size() > 1 || (!existing.isEmpty() && (existing.getFirst().getType() != Long.class
                            || !existing.getFirst().supports(Security.class) || existing.getFirst().getConverter().getClass() != AttributeType.AmountConverter.class)))
                throw new IllegalArgumentException("Attribut incompatible : " + label(id));
        }
        for (var id : ids)
            if (client.getSettings().getAttributeTypes().noneMatch(a -> a.getId().equals(id)))
            {
                var attribute = new AttributeType(id); attribute.setName(label(id)); attribute.setColumnLabel(label(id));
                attribute.setType(Long.class); attribute.setTarget(Security.class); attribute.setConverter(AttributeType.AmountConverter.class);
                client.getSettings().addAttributeType(attribute);
            }
        client.getSettings().getAttributeTypes().filter(a -> a.getId().equals(YEARS.get(3))).forEach(a -> {
            a.setName(label(a.getId())); a.setColumnLabel(label(a.getId()));
        });
    }
    public static Long value(Security security, String id)
    {
        var value = security.getAttributes().getMap().get(id);
        if (value == null) return null;
        if (!(value instanceof Long amount) || amount < 0) throw new IllegalArgumentException("Montant invalide : " + label(id));
        return amount;
    }
    public static void save(Client client, Security security, Long total, List<Long> forecast)
    {
        // Preserve later inputs when an older integration supplies the original four columns.
        if (forecast.size() == 4)
        {
            var expanded = new ArrayList<>(forecast);
            for (int i = 4; i < YEARS.size(); i++) { Long amount = value(security, YEARS.get(i)); expanded.add(amount == null ? 0L : amount); }
            forecast = expanded;
        }
        if (forecast.size() != YEARS.size() || total != null && total < 0 || forecast.stream().anyMatch(v -> v == null || v < 0))
            throw new IllegalArgumentException("Saisir des montants positifs ou nuls.");
        if (total == null && forecast.stream().anyMatch(v -> v != 0)) throw new IllegalArgumentException("Renseigner l'engagement total avant les prévisions.");
        ensureAttributes(client);
        for (var id : inputs())
        {
            var attribute = client.getSettings().getAttributeTypes().filter(a -> a.getId().equals(id)).findFirst().orElseThrow();
            Long amount = TOTAL.equals(id) ? total : forecast.get(YEARS.indexOf(id));
            if (total == null) security.getAttributes().remove(attribute); else security.getAttributes().put(attribute, amount);
        }
        // Calculated attributes deliberately have no stored value that could go stale.
        client.getSettings().getAttributeTypes().filter(a -> derived(a.getId())).forEach(a -> security.getAttributes().remove(a));
        client.markDirty();
    }
    private static String normalized(String text)
    {
        return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
    public static boolean excluded(Security security)
    {
        return Set.of("cowboy", "cowboybikes", "phacet", "phacetchris", "checkout", "checkoutcom").contains(normalized(security.getName()));
    }
    public static List<Security> scope(Client client)
    {
        var found = new HashSet<Security>();
        for (var taxonomy : client.getTaxonomies())
            for (var category : taxonomy.getAllClassifications())
                if (Set.of("privateequity", "venturecapital", "capitalinvestissement", "capitalrisque").contains(normalized(category.getName()))) collect(category, found);
        for (var account : client.getAccounts())
            for (var tx : account.getTransactions())
                if (tx.getType() == AccountTransaction.Type.CAPITAL_CALL && tx.getSecurity() != null) found.add(tx.getSecurity());
        return client.getSecurities().stream().filter(s -> !excluded(s)).filter(s -> s.getAttributes().getMap().containsKey(TOTAL) || !s.isRetired() && found.contains(s))
                        .sorted(Comparator.comparing((Security s) -> s.getName()).thenComparing(Security::getUUID)).toList();
    }
    private static void collect(Classification category, Set<Security> securities)
    {
        category.getAssignments().stream().filter(a -> a.getWeight() > 0).forEach(a -> { if (a.getInvestmentVehicle() instanceof Security s) securities.add(s); });
        category.getChildren().forEach(c -> collect(c, securities));
    }
    public static CurrencyConverter strictEur(CurrencyConverter converter)
    {
        return new CurrencyConverter()
        {
            public String getTermCurrency() { return "EUR"; }
            public ExchangeRate getRate(LocalDate date, String currency)
            {
                var eur = converter.with("EUR");
                var rate = eur instanceof CurrencyConverterImpl impl ? impl.getRateStrict(date, currency) : eur.getRate(date, currency);
                if (!currency.equals("EUR") && (rate.getTime().equals(LocalDate.MIN) || rate.getValue().signum() <= 0))
                    throw new IllegalArgumentException("Taux EUR indisponible pour " + currency + " au " + date);
                return rate;
            }
            public CurrencyConverter with(String currency)
            {
                if (!currency.equals("EUR")) throw new IllegalArgumentException("Calcul exclusivement en EUR.");
                return this;
            }
        };
    }
    private static long eur(Transaction transaction, CurrencyConverter converter)
    {
        var gross = transaction.getGrossValue();
        if (gross.getCurrencyCode().equals("EUR")) return gross.getAmount();
        if (transaction instanceof PortfolioTransaction p && p.getCrossEntry() instanceof BuySellEntry entry
                        && entry.getAccountTransaction().getCurrencyCode().equals("EUR"))
            return entry.getAccountTransaction().getGrossValue().getAmount();
        var forex = transaction.getUnit(Transaction.Unit.Type.GROSS_VALUE).filter(u -> u.getForex() != null && u.getForex().getCurrencyCode().equals("EUR"));
        if (forex.isPresent()) return forex.get().getForex().getAmount();
        return converter.convert(transaction.getDateTime(), gross).getAmount();
    }
    public static Row row(Client client, Security security, CurrencyConverter converter, LocalDate date)
    {
        var warnings = new ArrayList<String>(); var forecast = new ArrayList<Long>(); Long total = null, paid = null, remaining = null, gap = null;
        try
        {
            total = value(security, TOTAL);
            for (String id : YEARS) { Long amount = value(security, id); forecast.add(amount == null ? 0L : amount); }
            var buys = client.getPortfolios().stream().flatMap(p -> p.getTransactions().stream())
                            .filter(t -> t.getSecurity() == security && t.getType() == PortfolioTransaction.Type.BUY && !t.getDateTime().toLocalDate().isAfter(date))
                            .sorted(Comparator.comparing(PortfolioTransaction::getDateTime).thenComparing(PortfolioTransaction::getUUID)).toList();
            var eur = strictEur(converter);
            paid = buys.isEmpty() ? 0L : eur(buys.getFirst(), eur);
            if (buys.size() > 1) warnings.add("Plusieurs achats : seul le premier est retenu, avec les appels de fonds.");
            if (buys.isEmpty() && client.getPortfolios().stream().flatMap(p -> p.getTransactions().stream()).anyMatch(t -> t.getSecurity() == security
                            && t.getType() == PortfolioTransaction.Type.DELIVERY_INBOUND && !t.getDateTime().toLocalDate().isAfter(date)))
                throw new IllegalArgumentException("Entrée de titres sans achat initial : historique à compléter.");
            for (var account : client.getAccounts())
                for (var tx : account.getTransactions())
                    if (tx.getSecurity() == security && tx.getType() == AccountTransaction.Type.CAPITAL_CALL && !tx.getDateTime().toLocalDate().isAfter(date))
                        paid = Math.addExact(paid, eur(tx, eur));
            if (total == null) warnings.add("Engagement total non renseigné — exclu des totaux.");
            else
            {
                remaining = Math.subtractExact(total, paid);
                long sum = 0; for (long amount : forecast) sum = Math.addExact(sum, amount);
                gap = Math.subtractExact(remaining, sum);
                if (remaining < 0) warnings.add("Le réalisé dépasse l'engagement total.");
                if (gap != 0) warnings.add(gap > 0 ? "Restant non entièrement ventilé par année." : "Prévisions supérieures au restant.");
                for (int i = 0; i < YEARS.size(); i++) if (2026 + i < date.getYear() && forecast.get(i) > 0) warnings.add("Prévision " + (2026 + i) + " échue : à replanifier.");
            }
        }
        catch (RuntimeException e) { paid = null; remaining = null; gap = null; warnings.add(e.getMessage() == null ? "Calcul impossible." : e.getMessage()); }
        while (forecast.size() < YEARS.size()) forecast.add(0L);
        return new Row(security, total, paid, remaining, forecast, gap, warnings);
    }
    public static Summary summary(Client client, CurrencyConverter converter, LocalDate date)
    {
        var rows = scope(client).stream().map(s -> row(client, s, converter, date)).toList();
        long total = 0, paid = 0, remaining = 0, gap = 0; long[] forecast = new long[YEARS.size()]; boolean complete = !rows.isEmpty();
        for (var row : rows)
        {
            if (row.total() == null || row.remaining() == null) { complete = false; continue; }
            if (row.remaining() < 0) complete = false;
            total = Math.addExact(total, row.total()); paid = Math.addExact(paid, row.paid()); remaining = Math.addExact(remaining, row.remaining()); gap = Math.addExact(gap, row.gap());
            for (int i = 0; i < YEARS.size(); i++) forecast[i] = Math.addExact(forecast[i], row.forecast().get(i));
        }
        return new Summary(rows, total, paid, remaining, java.util.Arrays.stream(forecast).boxed().toList(), gap, complete);
    }
    public static List<Reserve> reserves(Client client)
    {
        var result = new ArrayList<Reserve>();
        for (var taxonomy : client.getTaxonomies()) for (var category : taxonomy.getAllClassifications())
            if (normalized(category.getName()).equals(normalized(RESERVE_NAME))) result.add(new Reserve(taxonomy.getId(), category.getId(), taxonomy.getName() + " / " + category.getName()));
        return List.copyOf(result);
    }
    public static long reserve(Client client, Reserve reserve, CurrencyConverter converter, LocalDate date)
    {
        var taxonomy = client.getTaxonomies().stream().filter(t -> t.getId().equals(reserve.taxonomyId())).findFirst().orElseThrow(() -> new IllegalArgumentException("Taxonomie des réserves absente."));
        var category = taxonomy.getAllClassifications().stream().filter(c -> c.getId().equals(reserve.categoryId())).findFirst().orElseThrow(() -> new IllegalArgumentException("Catégorie des réserves absente."));
        // Snapshot construction needs native-currency converters for every holding.
        // Enforce real EUR rates only when valuing the reserve assignments below.
        var positions = ClientSnapshot.create(client, converter.with("EUR"), date).getPositionsByVehicle();
        return reserveValue(category, positions, strictEur(converter), date);
    }
    private static long reserveValue(Classification category, Map<InvestmentVehicle, name.abuchen.portfolio.snapshot.AssetPosition> positions, CurrencyConverter converter, LocalDate date)
    {
        long value = 0;
        for (var assignment : category.getAssignments())
        {
            var position = positions.get(assignment.getInvestmentVehicle());
            if (position != null && assignment.getWeight() != 0) value = Math.addExact(value, Math.round(converter.convert(date, position.getPosition().calculateValue()).getAmount() * assignment.getWeight() / (double) Classification.ONE_HUNDRED_PERCENT));
        }
        for (var child : category.getChildren()) value = Math.addExact(value, reserveValue(child, positions, converter, date));
        return value;
    }
}
