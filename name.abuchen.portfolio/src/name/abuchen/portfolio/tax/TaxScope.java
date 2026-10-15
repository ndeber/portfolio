package name.abuchen.portfolio.tax;

import java.util.HashMap;
import java.util.Map;

import name.abuchen.portfolio.model.Account;
import name.abuchen.portfolio.model.Classification;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.InvestmentVehicle;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.model.Taxonomy;
import name.abuchen.portfolio.snapshot.filter.ClientClassificationFilter;
import name.abuchen.portfolio.snapshot.filter.ClientFilter;
import name.abuchen.portfolio.snapshot.filter.PortfolioClientFilter;

/** Explicit account/security weights, shared by table rows and dashboard scopes. */
@SuppressWarnings("nls")
public record TaxScope(ClientFilter filter, Map<String, Double> positions, Map<String, Double> accounts)
{
    public TaxScope
    {
        positions = Map.copyOf(positions);
        accounts = Map.copyOf(accounts);
        if (java.util.stream.Stream.concat(positions.values().stream(), accounts.values().stream())
                        .anyMatch(w -> !Double.isFinite(w) || w < 0 || w > 1))
            throw new IllegalArgumentException("La pondération fiscale de chaque actif doit être comprise entre 0 et 100 %.");
    }

    public static TaxScope of(Client client, ClientFilter filter, Classification classification)
    {
        if (filter != ClientFilter.NO_FILTER && !(filter instanceof PortfolioClientFilter))
            throw new IllegalArgumentException("Périmètre fiscal non pris en charge : choisir des comptes ou une classification.");
        Map<InvestmentVehicle, Double> assignments = new HashMap<>();
        if (classification != null)
            classification.accept(new Taxonomy.Visitor()
            {
                @Override
                public void visit(Classification node, Classification.Assignment assignment)
                {
                    assignments.merge(assignment.getInvestmentVehicle(),
                                    assignment.getWeight() / (double) Classification.ONE_HUNDRED_PERCENT, Double::sum);
                }
            });
        Map<String, Double> positions = new HashMap<>();
        for (var portfolio : client.getPortfolios())
        {
            double weight = weight(filter, portfolio);
            for (var security : portfolio.getTransactions().stream().map(t -> t.getSecurity()).distinct().toList())
            {
                double factor = weight * (classification == null ? 1 : assignments.getOrDefault(security, 0d));
                if (factor > 0)
                    positions.put(TaxEstimateSettings.key(portfolio.getUUID(), security.getUUID()), factor);
            }
        }
        Map<String, Double> accounts = new HashMap<>();
        for (Account account : client.getAccounts())
        {
            double factor = weight(filter, account) * (classification == null ? 1 : assignments.getOrDefault(account, 0d));
            if (factor > 0)
                accounts.put(account.getUUID(), factor);
        }
        var selected = classification == null ? filter
                        : (ClientFilter) c -> new ClientClassificationFilter(classification).filter(filter.filter(c));
        return new TaxScope(selected, positions, accounts);
    }

    public static TaxScope security(Client client, ClientFilter filter, Security security)
    {
        var classification = new Classification("tax-security", security.getName());
        classification.addAssignment(new Classification.Assignment(security));
        return of(client, filter, classification);
    }

    private static double weight(ClientFilter filter, Object object)
    {
        if (filter instanceof PortfolioClientFilter selection)
            return selection.hasElement(object) ? selection.getWeight(object) / (double) Classification.ONE_HUNDRED_PERCENT : 0;
        return 1;
    }
}
