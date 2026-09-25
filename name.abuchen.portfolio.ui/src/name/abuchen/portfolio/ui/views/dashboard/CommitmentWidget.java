package name.abuchen.portfolio.ui.views.dashboard;

import java.time.LocalDate;
import java.util.List;
import java.util.function.Supplier;
import org.eclipse.jface.action.*;
import org.eclipse.jface.layout.*;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.*;
import name.abuchen.portfolio.commitments.Commitments;
import name.abuchen.portfolio.commitments.AssetClasses;
import name.abuchen.portfolio.model.Dashboard.Widget;
import name.abuchen.portfolio.money.Values;
import name.abuchen.portfolio.ui.UIConstants;
import name.abuchen.portfolio.ui.commitments.CommitmentDialog;
import name.abuchen.portfolio.ui.util.SimpleAction;

public final class CommitmentWidget extends WidgetDelegate<CommitmentWidget.Data>
{
    public record Data(Commitments.Summary summary, Long reserve, String reserveLabel, String error) { }
    private static final String TAXONOMY = "PE_RESERVE_TAXONOMY", CATEGORY = "PE_RESERVE_CATEGORY";
    private final boolean detail;
    private Label title, note;
    private Table table;
    public CommitmentWidget(Widget widget, DashboardData data, boolean detail)
    {
        super(widget, data); this.detail = detail;
        addConfig(new WidgetConfig()
        {
            @Override public String getLabel() { return "Engagements et réserves"; }
            @Override public void menuAboutToShow(IMenuManager manager)
            {
                manager.add(new SimpleAction("Renseigner les engagements PE…", a -> {
                    if (new CommitmentDialog(Display.getCurrent().getActiveShell(), getClient(), getDashboardData().getCurrencyConverter()).open() == Window.OK) update();
                }));
                if (!detail)
                {
                    var menu = new MenuManager("Catégorie des réserves");
                    menu.add(new SimpleAction("Détection par nom : " + Commitments.RESERVE_NAME, a -> {
                        getWidget().getConfiguration().remove(TAXONOMY); getWidget().getConfiguration().remove(CATEGORY); getClient().touch(); update();
                    }));
                    for (var candidate : Commitments.reserves(getClient()))
                        menu.add(new SimpleAction(candidate.path(), a -> {
                            getWidget().getConfiguration().put(TAXONOMY, candidate.taxonomyId()); getWidget().getConfiguration().put(CATEGORY, candidate.categoryId()); getClient().touch(); update();
                        }));
                    manager.add(menu);
                }
            }
        });
    }
    @Override public Composite createControl(Composite parent, DashboardResources resources)
    {
        var container = new Composite(parent, SWT.NONE); container.setData(UIConstants.CSS.CLASS_NAME, getContainerCssClassNames());
        container.setBackground(parent.getBackground()); container.setBackgroundMode(SWT.INHERIT_DEFAULT); GridLayoutFactory.fillDefaults().margins(5, 5).applyTo(container);
        title = new Label(container, SWT.NONE); title.setData(UIConstants.CSS.CLASS_NAME, UIConstants.CSS.TITLE);
        note = new Label(container, SWT.WRAP); GridDataFactory.fillDefaults().grab(true, false).hint(400, SWT.DEFAULT).applyTo(note);
        if (detail)
        {
            var expand = new Button(container, SWT.PUSH); expand.setText("Agrandir le tableau — années et totaux");
            expand.addListener(SWT.Selection, e -> {
                var shell = new Shell(container.getShell(), SWT.SHELL_TRIM | SWT.RESIZE);
                shell.setText("Engagements PE — détail en EUR"); shell.setLayout(new org.eclipse.swt.layout.FillLayout());
                var expanded = new Table(shell, SWT.FULL_SELECTION | SWT.H_SCROLL | SWT.V_SCROLL);
                expanded.setHeaderVisible(true); expanded.setLinesVisible(true);
                for (var source : table.getColumns())
                {
                    var column = new TableColumn(expanded, source.getAlignment()); column.setText(source.getText()); column.setWidth(source.getWidth());
                }
                for (var source : table.getItems())
                {
                    var row = new TableItem(expanded, SWT.NONE);
                    for (int i = 0; i < table.getColumnCount(); i++) row.setText(i, source.getText(i));
                }
                var bounds = container.getMonitor().getClientArea();
                shell.setSize(Math.min(1350, bounds.width), Math.min(650, bounds.height)); shell.open();
            });
        }
        table = new Table(container, SWT.FULL_SELECTION | SWT.H_SCROLL | SWT.V_SCROLL); table.setHeaderVisible(true); table.setLinesVisible(true);
        GridDataFactory.fillDefaults().grab(true, false).hint(SWT.DEFAULT, detail ? 300 : 280).applyTo(table);
        var columns = new java.util.ArrayList<String>();
        if (detail) { columns.addAll(List.of("Type", "Fonds", "Engagé", "Réalisé", "Restant")); columns.addAll(Commitments.LABELS); columns.addAll(List.of("Total prévisions", "Non ventilé", "À vérifier")); }
        else columns.addAll(List.of("Indicateur / échéance", "Montant EUR", "Réserves après appels EUR"));
        String[] labels = columns.toArray(String[]::new);
        for (int i = 0; i < labels.length; i++) { var c = new TableColumn(table, i <= (detail ? 1 : 0) ? SWT.LEFT : SWT.RIGHT); c.setText(labels[i]); c.setWidth(detail ? (i == 1 ? 200 : 100) : (i == 0 ? 200 : 180)); }
        return container;
    }
    @Override public Control getTitleControl() { return title; }
    private Commitments.Reserve selectedReserve()
    {
        String taxonomyId = getWidget().getConfiguration().get(TAXONOMY), categoryId = getWidget().getConfiguration().get(CATEGORY);
        if (taxonomyId != null && categoryId != null)
        {
            var taxonomy = getClient().getTaxonomies().stream().filter(t -> t.getId().equals(taxonomyId)).findFirst().orElseThrow(() -> new IllegalArgumentException("Taxonomie des réserves supprimée : sélection à refaire."));
            var category = taxonomy.getAllClassifications().stream().filter(c -> c.getId().equals(categoryId)).findFirst().orElseThrow(() -> new IllegalArgumentException("Catégorie des réserves supprimée : sélection à refaire."));
            return new Commitments.Reserve(taxonomyId, categoryId, taxonomy.getName() + " / " + category.getName());
        }
        var matches = Commitments.reserves(getClient());
        if (matches.size() != 1) throw new IllegalArgumentException(matches.isEmpty() ? "Catégorie « Réserves appels de fonds » introuvable."
                        : "Plusieurs catégories de réserves : choisir la taxonomie dans le menu du widget.");
        return matches.getFirst();
    }
    @Override public Supplier<Data> getUpdateTask()
    {
        Commitments.Reserve selected = null; String selectionError = null;
        if (!detail) try { selected = selectedReserve(); } catch (RuntimeException e) { selectionError = e.getMessage(); }
        var reserve = selected; var message = selectionError;
        return () -> {
            Commitments.Summary summary;
            try { summary = Commitments.summary(getClient(), getDashboardData().getCurrencyConverter(), LocalDate.now()); }
            catch (RuntimeException e) { return new Data(null, null, "", "Calcul impossible : " + e.getMessage()); }
            if (detail || reserve == null) return new Data(summary, null, "", message);
            try { return new Data(summary, Commitments.reserve(getClient(), reserve, getDashboardData().getCurrencyConverter(), LocalDate.now()), reserve.path(), null); }
            catch (RuntimeException e) { return new Data(summary, null, reserve.path(), "Réserves non valorisables : " + e.getMessage()); }
        };
    }
    private static String amount(Long value) { return value == null ? "—" : Values.Amount.format(value); }
    private void line(String... cells) { var row = new TableItem(table, SWT.NONE); row.setText(cells); }
    private void detailLine(String type, String label, Long total, Long paid, Long remaining, List<Long> forecasts, Long gap, String warnings)
    {
        var cells = new java.util.ArrayList<>(List.of(type, label, amount(total), amount(paid), amount(remaining)));
        forecasts.forEach(v -> cells.add(v == 0 ? "" : amount(v)));
        cells.add(amount(forecasts.stream().reduce(0L, Math::addExact))); cells.add(amount(gap)); cells.add(warnings);
        line(cells.toArray(String[]::new));
    }
    @Override public void update(Data data)
    {
        if (table == null || table.isDisposed()) return;
        title.setText(getWidget().getLabel()); table.removeAll();
        if (data == null || data.summary() == null) { note.setText(data == null ? "Renseigner les engagements via le menu du widget." : data.error()); return; }
        var s = data.summary();
        long warnings = s.rows().stream().filter(r -> !r.warnings().isEmpty()).count();
        String status = s.rows().isEmpty() ? "Renseigner les engagements via le menu du widget ou Outils du portefeuille."
                        : (s.complete() ? "" : "Totaux partiels ou incohérents : renseigner/vérifier tous les fonds. ") + warnings + " fonds à vérifier.";
        note.setText(status + (data.error() == null ? "" : "\n" + data.error()) + (!detail ? "\n" + data.reserveLabel() + "\nPrévisions du restant ; réserves actuelles, sans revenus futurs supposés." : "\nMontants en EUR. Faites défiler horizontalement pour voir toutes les colonnes. Les dates sont fixes ; mettre à jour l'échéancier après chaque appel."));
        if (detail)
        {
            detailLine("", s.complete() ? "TOTAL" : "TOTAL RENSEIGNÉ", s.total(), s.paid(), s.remaining(), s.forecast(), s.gap(), "");
            for (var row : s.rows()) detailLine(AssetClasses.type(getClient(), row.security()).label(), row.security().getName(), row.total(), row.paid(), row.remaining(), row.forecast(), row.gap(), String.join(" ; ", row.warnings()));
        }
        else
        {
            line("Engagement total renseigné", amount(s.total()), ""); line("Engagement réalisé", amount(s.paid()), ""); line("Engagement restant", amount(s.remaining()), "");
            line("Réserves disponibles", amount(data.reserve()), "");
            Long surplus = s.complete() && data.reserve() != null ? Math.subtractExact(data.reserve(), s.remaining()) : null;
            line(surplus != null && surplus < 0 ? "Manque de financement" : "Surplus de réserves", surplus == null ? "—" : amount(Math.abs(surplus)), "");
            line("Couverture du restant", !s.complete() || data.reserve() == null || s.remaining() <= 0 ? "—" : String.format(java.util.Locale.FRANCE, "%.1f %%", 100.0 * data.reserve() / s.remaining()), "");
            long cumulative = 0;
            for (int i = 0; i < Commitments.YEARS.size(); i++)
            {
                cumulative = Math.addExact(cumulative, s.forecast().get(i));
                line("Appels prévus " + Commitments.LABELS.get(i), amount(s.forecast().get(i)), data.reserve() == null || !s.complete() ? "—" : amount(Math.subtractExact(data.reserve(), cumulative)));
            }
            line("Restant non ventilé (écart)", amount(s.gap()), "");
        }
        note.getParent().layout(true, true);
    }
}
