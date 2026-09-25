package name.abuchen.portfolio.ui.views.dashboard;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.*;
import org.eclipse.swtchart.ICircularSeries;
import org.eclipse.swtchart.ISeries.SeriesType;
import org.eclipse.swtchart.model.Node;

import name.abuchen.portfolio.model.Classification;
import name.abuchen.portfolio.model.Dashboard.Widget;
import name.abuchen.portfolio.money.Money;
import name.abuchen.portfolio.money.Values;
import name.abuchen.portfolio.ui.UIConstants;
import name.abuchen.portfolio.ui.util.Colors;
import name.abuchen.portfolio.ui.util.SimpleAction;
import name.abuchen.portfolio.ui.util.chart.CircularChart;
import name.abuchen.portfolio.ui.views.taxonomy.TaxonomyModel;
import name.abuchen.portfolio.ui.views.taxonomy.TaxonomyNode;
import name.abuchen.portfolio.util.ColorConversion;
import name.abuchen.portfolio.util.TextUtil;

/** Compares a selected branch with its locally configured allocation targets. */
@SuppressWarnings("nls")
public class TaxonomySublevelWidget extends WidgetDelegate<TaxonomySublevelWidget.Data>
{
    public static final String CATEGORY = "SUBLEVEL_CATEGORY";
    public record Slice(String id, String name, String color, Money actual, double target, List<Slice> children) { }
    public record Data(String name, Money actual, List<Slice> slices, boolean actualValid, boolean targetValid) { }
    private Label title;
    private Label subtitle;
    private CircularChart actualChart;
    private CircularChart targetChart;
    private Table table;

    public TaxonomySublevelWidget(Widget widget, DashboardData data)
    {
        super(widget, data);
        addConfig(new ClientFilterConfig(this));
        addConfig(new TaxonomyConfig(this));
        addConfig(new ChartHeightConfig(this));
        addConfig(new WidgetConfig()
        {
            @Override public String getLabel() { return "Sous-catégorie"; }
            @Override public void menuAboutToShow(IMenuManager manager)
            {
                var taxonomy = get(TaxonomyConfig.class).getTaxonomy();
                if (taxonomy == null) return;
                var menu = new MenuManager(getLabel());
                addCategory(menu, taxonomy.getRoot(), "");
                manager.add(menu);
            }
        });
    }

    private void addCategory(MenuManager menu, Classification category, String prefix)
    {
        String path = prefix + category.getName();
        var action = new SimpleAction(path, a -> {
            getWidget().getConfiguration().put(CATEGORY, category.getId());
            getClient().touch();
            update();
        });
        action.setChecked(category.getId().equals(getWidget().getConfiguration().get(CATEGORY)));
        menu.add(action);
        category.getChildren().forEach(child -> addCategory(menu, child, path + " / "));
    }

    @Override public Supplier<Data> getUpdateTask()
    {
        var taxonomy = get(TaxonomyConfig.class).getTaxonomy();
        String selected = getWidget().getConfiguration().get(CATEGORY);
        var filter = get(ClientFilterConfig.class).getSelectedFilter();
        return () -> {
            if (taxonomy == null) return null;
            var model = new TaxonomyModel(getDashboardData().getExchangeRateProviderFactory(), getClient(), taxonomy);
            model.updateClientSnapshot(filter.filter(getClient()));
            var matches = new ArrayList<TaxonomyNode>();
            model.visitAll(n -> { if (n.isClassification() && n.getId().equals(selected)) matches.add(n); });
            if (selected != null && matches.isEmpty()) return null;
            var root = selected == null ? model.getClassificationRootNode() : matches.get(0);
            boolean[] valid = {true, true};
            var slices = slices(root, 1d, valid);
            return new Data(root.getName(), root.getActual(), slices, valid[0], valid[1]);
        };
    }

    private static List<Slice> slices(TaxonomyNode parent, double target, boolean[] valid)
    {
        var result = new ArrayList<Slice>();
        long actualChildren = 0;
        int weights = 0;
        for (var child : parent.getChildren())
        {
            if (!child.isClassification()) continue;
            actualChildren += child.getActual().getAmount();
            weights += child.getWeight();
            if (child.getWeight() < 0) valid[1] = false;
            if (child.getActual().isNegative()) valid[0] = false;
            double share = target * child.getWeight() / Classification.ONE_HUNDRED_PERCENT;
            var children = slices(child, share, valid);
            result.add(new Slice(child.getId(), child.getName(), child.getClassification().getColor(),
                            child.getActual(), share, children));
        }
        // Leaves already represent the whole holding: no extra ring for securities.
        if (result.isEmpty()) return List.of();
        if (weights > Classification.ONE_HUNDRED_PERCENT) valid[1] = false;
        long direct = parent.getActual().getAmount() - actualChildren;
        if (direct < 0) valid[0] = false;
        if (direct != 0)
            result.add(new Slice(parent.getId() + "-direct", "Affectations directes", "#AEBDBB",
                            Money.of(parent.getActual().getCurrencyCode(), direct), 0, List.of()));
        if (weights < Classification.ONE_HUNDRED_PERCENT)
            result.add(new Slice(parent.getId() + "-unallocated", "Cible non répartie", "#CFD8D6",
                            Money.of(parent.getActual().getCurrencyCode(), 0),
                            target * (Classification.ONE_HUNDRED_PERCENT - weights) / Classification.ONE_HUNDRED_PERCENT, List.of()));
        return List.copyOf(result);
    }

    @Override public Composite createControl(Composite parent, DashboardResources resources)
    {
        var container = new Composite(parent, SWT.NONE);
        container.setData(UIConstants.CSS.CLASS_NAME, getContainerCssClassNames());
        container.setBackgroundMode(SWT.INHERIT_DEFAULT);
        GridLayoutFactory.fillDefaults().margins(5, 5).applyTo(container);
        title = new Label(container, SWT.NONE);
        title.setData(UIConstants.CSS.CLASS_NAME, UIConstants.CSS.TITLE);
        subtitle = new Label(container, SWT.WRAP);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(subtitle);
        var charts = new Composite(container, SWT.NONE);
        GridLayoutFactory.fillDefaults().numColumns(2).equalWidth(true).applyTo(charts);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(charts);
        new Label(charts, SWT.NONE).setText("Répartition actuelle");
        new Label(charts, SWT.NONE).setText("Répartition cible");
        actualChart = chart(charts);
        targetChart = chart(charts);
        table = new Table(container, SWT.FULL_SELECTION | SWT.H_SCROLL | SWT.V_SCROLL);
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        GridDataFactory.fillDefaults().grab(true, false).hint(SWT.DEFAULT, 170).applyTo(table);
        String[] labels = {"Catégorie", "Montant actuel", "Actuel", "Cible", "Écart (points)"};
        for (int i = 0; i < labels.length; i++)
        {
            var column = new TableColumn(table, i == 0 ? SWT.LEFT : SWT.RIGHT);
            column.setText(labels[i]);
        }
        return container;
    }

    private CircularChart chart(Composite parent)
    {
        var chart = new CircularChart(parent, SeriesType.PIE, n -> n.getData() instanceof Slice s ? s.name() : "");
        chart.getTitle().setVisible(false);
        chart.addLabelPainter(new CircularChart.RenderLabelsAlongAngle(chart));
        GridDataFactory.fillDefaults().grab(true, false).hint(SWT.DEFAULT, get(ChartHeightConfig.class).getPixel()).applyTo(chart);
        chart.getToolTip().setToolTipBuilder((container, node) -> {
            if (node.getData() instanceof Slice slice)
                new Label(container, SWT.NONE).setText(slice.name() + " : " + Values.Percent2.format(node.getValue() / node.getParent().getValue()));
        });
        getDashboardData().getStylingEngine().style(chart);
        chart.getSeriesSet().createSeries(SeriesType.PIE, "allocation");
        return chart;
    }

    @Override public Control getTitleControl() { return title; }

    @Override public void update(Data data)
    {
        title.setText(TextUtil.tooltip(getWidget().getLabel()));
        subtitle.setText(data == null ? "Choisir une taxonomie et une sous-catégorie dans le menu du widget."
                        : TextUtil.tooltip(data.name()) + " — pourcentages relatifs à cette catégorie"
                          + (!data.actualValid() ? " · Valeurs négatives : graphique actuel indisponible." : "")
                          + (!data.targetValid() ? " · Cibles incohérentes : vérifier les poids de la taxonomie." : ""));
        table.removeAll();
        if (data != null)
            for (var s : data.slices())
            {
                Double actual = data.actual().getAmount() == 0 ? null : s.actual().getAmount() / (double) data.actual().getAmount();
                new TableItem(table, SWT.NONE).setText(new String[] {s.name(), Values.Money.format(s.actual()),
                                actual == null ? "—" : Values.Percent2.format(actual), Values.Percent2.format(s.target()),
                                actual == null ? "—" : String.format("%+.2f", 100 * (actual - s.target()))});
            }
        for (var column : table.getColumns()) column.pack();
        render(actualChart, data, false);
        render(targetChart, data, true);
        title.getParent().layout(true, true);
    }

    private void render(CircularChart chart, Data data, boolean target)
    {
        get(ChartHeightConfig.class).updateGridData(chart, title.getParent());
        for (var old : chart.getSeriesSet().getSeries()) chart.getSeriesSet().deleteSeries(old.getId());
        var series = (ICircularSeries<?>) chart.getSeriesSet().createSeries(SeriesType.PIE, "allocation");
        series.setSliceColor(chart.getPlotArea().getBackground());
        if (data != null && (target ? data.targetValid() : data.actualValid()))
            for (var slice : data.slices()) add(series, series.getRootNode(), slice, target);
        chart.updateAngleBounds();
        chart.redraw();
    }

    private void add(ICircularSeries<?> series, Node parent, Slice slice, boolean target)
    {
        double value = target ? slice.target() : slice.actual().getAmount();
        if (value <= 0) return;
        Node node = parent.addChild(slice.id(), value);
        node.setData(slice);
        series.setColor(slice.id(), Colors.getColor(ColorConversion.hex2RGB(slice.color())));
        for (var child : slice.children()) add(series, node, child, target);
    }
}
