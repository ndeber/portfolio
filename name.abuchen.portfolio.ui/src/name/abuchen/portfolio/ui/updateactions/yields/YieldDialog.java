package name.abuchen.portfolio.ui.updateactions.yields;

import java.time.LocalDate;
import java.util.*;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.eclipse.jface.dialogs.*;
import org.eclipse.jface.layout.*;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.*;
import name.abuchen.portfolio.model.*;
import name.abuchen.portfolio.money.*;
import name.abuchen.portfolio.updates.yields.*;

public final class YieldDialog extends TitleAreaDialog
{
    private final Client client;
    private final CurrencyConverter converter;
    private final BooleanSupplier changed;
    private final List<BondYields.Observation> pending = new ArrayList<>();
    private final Map<String, String> errors = new HashMap<>();
    private Client working;
    private Table table;
    private DateTime dateControl;
    private Label summary;
    public YieldDialog(Shell shell, Client client, CurrencyConverter converter, BooleanSupplier changed)
    { super(shell); this.client = client; this.converter = converter; this.changed = changed; working = BondYields.duplicate(client); setShellStyle(getShellStyle() | SWT.RESIZE); }
    private LocalDate date() { return LocalDate.of(dateControl.getYear(), dateControl.getMonth() + 1, dateControl.getDay()); }
    public static String percent(Double value) { return value == null ? "—" : String.format(Locale.FRANCE, "%.2f %%", value * 100); }
    @Override protected Control createDialogArea(Composite parent)
    {
        var area = (Composite) super.createDialogArea(parent); setTitle("Rendements de la poche obligataire");
        setMessage("YTM et YTM-Frais pondérés par les valeurs en EUR. Conversion des rendements réels par l’IPCH EU sur un an.\nLes sources absentes sont exclues de la moyenne ; leur poids reste dans la couverture.");
        var toolbar = new Composite(area, SWT.NONE); GridLayoutFactory.fillDefaults().numColumns(4).applyTo(toolbar);
        new Label(toolbar, SWT.NONE).setText("Date du portefeuille :"); dateControl = new DateTime(toolbar, SWT.DATE | SWT.DROP_DOWN);
        dateControl.addListener(SWT.Selection, e -> refresh());
        var fetch = new Button(toolbar, SWT.PUSH); fetch.setText("Actualiser les sources"); fetch.addListener(SWT.Selection, e -> fetch());
        var edit = new Button(toolbar, SWT.PUSH); edit.setText("Renseigner la ligne sélectionnée…"); edit.addListener(SWT.Selection, e -> edit());
        summary = new Label(area, SWT.WRAP); GridDataFactory.fillDefaults().grab(true, false).hint(1000, 90).applyTo(summary);
        table = new Table(area, SWT.BORDER | SWT.FULL_SELECTION | SWT.H_SCROLL | SWT.V_SCROLL); table.setHeaderVisible(true); table.setLinesVisible(true);
        GridDataFactory.fillDefaults().grab(true, true).hint(1050, 430).applyTo(table);
        String[] labels = {"Titre", "Valeur EUR", "Date source", "YTM / YTW publié", "Frais annuels", "YTM nominal", "YTM-Frais", "Convention / état", "Source"};
        int[] widths = {260, 100, 100, 90, 90, 100, 100, 350, 400};
        for (int i = 0; i < labels.length; i++) { var column = new TableColumn(table, SWT.LEFT); column.setText(labels[i]); column.setWidth(widths[i]); }
        refresh(); return area;
    }
    private void refresh()
    {
        if (table == null) return;
        try
        {
            var result = BondYields.calculate(working, converter, date()); table.removeAll();
            for (var row : result.rows())
            {
                var o = row.observation(); var item = new TableItem(table, SWT.NONE); item.setData(row);
                item.setText(new String[] {row.security().getName(), Values.Amount.format(row.value()), o == null ? "—" : o.date().toString(),
                                o == null ? "—" : (o.ytw() ? "YTW " : "YTM ") + percent(o.published()), percent(o == null ? BondYields.attribute(working, row.security(), "Frais") : o.fees()),
                                percent(row.gross()), percent(row.net()), row.status() + (errors.containsKey(row.security().getUUID()) ? " — " + errors.get(row.security().getUUID()) : ""), o == null ? "" : o.source()});
            }
            var a = result.average();
            summary.setText("YTM : " + percent(a.gross()) + " · YTM-Frais : " + percent(a.net())
                            + " · Couverture brut / net : " + percent(a.total() == 0 ? null : a.grossCovered() / a.total()) + " / " + percent(a.total() == 0 ? null : a.netCovered() / a.total())
                            + "\n" + (result.inflation() == null ? result.inflationError() : "Inflation retenue : " + percent(result.inflation().rate()) + " du " + result.inflation().previous() + " au " + result.inflation().date())
                            + "\n" + result.quality() + "\n" + pending.size() + " observations préparées. Moyenne des rendements individuels, pas un TRI global ni une prévision de performance.");
        }
        catch (RuntimeException e) { setErrorMessage(e.getMessage()); }
    }
    private void fetch()
    {
        var reference = date();
        if (reference.isAfter(LocalDate.now())) { setErrorMessage("Choisir une date passée ou aujourd’hui."); return; }
        var downloaded = new ArrayList<BondYields.Observation>(); var failures = new HashMap<String, String>();
        try
        {
            var snapshot = BondYields.duplicate(working);
            var candidates = BondYields.calculate(snapshot, converter, reference).rows();
            new ProgressMonitorDialog(getShell()).run(true, true, monitor -> {
                monitor.beginTask("Actualisation des rendements obligataires", candidates.size());
                var sources = new YieldSources(monitor::isCanceled, reference);
                try
                {
                    for (var row : candidates)
                    {
                        if (monitor.isCanceled()) throw new InterruptedException();
                        monitor.subTask(row.security().getName());
                        try { downloaded.add(sources.fetch(snapshot, row.security())); }
                        catch (java.io.IOException | RuntimeException e) { failures.put(row.security().getUUID(), e.getMessage()); }
                        monitor.worked(1);
                    }
                }
                finally { monitor.done(); }
            });
            for (var o : downloaded) { pending.removeIf(old -> old.id().equals(o.id()) && old.date().equals(o.date())); pending.add(o); }
            errors.clear(); errors.putAll(failures); BondYields.apply(working, downloaded, converter, reference); refresh();
        }
        catch (InterruptedException e) { /* Cancel discards the entire retrieval. */ }
        catch (Exception e) { setErrorMessage(e.getMessage()); }
    }
    private void edit()
    {
        if (table.getSelectionCount() != 1) { setErrorMessage("Sélectionner un titre."); return; }
        var row = (BondYields.Row) table.getSelection()[0].getData();
        var editor = new YieldObservationDialog(getShell(), working, row, date());
        if (editor.open() != Window.OK) return;
        var o = editor.result();
        try
        {
            BondYields.apply(working, List.of(o), converter, date());
            pending.removeIf(old -> old.id().equals(o.id()) && old.date().equals(o.date())); pending.add(o);
            errors.remove(o.id()); setErrorMessage(null); refresh();
        }
        catch (RuntimeException e) { setErrorMessage(e.getMessage()); }
    }
    @Override protected void createButtonsForButtonBar(Composite parent)
    { createButton(parent, IDialogConstants.OK_ID, "Appliquer au portefeuille ouvert", true); createButton(parent, IDialogConstants.CANCEL_ID, "Fermer sans appliquer", false); }
    @Override protected void okPressed()
    {
        try
        {
            if (changed.getAsBoolean()) throw new IllegalArgumentException("Le portefeuille a changé pendant l’aperçu. Fermer et relancer.");
            if (!pending.isEmpty()) BondYields.apply(client, pending, converter, date());
            super.okPressed();
        }
        catch (RuntimeException e) { setErrorMessage(e.getMessage()); }
    }
}
