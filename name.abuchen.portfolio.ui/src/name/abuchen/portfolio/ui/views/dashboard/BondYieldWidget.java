package name.abuchen.portfolio.ui.views.dashboard;

import java.time.LocalDate;
import java.util.function.Supplier;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.*;
import name.abuchen.portfolio.model.Dashboard.Widget;
import name.abuchen.portfolio.ui.UIConstants;
import name.abuchen.portfolio.ui.updateactions.yields.YieldDialog;
import name.abuchen.portfolio.updates.yields.BondYields;

public final class BondYieldWidget extends WidgetDelegate<BondYieldWidget.Data>
{
    public record Data(BondYields.Result result, String error) { }
    private Label title, text;
    public BondYieldWidget(Widget widget, DashboardData data) { super(widget, data); }
    @Override public Composite createControl(Composite parent, DashboardResources resources)
    {
        var body = new Composite(parent, SWT.NONE); body.setData(UIConstants.CSS.CLASS_NAME, getContainerCssClassNames());
        GridLayoutFactory.fillDefaults().margins(5, 5).applyTo(body);
        title = new Label(body, SWT.NONE); title.setData(UIConstants.CSS.CLASS_NAME, UIConstants.CSS.TITLE);
        text = new Label(body, SWT.WRAP); GridDataFactory.fillDefaults().grab(true, false).hint(400, SWT.DEFAULT).applyTo(text); return body;
    }
    @Override public Control getTitleControl() { return title; }
    @Override public Supplier<Data> getUpdateTask()
    { return () -> { try { return new Data(BondYields.calculate(getClient(), getDashboardData().getCurrencyConverter(), LocalDate.now()), null); }
                    catch (RuntimeException e) { return new Data(null, e.getMessage()); } }; }
    @Override public void update(Data data)
    {
        title.setText(getWidget().getLabel());
        if (data == null || data.result() == null) { text.setText(data == null ? "Chargement…" : data.error()); return; }
        var r = data.result(); var a = r.average();
        text.setText("YTM : " + YieldDialog.percent(a.gross()) + "\nYTM-Frais : " + YieldDialog.percent(a.net())
                        + "\nCouverture brut / net : " + YieldDialog.percent(a.total() == 0 ? null : a.grossCovered()/a.total()) + " / " + YieldDialog.percent(a.total() == 0 ? null : a.netCovered()/a.total())
                        + "\n" + r.quality() + "\nAu " + r.date() + " · pondération en EUR"
                        + "\n" + (r.inflation() == null ? r.inflationError() : "Inflation IPCH EU sur un an : " + YieldDialog.percent(r.inflation().rate()) + " au " + r.inflation().date())
                        + "\nActualisation et détail : Outils du portefeuille → Actualiser les YTM et YTM-Frais.");
        text.getParent().layout(true, true);
    }
}
