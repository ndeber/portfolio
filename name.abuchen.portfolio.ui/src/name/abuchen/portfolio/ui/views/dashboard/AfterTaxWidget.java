package name.abuchen.portfolio.ui.views.dashboard;

import java.time.LocalDate;
import java.util.List;
import java.util.function.Supplier;

import org.eclipse.swt.widgets.Composite;

import name.abuchen.portfolio.model.Account;
import name.abuchen.portfolio.model.Classification;
import name.abuchen.portfolio.model.Dashboard.Widget;
import name.abuchen.portfolio.model.Portfolio;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.money.Money;
import name.abuchen.portfolio.money.Values;
import name.abuchen.portfolio.snapshot.filter.ClientFilter;
import name.abuchen.portfolio.snapshot.filter.PortfolioClientFilter;
import name.abuchen.portfolio.tax.TaxPerformance;
import name.abuchen.portfolio.tax.TaxScope;
import name.abuchen.portfolio.ui.util.ClientFilterMenu;
import name.abuchen.portfolio.ui.util.InfoToolTip;
import name.abuchen.portfolio.ui.views.dataseries.DataSeries;
import name.abuchen.portfolio.util.Interval;

@SuppressWarnings("nls")
public final class AfterTaxWidget extends AbstractIndicatorWidget<TaxPerformance.Result>
{
    public enum Metric
    {
        EUR, PERCENT, TWR, IRR, ANNUALIZED
    }

    private record Key(String series, Interval interval)
    {
    }

    private final Metric metric;
    private String detail = "Choisir un périmètre et une période couverte par les bases fiscales.";

    public AfterTaxWidget(Widget widget, DashboardData data, Metric metric)
    {
        super(widget, data, false, AfterTaxWidget::supports);
        this.metric = metric;
    }

    public static boolean supports(DataSeries series)
    {
        return switch (series.getType())
        {
            case CLIENT, SECURITY, ACCOUNT, PORTFOLIO, PORTFOLIO_PLUS_ACCOUNT, CLASSIFICATION, CLIENT_FILTER -> true;
            default -> false;
        };
    }

    public static TaxScope scope(name.abuchen.portfolio.model.Client client, DataSeries series)
    {
        return switch (series.getType())
        {
            case CLIENT -> TaxScope.of(client, ClientFilter.NO_FILTER, null);
            case SECURITY -> TaxScope.security(client, ClientFilter.NO_FILTER, (Security) series.getInstance());
            case ACCOUNT -> TaxScope.of(client, new PortfolioClientFilter(List.of(), List.of((Account) series.getInstance())), null);
            case PORTFOLIO -> TaxScope.of(client, new PortfolioClientFilter((Portfolio) series.getInstance()), null);
            case PORTFOLIO_PLUS_ACCOUNT -> {
                var portfolio = (Portfolio) series.getInstance();
                yield TaxScope.of(client, new PortfolioClientFilter(portfolio, portfolio.getReferenceAccount()), null);
            }
            case CLASSIFICATION -> TaxScope.of(client, ClientFilter.NO_FILTER, (Classification) series.getInstance());
            case CLIENT_FILTER -> TaxScope.of(client, ((ClientFilterMenu.Item) series.getInstance()).getFilter(), null);
            default -> throw new IllegalArgumentException("Sélectionner un périmètre d'actifs, sans indice de référence.");
        };
    }

    @Override
    public Composite createControl(Composite parent, DashboardResources resources)
    {
        var control = super.createControl(parent, resources);
        InfoToolTip.attach(indicator, () -> detail);
        return control;
    }

    @Override
    public Supplier<TaxPerformance.Result> getUpdateTask()
    {
        var series = get(DataSeriesConfig.class).getDataSeries();
        var interval = get(ReportingPeriodConfig.class).getReportingPeriod().toInterval(LocalDate.now());
        return () -> {
            var cache = getDashboardData().getCache();
            synchronized (cache)
            {
                return (TaxPerformance.Result) cache.computeIfAbsent(new Key(series.getUUID(), interval), key -> {
                    try
                    {
                        return TaxPerformance.calculate(getClient(), scope(getClient(), series),
                                        getDashboardData().getCurrencyConverter(), interval);
                    }
                    catch (RuntimeException e)
                    {
                        return TaxPerformance.Result.unavailable(e.getMessage());
                    }
                });
            }
        };
    }

    @Override
    public void update(TaxPerformance.Result result)
    {
        super.update(result);
        detail = get(DataSeriesConfig.class).getDataSeries().getLabel() + "\n" + result.detail();
        var value = result.after();
        if (metric == Metric.EUR)
            indicator.setText(value.gain() == null ? "—" : Values.Money.format(Money.of("EUR", value.gain())));
        else
        {
            Double number = switch (metric)
            {
                case PERCENT -> value.percent();
                case TWR -> value.twr();
                case IRR -> value.irr();
                case ANNUALIZED -> value.annualized();
                default -> null;
            };
            indicator.setText(number == null ? "—" : Values.Percent2.format(number));
        }
    }
}
