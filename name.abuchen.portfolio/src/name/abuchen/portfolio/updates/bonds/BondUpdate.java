package name.abuchen.portfolio.updates.bonds;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import name.abuchen.portfolio.model.AttributeType;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.LatestSecurityPrice;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.model.SecurityPrice;
import name.abuchen.portfolio.model.SecurityProperty;

/** Read-only preparation and atomic prevalidation before applying to the open model. */
public final class BondUpdate
{
    private static final String BEGIN = "[XAPA-BOND-PRICES-BEGIN]";
    private static final String END = "[XAPA-BOND-PRICES-END]";
    private static final String CONVENTION = "Convention Portfolio Performance : prix dirty";

    public record Point(LocalDate date, long value)
    {
    }

    public record State(String isin, String currency, String note, String feed, String feedUrl, String latestFeed,
                    String latestFeedUrl, List<String> properties, List<Point> prices, String latest,
                    Map<String, Object> attributes, Instant updatedAt)
    {
    }

    public record Plan(String securityId, State before, List<BondQuote> quotes, boolean migration, List<Point> after, int removed)
    {
        public Plan
        {
            quotes = List.copyOf(quotes);
            after = List.copyOf(after);
        }
    }

    private record Attribute(String id, String label, Class<?> type, Class<? extends AttributeType.Converter> converter)
    {
    }

    private static final List<Attribute> ATTRIBUTES = List.of(
                    new Attribute("xapa.bond.clean-price", "Prix clean", Long.class, AttributeType.QuoteConverter.class),
                    new Attribute("xapa.bond.dirty-price", "Prix dirty", Long.class, AttributeType.QuoteConverter.class),
                    new Attribute("xapa.bond.price-date", "Date du cours", LocalDate.class, AttributeType.DateConverter.class),
                    new Attribute("xapa.bond.price-source", "Source du cours", String.class, AttributeType.StringConverter.class));

    private BondUpdate()
    {
    }

    public static boolean managed(Security security)
    {
        var note = Objects.toString(security.getNote(), "");
        int first = note.indexOf(BEGIN), last = note.indexOf(END);
        return first >= 0 && last > first && note.substring(first, last).contains(CONVENTION)
                        && "MANUAL".equals(security.getFeed())
                        && (security.getLatestFeed() == null || "MANUAL".equals(security.getLatestFeed()));
    }

    public static State state(Security security)
    {
        var latest = security.getLatest();
        String latestState = latest == null ? null : latest.getDate() + ":" + latest.getValue() + ":" + latest.getHigh()
                        + ":" + latest.getLow() + ":" + latest.getVolume();
        return new State(security.getIsin(), security.getCurrencyCode(), security.getNote(), security.getFeed(), security.getFeedURL(),
                        security.getLatestFeed(), security.getLatestFeedURL(), security.getProperties().map(SecurityProperty::toString).sorted().toList(),
                        security.getPrices().stream().map(p -> new Point(p.getDate(), p.getValue())).toList(), latestState,
                        Collections.unmodifiableMap(new HashMap<>(security.getAttributes().getMap())), security.getUpdatedAt());
    }

    public static Plan prepare(Security security, List<BondQuote> quotes, boolean allowMigration)
    {
        if (BondSpec.find(security.getIsin()).isEmpty() || !"EUR".equals(security.getCurrencyCode()))
            throw new IllegalArgumentException("Obligation non prise en charge ou devise différente de EUR : " + security.getName());
        boolean migration = !managed(security) && !security.getPricesIncludingLatest().isEmpty();
        if (migration && !allowMigration)
            throw new IllegalArgumentException("La convention de " + security.getName() + " est inconnue. Autorisez explicitement le remplacement de son historique.");
        var ordered = new TreeMap<LocalDate, BondQuote>();
        for (var quote : quotes)
        {
            var previous = ordered.putIfAbsent(quote.date(), quote);
            if (previous != null && !previous.equals(quote))
                throw new IllegalArgumentException("Cours contradictoires pour le " + quote.date());
        }
        if (ordered.isEmpty())
            throw new IllegalArgumentException("Aucun cours reçu pour " + security.getName());
        var oldPrices = security.getPricesIncludingLatest();
        if (!oldPrices.isEmpty() && ordered.lastKey().isBefore(oldPrices.getLast().getDate()))
            throw new IllegalArgumentException("La source est plus ancienne que le dernier cours de " + security.getName() + ". Aucun recul de date n'est appliqué.");
        var prices = new TreeMap<LocalDate, Long>();
        if (!migration)
            oldPrices.forEach(p -> prices.put(p.getDate(), p.getValue()));
        ordered.forEach((date, quote) -> prices.put(date, quote.value()));
        int removed = (int) oldPrices.stream().filter(p -> !prices.containsKey(p.getDate())).count();
        return new Plan(security.getUUID(), state(security), new ArrayList<>(ordered.values()), migration,
                        prices.entrySet().stream().map(e -> new Point(e.getKey(), e.getValue())).toList(), removed);
    }

    public static Security security(Client client, String id)
    {
        return client.getSecurities().stream().filter(s -> s.getUUID().equals(id)).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Une obligation de l'aperçu n'existe plus."));
    }

    public static void apply(Client client, List<Plan> plans)
    {
        var ids = new HashSet<String>();
        for (var plan : plans)
        {
            var security = security(client, plan.securityId());
            if (!ids.add(plan.securityId()) || !state(security).equals(plan.before())
                            || !prepare(security, plan.quotes(), plan.migration()).equals(plan))
                throw new IllegalArgumentException("Le portefeuille a changé depuis l'aperçu. Relancez la mise à jour.");
        }
        // Validate all existing metadata definitions before any price or type is mutated.
        for (var definition : ATTRIBUTES)
        {
            var matches = client.getSettings().getAttributeTypes().filter(a -> a.getId().equals(definition.id())).toList();
            if (matches.size() > 1 || (!matches.isEmpty() && (matches.getFirst().getType() != definition.type()
                            || !matches.getFirst().supports(Security.class)
                            || matches.getFirst().getConverter().getClass() != definition.converter())))
                throw new IllegalArgumentException("Attribut obligataire incompatible : " + definition.id());
        }
        if (plans.isEmpty())
            return;
        var attributes = new ArrayList<AttributeType>();
        for (var definition : ATTRIBUTES)
        {
            var type = client.getSettings().getAttributeTypes().filter(a -> a.getId().equals(definition.id())).findFirst().orElse(null);
            if (type == null)
            {
                type = new AttributeType(definition.id());
                type.setName(definition.label());
                type.setColumnLabel(definition.label());
                type.setType(definition.type());
                type.setTarget(Security.class);
                type.setConverter(definition.converter());
                client.getSettings().addAttributeType(type);
            }
            attributes.add(type);
        }
        for (var plan : plans)
        {
            var security = security(client, plan.securityId());
            var last = plan.quotes().getLast();
            security.removeAllPrices();
            security.addAllPrices(plan.after().stream().map(p -> new SecurityPrice(p.date(), p.value())).toList());
            security.setLatest(new LatestSecurityPrice(last.date(), last.value(), -1, -1, -1));
            security.setFeed("MANUAL");
            security.setFeedURL(null);
            security.setLatestFeed("MANUAL");
            security.setLatestFeedURL(null);
            security.removePropertyIf(p -> p.getType() == SecurityProperty.Type.FEED);
            security.getAttributes().put(attributes.get(0), BondQuote.scaled(last.clean()));
            security.getAttributes().put(attributes.get(1), last.value());
            security.getAttributes().put(attributes.get(2), last.date());
            security.getAttributes().put(attributes.get(3), last.source());
            var note = Objects.toString(security.getNote(), "").replaceAll("(?s)\\n?\\[XAPA-BOND-PRICES-BEGIN\\].*?\\[XAPA-BOND-PRICES-END\\]\\n?", "").stripTrailing();
            var audit = BEGIN + "\n" + CONVENTION + "\nDate de cotation : " + last.date()
                            + "\nPrix clean : " + last.clean().toPlainString() + "\nCoupon couru : " + last.accrued().toPlainString()
                            + "\nPrix dirty : " + last.dirty().toPlainString() + "\nSource : " + last.source()
                            + "\nHistorique dirty : " + plan.after().getFirst().date() + " à " + last.date() + " (" + plan.after().size() + " points)"
                            + (last.coefficient() == null ? "\nFormule : prix clean + coupon couru (Bundesbank)"
                                            : "\nRèglement T+2 : " + last.settlement() + "\nCoefficient AFT : " + last.coefficient().toPlainString()
                                                            + "\nFormule : (clean + coupon couru ACT/ACT) × coefficient AFT")
                            + "\n" + END;
            security.setNote((note.isEmpty() ? "" : note + "\n\n") + audit);
            security.setUpdatedAt(Instant.now());
        }
        client.markDirty();
    }
}
