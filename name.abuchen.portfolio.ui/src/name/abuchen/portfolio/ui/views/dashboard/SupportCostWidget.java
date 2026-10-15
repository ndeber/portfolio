package name.abuchen.portfolio.ui.views.dashboard;

import java.time.LocalDate;
import java.util.function.Supplier;

import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import name.abuchen.portfolio.model.Dashboard;

import name.abuchen.portfolio.model.Dashboard.Widget;
import name.abuchen.portfolio.money.Money;
import name.abuchen.portfolio.money.Values;
import name.abuchen.portfolio.supportcosts.SupportCosts;
import name.abuchen.portfolio.ui.UIConstants;
import name.abuchen.portfolio.ui.util.swt.ColoredLabel;

@SuppressWarnings("nls")
public final class SupportCostWidget extends WidgetDelegate<SupportCostWidget.Data>
{
    public record Data(SupportCosts.Result result, String error) {}
    private record Key(String series, name.abuchen.portfolio.util.Interval period, LocalDate asOf) {}
    private Label title;
    private ColoredLabel rate, cost;
    private Button detail, info;
    private SupportCosts.Result result;
    private String error;

    public SupportCostWidget(Widget widget, DashboardData data)
    {
        super(widget, data);
        addConfig(new DataSeriesConfig(this, false, false, AfterTaxWidget::supports, "Périmètre", Dashboard.Config.DATA_SERIES));
        addConfig(new ReportingPeriodConfig(this));
    }

    @Override public Composite createControl(Composite parent, DashboardResources resources)
    {
        var body = new Composite(parent, SWT.NONE);
        body.setData(UIConstants.CSS.CLASS_NAME, getContainerCssClassNames());
        GridLayoutFactory.fillDefaults().margins(5, 5).applyTo(body);
        title = new Label(body, SWT.NONE); title.setData(UIConstants.CSS.CLASS_NAME, UIConstants.CSS.TITLE);
        var metrics = new Composite(body, SWT.NONE);
        GridLayoutFactory.fillDefaults().numColumns(2).spacing(20, 3).applyTo(metrics);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(metrics);
        for (var caption : new String[]{"Taux annuel", "Frais"})
        {
            var label = new Label(metrics, SWT.NONE); label.setText(caption);
            GridDataFactory.fillDefaults().grab(true, false).applyTo(label);
        }
        rate = new ColoredLabel(metrics, SWT.NONE); cost = new ColoredLabel(metrics, SWT.NONE);
        for (var value : new ColoredLabel[]{rate, cost})
        {
            value.setData(UIConstants.CSS.CLASS_NAME, UIConstants.CSS.KPI);
            value.setText("—");
            GridDataFactory.fillDefaults().grab(true, false).applyTo(value);
        }
        var buttons = new Composite(body, SWT.NONE);
        GridLayoutFactory.fillDefaults().numColumns(2).applyTo(buttons);
        detail = new Button(buttons, SWT.PUSH); detail.setText("Détail par support…"); detail.setEnabled(false);
        detail.addListener(SWT.Selection, e -> { if (result != null) detailsDialog().open(); });
        info = new Button(buttons, SWT.PUSH); info.setText("Infos");
        info.addListener(SWT.Selection, e -> infoDialog().open());
        return body;
    }

    @Override public Control getTitleControl() { return title; }

    @Override public Supplier<Data> getUpdateTask()
    {
        var series = get(DataSeriesConfig.class).getDataSeries();
        var asOf = LocalDate.now();
        var period = get(ReportingPeriodConfig.class).getReportingPeriod().toInterval(asOf);
        return () -> {
            var cache = getDashboardData().getCache();
            synchronized (cache)
            {
                return (Data) cache.computeIfAbsent(new Key(series.getUUID(), period, asOf), key -> {
                    try { return new Data(SupportCosts.calculate(getClient(), AfterTaxWidget.scope(getClient(), series),
                                    getDashboardData().getCurrencyConverter(), period), null); }
                    catch (RuntimeException e) { return new Data(null, e.getMessage()); }
                });
            }
        };
    }

    private static String percent(Double value) { return value == null ? "—" : Values.Percent2.format(value); }
    private static String euros(long value) { return Values.Money.format(Money.of("EUR", value)); }

    @Override public void onWidgetConfigEdited(Class<? extends WidgetConfig> type)
    {
        // Keep the short, editable title when the native series picker changes the scope.
    }

    @Override public void update(Data data)
    {
        var label = getWidget().getLabel();
        title.setText(label.startsWith("Frais intégrés") ? "Frais supports" : label);
        result = data == null ? null : data.result();
        error = data == null ? "Chargement…" : data.error();
        detail.setEnabled(result != null);
        rate.setText(result == null ? "—" : percent(result.annualRate()));
        cost.setText(result == null ? "—" : euros(result.estimatedCost()));
        boolean incomplete = result == null || !result.complete();
        info.setText(incomplete ? "Infos *" : "Infos");
        info.setToolTipText(result == null ? error : incomplete ? "Estimation partielle : consulter les informations." : "Période, périmètre et méthode de calcul.");
        title.getParent().layout(true, true);
    }

    private String information()
    {
        var requested = get(ReportingPeriodConfig.class).getReportingPeriod().toInterval(LocalDate.now());
        var value = "Périmètre : " + get(DataSeriesConfig.class).getDataSeries().getLabel()
                        + "\nPériode choisie : " + requested.getStart().plusDays(1) + " → " + requested.getEnd();
        if (result == null) return value + "\n\nCalcul indisponible : " + error;
        var r = result;
        long missing = r.rows().stream().filter(row -> row.annualRate() == null).count();
        return value + "\nPériode calculée : " + r.period().getStart().plusDays(1) + " → " + r.period().getEnd()
                        + "\n\nTaux annuel pondéré des supports couverts : " + percent(r.annualRate())
                        + "\nFrais intégrés estimés : " + euros(r.estimatedCost()) + (r.complete() ? "" : " (partiel)")
                        + "\nCoût sur la période / encours moyen couvert : " + percent(r.periodRate())
                        + "\nTaux annuel du périmètre, liquidités incluses : " + percent(r.scopeAnnualRate())
                        + "\nCouverture des encours-jours des supports : " + percent(r.coverage())
                        + " · " + missing + " taux manquant(s)"
                        + (r.unavailableValuations() == 0 ? "" : "\n" + r.unavailableValuations() + " journée(s) de support exclue(s) : cours manquant ou position négative")
                        + "\n\nLes périodes en cours s’arrêtent à aujourd’hui."
                        + "\nTaux renseignés appliqués constants sur la période ; encours et changes historiques PP, ACT/365 ou 366."
                        + "\nTER et compléments explicitement renseignés. Coûts variables non documentés exclus."
                        + "\nDéjà compris dans les VL : aucune nouvelle déduction de performance.";
    }

    TitleAreaDialog infoDialog()
    {
        return new TitleAreaDialog(title.getShell())
        {
            @Override protected boolean isResizable() { return true; }
            @Override protected Control createDialogArea(Composite parent)
            {
                var area = (Composite) super.createDialogArea(parent);
                setTitle("Frais supports — informations");
                setMessage("Période, périmètre et limites de l’estimation.");
                var text = new Text(area, SWT.READ_ONLY | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL | SWT.BORDER);
                text.setText(information());
                GridDataFactory.fillDefaults().grab(true, true).hint(720, 360).applyTo(text);
                return area;
            }
        };
    }

    TitleAreaDialog detailsDialog()
    {
        return new TitleAreaDialog(title.getShell())
        {
            @Override protected boolean isResizable() { return true; }
            @Override protected Control createDialogArea(Composite parent)
            {
                var area = (Composite) super.createDialogArea(parent);
                setTitle("Frais supports — détail");
                setMessage("Estimations sur la période choisie. Modifier les taux dans les attributs du titre (TER et complément annuel).");
                var table = new Table(area, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.H_SCROLL);
                table.setHeaderVisible(true); table.setLinesVisible(true);
                GridDataFactory.fillDefaults().grab(true, true).hint(1000, 420).applyTo(table);
                String[] headers = {"Support", "ISIN", "% annuel", "Montant estimé", "Jours détenus", "Source", "Périmètre / limites"};
                int[] widths = {260, 125, 85, 120, 95, 250, 350};
                for (int i = 0; i < headers.length; i++) { var col = new TableColumn(table, SWT.NONE); col.setText(headers[i]); col.setWidth(widths[i]); }
                for (var row : result.rows())
                {
                    var item = new TableItem(table, SWT.NONE);
                    item.setText(new String[]{row.security().getName(), row.security().getIsin() == null ? "" : row.security().getIsin(),
                                    percent(row.annualRate()), row.annualRate() == null ? "À renseigner" : euros(row.estimatedCost()),
                                    Integer.toString(row.days()), row.source(), row.note()});
                }
                return area;
            }
        };
    }
}
