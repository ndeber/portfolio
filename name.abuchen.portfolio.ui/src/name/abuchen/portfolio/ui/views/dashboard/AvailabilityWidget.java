package name.abuchen.portfolio.ui.views.dashboard;

import java.time.LocalDate;
import java.util.function.Supplier;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.layout.*;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.*;
import name.abuchen.portfolio.commitments.Availability;
import name.abuchen.portfolio.commitments.AvailabilityPlan;
import name.abuchen.portfolio.ui.commitments.AvailabilityDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.window.Window;
import name.abuchen.portfolio.model.Dashboard.Widget;
import name.abuchen.portfolio.money.Values;
import name.abuchen.portfolio.ui.UIConstants;
import name.abuchen.portfolio.ui.util.SimpleAction;

public final class AvailabilityWidget extends WidgetDelegate<AvailabilityWidget.Data>
{
    public record Data(Availability.Result result, String error) { }
    private Label title, note;
    private Table table;
    public AvailabilityWidget(Widget widget, DashboardData data)
    {
        super(widget, data);
        addConfig(new WidgetConfig()
        {
            @Override public String getLabel() { return "Date de disponibilité — Xapa"; }
            @Override public void menuAboutToShow(IMenuManager manager)
            {
                manager.add(new SimpleAction("Renseigner les disponibilités Xapa…", a -> edit()));
                manager.add(new SimpleAction("Recalculer la taxonomie depuis le tableau", a -> {
                    try { AvailabilityPlan.apply(getClient(), AvailabilityPlan.load(getClient())); update(); }
                    catch (RuntimeException e) { MessageDialog.openError(Display.getCurrent().getActiveShell(), "Disponibilités Xapa", e.getMessage()); }
                }));
                for (var taxonomy : getClient().getTaxonomies())
                    manager.add(new SimpleAction(taxonomy.getName(), a -> {
                        getWidget().getConfiguration().put("AVAILABILITY_TAXONOMY", taxonomy.getId()); getClient().touch(); update();
                    }));
            }
        });
    }
    @Override public Composite createControl(Composite parent, DashboardResources resources)
    {
        var container = new Composite(parent, SWT.NONE); container.setData(UIConstants.CSS.CLASS_NAME, getContainerCssClassNames());
        container.setBackground(parent.getBackground()); container.setBackgroundMode(SWT.INHERIT_DEFAULT);
        GridLayoutFactory.fillDefaults().margins(5, 5).applyTo(container);
        title = new Label(container, SWT.NONE); title.setData(UIConstants.CSS.CLASS_NAME, UIConstants.CSS.TITLE);
        note = new Label(container, SWT.WRAP); GridDataFactory.fillDefaults().grab(true, false).hint(600, SWT.DEFAULT).applyTo(note);
        var edit = new Button(container, SWT.PUSH); edit.setText("Renseigner les disponibilités Xapa…"); edit.addListener(SWT.Selection, e -> edit());
        table = new Table(container, SWT.FULL_SELECTION | SWT.H_SCROLL | SWT.V_SCROLL); table.setHeaderVisible(true); table.setLinesVisible(true);
        GridDataFactory.fillDefaults().grab(true, false).hint(SWT.DEFAULT, 390).applyTo(table);
        String[] labels = {"Disponibilité", "Nouveaux EUR", "Cumul EUR", "Appels EUR", "Appels cumulés", "Solde EUR"};
        for (int i = 0; i < labels.length; i++) { var column = new TableColumn(table, i == 0 ? SWT.LEFT : SWT.RIGHT); column.setText(labels[i]); column.setWidth(i == 0 ? 165 : 115); }
        return container;
    }
    private void edit()
    {
        try { if (new AvailabilityDialog(Display.getCurrent().getActiveShell(), getClient(), getDashboardData().getCurrencyConverter()).open() == Window.OK) update(); }
        catch (RuntimeException e) { MessageDialog.openError(Display.getCurrent().getActiveShell(), "Disponibilités Xapa", e.getMessage()); }
    }
    @Override public Control getTitleControl() { return title; }
    @Override public Supplier<Data> getUpdateTask()
    {
        var id = getWidget().getConfiguration().get("AVAILABILITY_TAXONOMY");
        return () -> {
            try { return new Data(Availability.calculate(getClient(), Availability.taxonomy(getClient(), id), getDashboardData().getCurrencyConverter(), LocalDate.now()), null); }
            catch (RuntimeException e) { return new Data(null, e.getMessage()); }
        };
    }
    private static String amount(Long value) { return value == null ? "—" : Values.Amount.format(value); }
    private void line(String label, Long added, Long available, Long calls, Long cumulativeCalls, Long balance)
    {
        var item = new TableItem(table, SWT.NONE);
        item.setText(new String[] {label, amount(added), amount(available), amount(calls), amount(cumulativeCalls), amount(balance)});
        if (balance != null && balance < 0) item.setForeground(5, table.getDisplay().getSystemColor(SWT.COLOR_RED));
    }
    @Override public void update(Data data)
    {
        if (table == null || table.isDisposed()) return;
        title.setText(getWidget().getLabel()); table.removeAll();
        if (data == null || data.result() == null) { note.setText(data == null ? "Chargement…" : data.error()); return; }
        var result = data.result(); var commitments = result.commitments();
        boolean reliable = commitments.complete() && commitments.gap() == 0;
        note.setText("Comptes et dépôts « Xapa - » uniquement ; Phacet exclu. Valeurs actuelles en EUR, sans rendement futur.\n"
                        + "Les catégories sont les années de disponibilité, marge déjà incluse. Les appels sont détaillés de 2026 à 2033.\n"
                        + (reliable ? "" : "Appels incomplets ou non ventilés : les soldes de couverture ne sont pas calculés. ")
                        + "À planifier : " + amount(result.amount(Availability.UNPLANNED)) + " EUR, exclus des disponibilités cumulées.");
        long available = result.amount(Availability.IMMEDIATE), calls = 0;
        line("Immédiate", available, available, null, null, null);
        // Older dated tranches remain labelled as such: no silent relabelling to cash.
        for (var entry : result.amounts().entrySet())
            if (entry.getKey() > 0 && entry.getKey() < 2026) available = Math.addExact(available, entry.getValue());
        int last = Math.max(2034, result.amounts().keySet().stream().max(Integer::compare).orElse(2034));
        for (int year = 2026; year <= last; year++)
        {
            long added = result.amount(year); available = Math.addExact(available, added);
            Long annual = year <= 2033 ? commitments.forecast().get(year - 2026) : null;
            if (annual != null) calls = Math.addExact(calls, annual);
            line(Integer.toString(year), added, available, annual, annual == null ? null : calls,
                            annual == null || !reliable ? null : Math.subtractExact(available, calls));
        }
        line("À planifier", result.amount(Availability.UNPLANNED), null, null, null, null);
        line("Total actifs Xapa", result.total(), null, null, null, null);
        note.getParent().layout(true, true);
    }
}
