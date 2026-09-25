package name.abuchen.portfolio.ui.views.dashboard;

import static org.junit.Assert.*;
import java.time.LocalDate;
import java.util.List;
import org.junit.Test;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import name.abuchen.portfolio.commitments.Commitments;
import name.abuchen.portfolio.model.*;
import name.abuchen.portfolio.money.Values;
import name.abuchen.portfolio.ui.commitments.CommitmentColumns;

public class CommitmentWidgetTest
{
    private final Client client = new Client();
    private final Security security = new Security("PE Fund", "EUR");
    private final Dashboard.Widget widget = new Dashboard.Widget();
    private final Taxonomy taxonomy = new Taxonomy("Liquidity");
    public CommitmentWidgetTest()
    {
        client.setBaseCurrency("EUR"); client.addSecurity(security); widget.setLabel("Engagements");
        Commitments.save(client, security, 200000L, List.of(30000L, 30000L, 30000L, 90000L));
        var account = new Account("Cash"); account.setCurrencyCode("EUR"); client.addAccount(account);
        account.addTransaction(new AccountTransaction(LocalDate.now().atStartOfDay(), "EUR", 200000, null, AccountTransaction.Type.DEPOSIT));
        account.addTransaction(new AccountTransaction(LocalDate.now().atStartOfDay(), "EUR", 20000, security, AccountTransaction.Type.CAPITAL_CALL));
        var root = new Classification("reserves", Commitments.RESERVE_NAME); taxonomy.setRootNode(root); client.addTaxonomy(taxonomy);
        root.addAssignment(new Classification.Assignment(account));
    }
    @Test public void usesCurrentReservesAndDerivedAmountsWithoutMutatingInputs()
    {
        var data = new CommitmentWidget(widget, new DashboardData(client), false).getUpdateTask().get();
        assertNull(data.error()); assertEquals(Long.valueOf(180000), data.reserve());
        assertEquals(20000, data.summary().paid()); assertEquals(180000, data.summary().remaining()); assertTrue(data.summary().complete());
        assertFalse(security.getAttributes().getMap().containsKey(Commitments.PAID));
    }
    @Test public void duplicateReserveNamesRequireSelectionAndSavedIdsSurviveRename()
    {
        var duplicate = new Taxonomy("Another"); duplicate.setRootNode(new Classification("duplicate", Commitments.RESERVE_NAME)); client.addTaxonomy(duplicate);
        var missing = new CommitmentWidget(widget, new DashboardData(client), false).getUpdateTask().get();
        assertNull(missing.reserve()); assertTrue(missing.error().contains("Plusieurs"));
        widget.getConfiguration().put("PE_RESERVE_TAXONOMY", taxonomy.getId()); widget.getConfiguration().put("PE_RESERVE_CATEGORY", "reserves");
        taxonomy.getRoot().setName("Renamed");
        assertEquals(Long.valueOf(180000), new CommitmentWidget(widget, new DashboardData(client), false).getUpdateTask().get().reserve());
        client.removeTaxonomy(taxonomy);
        assertNull(new CommitmentWidget(widget, new DashboardData(client), false).getUpdateTask().get().reserve());
    }
    @Test public void calculatedAttributeColumnsAreReadOnlyAndReflectNewOperations()
    {
        var attribute = client.getSettings().getAttributeTypes().filter(a -> a.getId().equals(Commitments.REMAINING)).findFirst().orElseThrow();
        var column = CommitmentColumns.create(client, attribute, new DashboardData(client).getCurrencyConverter());
        assertFalse(column.getEditingSupport().canEdit(security));
        var provider = (ColumnLabelProvider) column.getLabelProvider().get(); assertEquals(Values.Amount.format(180000L), provider.getText(security));
        client.getAccounts().getFirst().addTransaction(new AccountTransaction(LocalDate.now().atStartOfDay(), "EUR", 30000, security, AccountTransaction.Type.CAPITAL_CALL));
        assertEquals(Values.Amount.format(150000L), provider.getText(security));
    }
}
