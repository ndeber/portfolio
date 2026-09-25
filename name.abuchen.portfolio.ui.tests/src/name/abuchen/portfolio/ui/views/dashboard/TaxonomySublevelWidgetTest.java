package name.abuchen.portfolio.ui.views.dashboard;

import static org.junit.Assert.*;
import java.time.LocalDate;
import org.junit.Test;
import name.abuchen.portfolio.model.*;

@SuppressWarnings("nls")
public class TaxonomySublevelWidgetTest
{
    @Test public void localTargetsAreIndependentOfParentsTargetAndActualValue()
    {
        var client = new Client(); client.setBaseCurrency("EUR");
        var taxonomy = new Taxonomy("Allocation");
        var root = new Classification(null, "root", "All"); root.setWeight(10000);
        var branch = new Classification(root, "branch", "Selected"); branch.setWeight(2000); root.addChild(branch);
        var a = new Classification(branch, "a", "A"); a.setWeight(6000); branch.addChild(a);
        var b = new Classification(branch, "b", "B"); b.setWeight(4000); branch.addChild(b);
        taxonomy.setRootNode(root); client.addTaxonomy(taxonomy);
        var cash = new Account("Cash"); client.addAccount(cash);
        cash.addTransaction(new AccountTransaction(LocalDate.now().minusDays(1).atStartOfDay(), "EUR", 100000, null, AccountTransaction.Type.DEPOSIT));
        a.addAssignment(new Classification.Assignment(cash, 10000));
        var w = new Dashboard.Widget(); w.getConfiguration().put("TAXONOMY", taxonomy.getId());
        w.getConfiguration().put(TaxonomySublevelWidget.CATEGORY, branch.getId());
        var delegate = new TaxonomySublevelWidget(w, new DashboardData(client));
        var data = delegate.getUpdateTask().get();
        assertEquals("Selected", data.name()); assertEquals(100000, data.actual().getAmount());
        assertEquals(.6, data.slices().get(0).target(), 1e-9); assertEquals(.4, data.slices().get(1).target(), 1e-9);
        assertTrue(data.targetValid()); assertTrue(data.actualValid());
        cash.getTransactions().getFirst().setAmount(0);
        data = delegate.getUpdateTask().get(); assertEquals(0, data.actual().getAmount());
        assertEquals(.6, data.slices().get(0).target(), 1e-9);
        b.setWeight(5000); assertFalse(delegate.getUpdateTask().get().targetValid());
        b.setWeight(2000); data = delegate.getUpdateTask().get();
        assertEquals(.2, data.slices().getLast().target(), 1e-9);
        w.getConfiguration().put(TaxonomySublevelWidget.CATEGORY, "deleted");
        assertNull(delegate.getUpdateTask().get());
    }
    @Test public void onlySelectedLevelAndOptionalUnclassifiedExclusion()
    {
        var client = new Client(); client.setBaseCurrency("EUR");
        var taxonomy = new Taxonomy("Allocation");
        var root = new Classification(null, "root", "All"); root.setWeight(10000);
        var a = new Classification(root, "a", "Actions"); a.setWeight(8000); a.setColor("#12ABCD"); root.addChild(a);
        var sub = new Classification(a, "sub", "Sous-niveau"); sub.setWeight(12000); a.addChild(sub);
        var unclassified = new Classification(root, "u", "Sans classification"); unclassified.setWeight(2000); root.addChild(unclassified);
        taxonomy.setRootNode(root); client.addTaxonomy(taxonomy);
        var cash = new Account("Cash"); client.addAccount(cash);
        cash.addTransaction(new AccountTransaction(LocalDate.now().minusDays(1).atStartOfDay(), "EUR", 100000, null, AccountTransaction.Type.DEPOSIT));
        sub.addAssignment(new Classification.Assignment(cash, 7500));
        unclassified.addAssignment(new Classification.Assignment(cash, 2500));
        var w = new Dashboard.Widget(); w.getConfiguration().put("TAXONOMY", taxonomy.getId());
        var delegate = new TaxonomySublevelWidget(w, new DashboardData(client));
        var data = delegate.getUpdateTask().get();
        assertEquals(1, data.slices().size()); assertTrue(data.slices().getFirst().children().isEmpty());
        assertEquals("#12ABCD", data.slices().getFirst().color());
        assertTrue(data.targetValid()); // Descendant targets do not invalidate this level.
        assertEquals(75000, data.actual().getAmount()); assertEquals(1d, data.slices().getFirst().target(), 1e-9);
        w.getConfiguration().put(TaxonomySublevelWidget.HIDE_UNCLASSIFIED, "false");
        data = delegate.getUpdateTask().get();
        assertEquals(2, data.slices().size()); assertEquals(100000, data.actual().getAmount());
        assertEquals(.8, data.slices().getFirst().target(), 1e-9);
        assertEquals(2, root.getChildren().size()); assertEquals(2000, unclassified.getWeight());
        w.getConfiguration().put(TaxonomySublevelWidget.CATEGORY, sub.getId());
        var leaf = new TaxonomySublevelWidget(w, new DashboardData(client), true).getUpdateTask().get();
        assertEquals(1, leaf.slices().size()); assertEquals("Sous-niveau", leaf.slices().getFirst().name());
        assertEquals(75000, leaf.actual().getAmount()); assertEquals(1d, leaf.slices().getFirst().target(), 1e-9);
        assertTrue(leaf.targetValid());
    }

}
