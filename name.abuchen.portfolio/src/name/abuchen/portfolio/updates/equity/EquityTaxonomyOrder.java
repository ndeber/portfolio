package name.abuchen.portfolio.updates.equity;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Locale;
import java.util.List;
import name.abuchen.portfolio.model.Taxonomy;
import java.util.Map;

import name.abuchen.portfolio.model.Classification;
import name.abuchen.portfolio.model.Classification.Assignment;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.updates.equity.EquityComposition.Family;
import name.abuchen.portfolio.util.TextUtil;

/** Persist the category order using current, common-currency portfolio valuations. */
public final class EquityTaxonomyOrder
{
    private EquityTaxonomyOrder() { }

    public static void apply(Client client, Map<String, Long> valuations)
    {
        apply(client, java.util.Arrays.stream(Family.values()).map(f -> EquityAdjustment.taxonomy(client, f)).toList(), valuations);
    }

    public static void apply(Client client, List<Taxonomy> taxonomies, Map<String, Long> valuations)
    {
        var amounts = new HashMap<Classification, Long>();
        // Compute all amounts before modifying any order. Assignment rounding matches TaxonomyModel.
        for (var taxonomy : taxonomies)
            total(taxonomy.getRoot(), valuations, amounts);
        var order = Comparator.comparing((Classification c) -> isOther(c.getName()))
                        .thenComparing(Comparator.comparingLong((Classification c) -> amounts.get(c)).reversed())
                        .thenComparing(Classification::getName, TextUtil::compare)
                        .thenComparing(Classification::getId);
        for (var taxonomy : taxonomies)
        {
            sort(taxonomy.getRoot(), order);
            taxonomy.notifyAssignmentsChanged();
        }
        client.markDirty();
    }

    private static boolean isOther(String name)
    {
        return switch (name.strip().toLowerCase(Locale.ROOT).replaceFirst("^.* - ", ""))
        {
            case "other", "others", "autre", "autres" -> true;
            default -> false;
        };
    }

    private static long total(Classification category, Map<String, Long> valuations, Map<Classification, Long> amounts)
    {
        long amount = 0;
        for (var assignment : category.getAssignments())
            amount = Math.addExact(amount, Math.round(valuations.getOrDefault(assignment.getInvestmentVehicle().getUUID(), 0L)
                            * assignment.getWeight() / (double) Classification.ONE_HUNDRED_PERCENT));
        for (var child : category.getChildren())
            amount = Math.addExact(amount, total(child, valuations, amounts));
        amounts.put(category, amount);
        return amount;
    }

    private static void sort(Classification parent, Comparator<Classification> order)
    {
        parent.getChildren().sort(order);
        int rank = 0;
        for (var child : parent.getChildren())
        {
            child.setRank(rank++);
            sort(child, order);
        }
        // Categories precede positions; retain the existing relative order of positions.
        for (var assignment : parent.getAssignments().stream().sorted(Comparator.comparingInt(Assignment::getRank)).toList())
            assignment.setRank(rank++);
    }
}
