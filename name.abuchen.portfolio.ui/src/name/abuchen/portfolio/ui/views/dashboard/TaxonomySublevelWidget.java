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
    public static final String HIDE_UNCLASSIFIED = "SUBLEVEL_HIDE_UNCLASSIFIED";
    private CircularChart chart;
    private final boolean target;

    public TaxonomySublevelWidget(Widget widget, DashboardData data)
    {
        this(widget, data, false);
    }

    public TaxonomySublevelWidget(Widget widget, DashboardData data, boolean target)
    {
        super(widget, data);
        this.target = target;
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
                var hide = new SimpleAction("Masquer les sans classification", a -> {
                    getWidget().getConfiguration().put(HIDE_UNCLASSIFIED, Boolean.toString(!hideUnclassified()));
                    getClient().touch();
                    update();
                });
                hide.setChecked(hideUnclassified());
                manager.add(hide);
            }
        });
    }

    private boolean hideUnclassified()
    {
        return Boolean.parseBoolean(getWidget().getConfiguration().getOrDefault(HIDE_UNCLASSIFIED, "true"));
    }

    private static boolean unclassified(Slice slice)
    {
        String name = java.text.Normalizer.normalize(slice.name(), java.text.Normalizer.Form.NFD)
                        .replaceAll("\\p{M}", "").toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z]", "");
        return List.of("sansclassification", "nonclasse", "nonclasses", "nonclassifie", "unclassified", "unassigned").contains(name);
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
        boolean hide = hideUnclassified();
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
            Money actual = root.getActual();
            if (hide && slices.stream().anyMatch(TaxonomySublevelWidget::unclassified))
            {
                slices = slices.stream().filter(s -> !unclassified(s)).toList();
                actual = Money.of(actual.getCurrencyCode(), slices.stream().mapToLong(s -> s.actual().getAmount()).sum());
                double totalTarget = slices.stream().mapToDouble(Slice::target).sum();
                if (totalTarget > 0)
                    slices = slices.stream().map(s -> new Slice(s.id(), s.name(), s.color(), s.actual(),
                                    s.target() / totalTarget, List.of())).toList();
            }
            return new Data(root.getName(), actual, slices, valid[0], valid[1]);
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

            result.add(new Slice(child.getId(), child.getName(), child.getClassification().getColor(),
                            child.getActual(), share, List.of()));
        }
        // A leaf can also be selected: it represents 100% of its own level.
        if (result.isEmpty())
        {
            if (parent.getActual().isNegative()) valid[0] = false;
            return List.of(new Slice(parent.getId(), parent.getName(), parent.getClassification().getColor(),
                            parent.getActual(), target, List.of()));
        }
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
        chart = chart(container);
        return container;
    }

    private CircularChart chart(Composite parent)
    {
        var chart = new CircularChart(parent, SeriesType.PIE, n -> n.getData() instanceof Slice s ? s.name() : "");
        chart.getTitle().setVisible(false);
        chart.addLabelPainter(new CircularChart.LabelPainter(chart)
        {
            @Override protected void renderLabel(Node node, ICircularSeries<?> series,
                            org.eclipse.swt.graphics.GC gc, org.eclipse.swtchart.IAxis xAxis,
                            org.eclipse.swtchart.IAxis yAxis)
            {
                if (!node.isVisible() || node.getParent().getValue() <= 0) return;
                String label = Math.round(100 * node.getValue() / node.getParent().getValue()) + " %";
                var previousFont = gc.getFont();
                var previousColor = gc.getForeground();
                try
                {
                    gc.setFont(labelFont);
                    var size = gc.textExtent(label);
                    var angles = node.getAngleBounds();
                    double radius = .67 * (node.getLevel() - series.getRootNode().getLevel());
                    var start = getPixelCoordinate(xAxis, yAxis, radius, angles.x);
                    var end = getPixelCoordinate(xAxis, yAxis, radius, angles.x + angles.y);
                    // Keep narrow slices readable via their tooltip rather than overlapping labels.
                    if (angles.y < 180 && Math.hypot(end.x - start.x, end.y - start.y) < Math.max(size.x, size.y) + 4)
                        return;
                    var center = getPixelCoordinate(xAxis, yAxis, angles.y >= 359 ? 0 : radius, angles.x + angles.y / 2);
                    gc.setForeground(Colors.getTextColor(node.getSliceColor()));
                    gc.drawString(label, center.x - size.x / 2, center.y - size.y / 2, true);
                }
                finally
                {
                    gc.setFont(previousFont);
                    gc.setForeground(previousColor);
                }
            }
        });
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
        title.setToolTipText(data == null ? "Choisir une taxonomie et une sous-catégorie dans le menu du widget."
                        : data.name() + (target ? " — répartition cible" : " — répartition réelle")
                          + (hideUnclassified() ? " · Sans classification masqués ; pourcentages recalculés sur les catégories visibles." : "")
                          + (!target && !data.actualValid() ? " · Valeurs négatives : graphique actuel indisponible." : "")
                          + (target && !data.targetValid() ? " · Cibles incohérentes : vérifier les poids de la taxonomie." : ""));
        render(chart, data, target);
        title.getParent().layout(true, true);
    }

    private void render(CircularChart chart, Data data, boolean target)
    {
        get(ChartHeightConfig.class).updateGridData(chart, title.getParent());
        for (var old : chart.getSeriesSet().getSeries()) chart.getSeriesSet().deleteSeries(old.getId());
        var series = (ICircularSeries<?>) chart.getSeriesSet().createSeries(SeriesType.PIE, "allocation");
        series.setSliceColor(chart.getPlotArea().getBackground());
        if (data != null && (target ? data.targetValid() : data.actualValid()))
            populateSeries(series, data.slices(), target);
        chart.updateAngleBounds();
        chart.redraw();
    }

    static void populateSeries(ICircularSeries<?> series, List<Slice> slices, boolean target)
    {
        for (var slice : slices)
        {
            double value = target ? slice.target() : slice.actual().getAmount();
            if (value <= 0) continue;
            Node node = series.getRootNode().addChild(slice.id(), value);
            node.setData(slice);
        }
        // SWTChart resets every node's color when adding a child. Like the taxonomy
        // page, apply category colors only after the complete series has been built.
        for (var node : series.getRootNode().getChildren())
        {
            var slice = (Slice) node.getData();
            series.setColor(slice.id(), Colors.getColor(ColorConversion.hex2RGB(slice.color())));
        }
    }
}
