package name.abuchen.portfolio.ui.views.dashboard;

import static org.junit.Assert.*;
import java.time.LocalDate;
import org.junit.Test;
import name.abuchen.portfolio.commitments.Availability;
import name.abuchen.portfolio.model.*;

public class AvailabilityWidgetTest
{
    @Test public void readsTaxonomyAndOnlyXapaCash()
    {
        var client = new Client(); client.setBaseCurrency("EUR");
        var account = new Account("Xapa - Bank"); account.setCurrencyCode("EUR"); client.addAccount(account);
        account.addTransaction(new AccountTransaction(LocalDate.now().atStartOfDay(), "EUR", 100000, null, AccountTransaction.Type.DEPOSIT));
        var taxonomy = new Taxonomy(Availability.NAME); var root = new Classification("root", Availability.NAME); taxonomy.setRootNode(root); client.addTaxonomy(taxonomy);
        var immediate = new Classification(root, "now", "Immédiate"); root.addChild(immediate); immediate.addAssignment(new Classification.Assignment(account));
        var widget = new Dashboard.Widget(); var delegate = new AvailabilityWidget(widget, new DashboardData(client));
        var data = delegate.getUpdateTask().get(); assertNull(data.error()); assertEquals(100000, data.result().amount(0));
        name.abuchen.portfolio.commitments.AvailabilityPlan.apply(client, name.abuchen.portfolio.commitments.AvailabilityPlan.load(client));
        immediate.getAssignments().clear();
        assertEquals(100000, delegate.getUpdateTask().get().result().amount(0));
        widget.getConfiguration().put("AVAILABILITY_TAXONOMY", taxonomy.getId()); taxonomy.setName("Renamed");
        assertNull(delegate.getUpdateTask().get().error());
        client.removeTaxonomy(taxonomy); assertNotNull(delegate.getUpdateTask().get().error());
    }
    @Test public void neverFallsBackToWholePortfolioForMissingTaxonomy()
    {
        var data = new AvailabilityWidget(new Dashboard.Widget(), new DashboardData(new Client())).getUpdateTask().get();
        assertNull(data.result()); assertTrue(data.error().contains("introuvable"));
    }
}
