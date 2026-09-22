package name.abuchen.portfolio.ui.views.dashboard;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;
import java.util.function.Supplier;

import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;

import name.abuchen.portfolio.model.Classification;
import name.abuchen.portfolio.model.Dashboard.Widget;
import name.abuchen.portfolio.money.Money;
import name.abuchen.portfolio.money.Values;
import name.abuchen.portfolio.ui.UIConstants;
import name.abuchen.portfolio.ui.util.SimpleAction;
import name.abuchen.portfolio.ui.util.StringToCurrencyConverter;
import name.abuchen.portfolio.ui.views.taxonomy.TaxonomyModel;
import name.abuchen.portfolio.ui.views.taxonomy.TaxonomyNode;
import name.abuchen.portfolio.util.TextUtil;

/** Allocation and fixed EUR goals using the same valuations as the taxonomy view. */
public class AllocationGoalWidget extends WidgetDelegate<AllocationGoalWidget.Data>
{
    private static final ResourceBundle LABELS = ResourceBundle.getBundle(
                    "name.abuchen.portfolio.ui.views.dashboard.allocationgoals");
    private static final String CATEGORY = "ALLOCATION_GOAL_CATEGORY";
    private static final String EUR_TARGET = "ALLOCATION_GOAL_EUR_TARGET";

    public record Row(String name, Money actual, Double actualWeight, double targetWeight)
    {
    }

    public record Data(String category, List<Row> rows, Money current, Long target)
    {
    }

    private final boolean fixedGoal;
    private Label title;
    private Label subtitle;
    private Table table;

    public static String label(String key)
    {
        return LABELS.getString(key);
    }

    public AllocationGoalWidget(Widget widget, DashboardData data, boolean fixedGoal)
    {
        super(widget, data);
        this.fixedGoal = fixedGoal;
        addConfig(new ClientFilterConfig(this));
        addConfig(new TaxonomyConfig(this));
        addConfig(new GoalConfig());
    }

    private final class GoalConfig implements WidgetConfig
    {
        @Override
        public void menuAboutToShow(IMenuManager manager)
        {
            var taxonomy = get(TaxonomyConfig.class).getTaxonomy();
            if (taxonomy != null)
            {
                var categories = new MenuManager(label("category"));
                addCategory(categories, taxonomy.getRoot(), "");
                manager.add(categories);
            }
            if (fixedGoal)
                manager.add(new SimpleAction(label("target") + "...", a -> editTarget()));
        }

        private void addCategory(MenuManager manager, Classification classification, String prefix)
        {
            String name = prefix + classification.getName();
            manager.add(new SimpleAction(name, a -> {
                getWidget().getConfiguration().put(CATEGORY, classification.getId());
                update();
                getClient().touch();
            }));
            classification.getChildren().forEach(child -> addCategory(manager, child, name + " / "));
        }

        @Override
        public String getLabel()
        {
            return label("category");
        }
    }

    private Long target()
    {
        try
        {
            long value = Long.parseLong(getWidget().getConfiguration().getOrDefault(EUR_TARGET, "0"));
            return value > 0 ? value : null;
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }

    private void editTarget()
    {
        Long value = target();
        var dialog = new InputDialog(Display.getCurrent().getActiveShell(), label("target"), label("target"),
                        value == null ? "" : Values.Amount.format(value), text -> {
                            try
                            {
                                return new StringToCurrencyConverter(Values.Amount).convert(text) > 0 ? null
                                                : label("positiveTarget");
                            }
                            catch (IllegalArgumentException e)
                            {
                                return label("positiveTarget");
                            }
                        });
        if (dialog.open() == Window.OK)
        {
            long amount = new StringToCurrencyConverter(Values.Amount).convert(dialog.getValue());
            getWidget().getConfiguration().put(EUR_TARGET, Long.toString(amount));
            update();
            getClient().touch();
        }
    }

    @Override
    public Composite createControl(Composite parent, DashboardResources resources)
    {
        var container = new Composite(parent, SWT.NONE);
        container.setData(UIConstants.CSS.CLASS_NAME, getContainerCssClassNames());
        container.setBackground(parent.getBackground());
        container.setBackgroundMode(SWT.INHERIT_DEFAULT);
        GridLayoutFactory.fillDefaults().margins(5, 5).applyTo(container);
        title = new Label(container, SWT.NONE);
        title.setData(UIConstants.CSS.CLASS_NAME, UIConstants.CSS.TITLE);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(title);
        subtitle = new Label(container, SWT.WRAP);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(subtitle);
        table = new Table(container, SWT.FULL_SELECTION | SWT.H_SCROLL | SWT.V_SCROLL);
        table.setHeaderVisible(!fixedGoal);
        table.setLinesVisible(true);
        GridDataFactory.fillDefaults().grab(true, false).hint(SWT.DEFAULT, fixedGoal ? 120 : 230).applyTo(table);
        String[] columns = fixedGoal ? new String[] { "category", "actual" }
                        : new String[] { "category", "actual", "actualWeight", "targetWeight", "gap" };
        for (int i = 0; i < columns.length; i++)
        {
            var column = new TableColumn(table, i == 0 ? SWT.LEFT : SWT.RIGHT);
            column.setText(label(columns[i]));
        }
        return container;
    }

    @Override
    public Control getTitleControl()
    {
        return title;
    }

    @Override
    public Supplier<Data> getUpdateTask()
    {
        var taxonomy = get(TaxonomyConfig.class).getTaxonomy();
        String selected = getWidget().getConfiguration().get(CATEGORY);
        var filter = get(ClientFilterConfig.class).getSelectedFilter();
        Long configuredTarget = target();
        return () -> {
            if (taxonomy == null)
                return null;
            var model = new TaxonomyModel(getDashboardData().getExchangeRateProviderFactory(), getClient(), taxonomy);
            model.updateClientSnapshot(filter.filter(getClient()));
            var root = model.getClassificationRootNode();
            if (selected != null && !selected.equals(root.getId()))
            {
                var found = new ArrayList<TaxonomyNode>();
                model.visitAll(node -> {
                    if (node.isClassification() && selected.equals(node.getId()))
                        found.add(node);
                });
                if (found.isEmpty())
                    return null;
                root = found.get(0);
            }
            List<Row> rows = new ArrayList<>();
            collectRows(root, root.getActual().getAmount(), root.getTarget().getAmount(), 1d, "", rows);
            Money current = getDashboardData().getCurrencyConverter().with("EUR")
                            .convert(LocalDate.now(), root.getActual());
            return new Data(root.getName(), List.copyOf(rows), current, configuredTarget);
        };
    }

    private void collectRows(TaxonomyNode parent, long actualTotal, long targetTotal, double parentTargetWeight, String prefix, List<Row> rows)
    {
        for (var node : parent.getChildren())
        {
            if (!node.isClassification())
                continue;
            double targetWeight = targetTotal == 0
                            ? parentTargetWeight * node.getWeight() / Classification.ONE_HUNDRED_PERCENT
                            : node.getTarget().getAmount() / (double) targetTotal;
            rows.add(new Row(prefix + node.getName(), node.getActual(),
                            actualTotal == 0 ? null : node.getActual().getAmount() / (double) actualTotal,
                            targetWeight));
            collectRows(node, actualTotal, targetTotal, targetWeight, prefix + "    ", rows);
        }
    }

    @Override
    public void update(Data data)
    {
        title.setText(TextUtil.tooltip(getWidget().getLabel()));
        table.removeAll();
        subtitle.setText(data == null ? label("selectCategory") : TextUtil.tooltip(data.category()));
        if (data != null && fixedGoal)
        {
            addRow(label("actual"), Values.Money.format(data.current()));
            addRow(label("target"), data.target() == null ? label("setTarget")
                            : Values.Money.format(Money.of("EUR", data.target())));
            if (data.target() != null)
            {
                long remaining = data.target() - data.current().getAmount();
                addRow(label(remaining >= 0 ? "remaining" : "excess"),
                                Values.Money.format(Money.of("EUR", Math.abs(remaining))));
                addRow(label("reached"), Values.Percent2.format(data.current().getAmount() / (double) data.target()));
            }
        }
        else if (data != null)
        {
            for (Row row : data.rows())
                addRow(row.name(), Values.Money.format(row.actual()),
                                row.actualWeight() == null ? "—" : Values.Percent2.format(row.actualWeight()),
                                Values.Percent2.format(row.targetWeight()), row.actualWeight() == null ? "—"
                                                : java.text.NumberFormat.getNumberInstance().format(100 * (row.actualWeight() - row.targetWeight()))
                                                                + " " + label("points"));
        }
        for (TableColumn column : table.getColumns())
            column.pack();
        title.getParent().layout(true, true);
    }

    private void addRow(String... values)
    {
        new TableItem(table, SWT.NONE).setText(values);
    }
}
