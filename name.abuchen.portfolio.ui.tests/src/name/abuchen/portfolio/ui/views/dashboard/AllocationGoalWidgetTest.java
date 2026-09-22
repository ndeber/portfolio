package name.abuchen.portfolio.ui.views.dashboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.time.LocalDate;

import org.junit.Test;

import name.abuchen.portfolio.model.Account;
import name.abuchen.portfolio.model.AccountTransaction;
import name.abuchen.portfolio.model.Classification;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Dashboard;
import name.abuchen.portfolio.model.Taxonomy;

@SuppressWarnings("nls")
public class AllocationGoalWidgetTest
{
    private final Client client = new Client();
    private final Taxonomy taxonomy = new Taxonomy("Allocation");
    private final Classification root = new Classification(null, "root", "Portfolio");
    private final Classification cashCategory = new Classification(root, "cash", "Cash");
    private final Dashboard.Widget widget = new Dashboard.Widget();

    public AllocationGoalWidgetTest()
    {
        client.setBaseCurrency("EUR");
        root.setWeight(Classification.ONE_HUNDRED_PERCENT);
        cashCategory.setWeight(Classification.ONE_HUNDRED_PERCENT * 60 / 100);
        root.addChild(cashCategory);
        var other = new Classification(root, "other", "Other");
        other.setWeight(Classification.ONE_HUNDRED_PERCENT * 40 / 100);
        root.addChild(other);
        taxonomy.setRootNode(root);
        client.addTaxonomy(taxonomy);
        var account = new Account("Cash");
        account.addTransaction(new AccountTransaction(LocalDate.now().minusDays(1).atStartOfDay(), "EUR", 100000,
                        null, AccountTransaction.Type.DEPOSIT));
        client.addAccount(account);
        cashCategory.addAssignment(new Classification.Assignment(account, Classification.ONE_HUNDRED_PERCENT));
        widget.setLabel("Allocation");
        widget.getConfiguration().put(Dashboard.Config.TAXONOMY.name(), taxonomy.getId());
    }

    @Test
    public void allocationUsesActualValuationAndConfiguredWeights()
    {
        var data = new AllocationGoalWidget(widget, new DashboardData(client), false).getUpdateTask().get();
        assertEquals(2, data.rows().size());
        assertEquals(100000, data.rows().get(0).actual().getAmount());
        assertEquals(1d, data.rows().get(0).actualWeight(), 1e-9);
        assertEquals(.6, data.rows().get(0).targetWeight(), 1e-9);
        assertEquals(.4, data.rows().get(1).targetWeight(), 1e-9);
    }

    @Test
    public void subtotalIncludesChildrenAndKeepsTargetInEuroCents()
    {
        widget.getConfiguration().put("ALLOCATION_GOAL_CATEGORY", cashCategory.getId());
        widget.getConfiguration().put("ALLOCATION_GOAL_EUR_TARGET", "200000");
        var data = new AllocationGoalWidget(widget, new DashboardData(client), true).getUpdateTask().get();
        assertEquals("Cash", data.category());
        assertEquals("EUR", data.current().getCurrencyCode());
        assertEquals(100000, data.current().getAmount());
        assertEquals(Long.valueOf(200000), data.target());
    }

    @Test
    public void deletedCategoryDoesNotSilentlyFallBackToEntirePortfolio()
    {
        widget.getConfiguration().put("ALLOCATION_GOAL_CATEGORY", "deleted");
        assertNull(new AllocationGoalWidget(widget, new DashboardData(client), true).getUpdateTask().get());
    }

    @Test
    public void emptyPortfolioStillShowsTargetsAndDoesNotDivideByZero()
    {
        client.getAccounts().get(0).getTransactions().get(0).setAmount(0);
        var data = new AllocationGoalWidget(widget, new DashboardData(client), false).getUpdateTask().get();
        assertNull(data.rows().get(0).actualWeight());
        assertEquals(.6, data.rows().get(0).targetWeight(), 1e-9);
        assertNull(data.target());
    }
}
