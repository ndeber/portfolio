package name.abuchen.portfolio.ui.tax;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import name.abuchen.portfolio.model.Classification;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.money.CurrencyConverter;
import name.abuchen.portfolio.snapshot.filter.ClientFilter;
import name.abuchen.portfolio.tax.TaxEstimateSettings;
import name.abuchen.portfolio.tax.TaxPerformance;
import name.abuchen.portfolio.tax.TaxScope;
import name.abuchen.portfolio.ui.views.StatementOfAssetsViewer.Element;
import name.abuchen.portfolio.util.Interval;

/** The native asset table and dashboard call the same scope-level engine. */
@SuppressWarnings("nls")
public final class StatementOfAssetsTaxValues
{
    public enum Metric
    {
        LATENT, DEBT, NET_VALUE, BEFORE, ACCRUED, CHANGE, AFTER, PERCENT, TWR, IRR, ANNUALIZED
    }

    public record Cell(Long amount, String detail)
    {
    }

    private record Key(Element element, Interval interval)
    {
    }

    private final Client client;
    private final ClientFilter filter;
    private final name.abuchen.portfolio.model.Taxonomy taxonomy;
    private final LocalDate date;
    private final TaxPerformance.Session session;
    private final Map<Key, TaxPerformance.Result> cache = new HashMap<>();

    public StatementOfAssetsTaxValues(Client client, ClientFilter filter, CurrencyConverter converter, LocalDate date, name.abuchen.portfolio.model.Taxonomy taxonomy)
    {
        this.client = client;
        this.filter = filter;
        this.taxonomy = taxonomy;
        this.date = date;
        session = new TaxPerformance.Session(client, converter);
    }

    public LocalDate defaultStart()
    {
        try
        {
            var scope = TaxScope.of(client, filter, null);
            return name.abuchen.portfolio.tax.TaxEnvelopeProfiles.effectiveSettings(client).positions().entrySet().stream()
                            .filter(e -> scope.positions().containsKey(e.getKey()))
                            .map(e -> LocalDate.parse(e.getValue().validatedOn())).filter(d -> !d.isAfter(date))
                            .max(LocalDate::compareTo).orElse(date);
        }
        catch (RuntimeException e)
        {
            return date;
        }
    }

    public TaxPerformance.Result performance(Element element, Interval interval)
    {
        return cache.computeIfAbsent(new Key(element, interval), key -> {
            try
            {
                TaxScope scope;
                if (element.isSecurity())
                {
                    scope = TaxScope.security(client, filter, element.getSecurity());
                }
                else if (element.isAccount())
                {
                    var assignment = new Classification("tax-account", element.getAccount().getName());
                    assignment.addAssignment(new Classification.Assignment(element.getAccount()));
                    scope = TaxScope.of(client, filter, assignment);
                }
                else
                    scope = TaxScope.of(client, filter, element.isCategory() ? classification(element) : null);
                return session.calculate(scope, interval);
            }
            catch (RuntimeException e)
            {
                return TaxPerformance.Result.unavailable(e.getMessage());
            }
        });
    }

    public Cell value(Element element, Metric metric, Interval interval)
    {
        var result = performance(element, metric == Metric.LATENT || metric == Metric.DEBT || metric == Metric.NET_VALUE
                        ? Interval.of(date, date) : interval);
        Long amount = switch (metric)
        {
            case LATENT -> result.latent();
            case DEBT -> result.debt();
            case NET_VALUE -> result.netValue();
            case BEFORE -> result.before().gain();
            case AFTER -> result.after().gain();
            case ACCRUED -> result.after().gain() == null ? null : result.events().stream().mapToLong(e -> e.tax()).sum();
            case CHANGE -> {
                var opening = performance(element, Interval.of(interval.getStart(), interval.getStart()));
                yield opening.latent() == null || result.latent() == null ? null : result.latent() - opening.latent();
            }
            default -> null;
        };
        if (amount != null && element.isSecurity())
            amount = split(element, amount);
        return new Cell(amount, result.detail());
    }

    private static long split(Element element, long amount)
    {
        var positions = element.getGroupByTaxonomy().getCategories().flatMap(c -> c.getPositions().stream())
                        .filter(p -> p.getSecurity() == element.getSecurity()).toList();
        long total = positions.stream().mapToLong(p -> p.getPosition().getShares()).sum();
        long preceding = 0;
        for (var position : positions)
        {
            long through = preceding + position.getPosition().getShares();
            if (position == element.getPosition())
                return rounded(amount, through, total) - rounded(amount, preceding, total);
            preceding = through;
        }
        throw new IllegalArgumentException("Répartition de la position absente.");
    }

    private static long rounded(long amount, long part, long total)
    {
        return java.math.BigDecimal.valueOf(amount).multiply(java.math.BigDecimal.valueOf(part))
                        .divide(java.math.BigDecimal.valueOf(total), 0, java.math.RoundingMode.HALF_UP).longValueExact();
    }

    private Classification classification(Element element)
    {
        var original = element.getCategory().getClassification();
        if (!Classification.UNASSIGNED_ID.equals(original.getId()))
            return original;
        // Include historical assets even after a full sale or a cash account closing at zero.
        var assigned = new HashMap<name.abuchen.portfolio.model.InvestmentVehicle, Integer>();
        if (taxonomy != null)
            for (var child : taxonomy.getRoot().getChildren())
                child.accept(new name.abuchen.portfolio.model.Taxonomy.Visitor()
                {
                    @Override
                    public void visit(Classification node, Classification.Assignment assignment)
                    {
                        assigned.merge(assignment.getInvestmentVehicle(), assignment.getWeight(), Math::addExact);
                    }
                });
        var result = new Classification("tax-unassigned", original.getName());
        java.util.stream.Stream.concat(client.getSecurities().stream(), client.getAccounts().stream()).forEach(vehicle -> {
            int remaining = Classification.ONE_HUNDRED_PERCENT - assigned.getOrDefault(vehicle, 0);
            if (remaining > 0)
                result.addAssignment(new Classification.Assignment(vehicle, remaining));
        });
        return result;
    }

    public Double rate(Element element, Metric metric, Interval interval)
    {
        var result = performance(element, interval).after();
        return switch (metric)
        {
            case PERCENT -> result.percent();
            case TWR -> result.twr();
            case IRR -> result.irr();
            case ANNUALIZED -> result.annualized();
            default -> null;
        };
    }
}
