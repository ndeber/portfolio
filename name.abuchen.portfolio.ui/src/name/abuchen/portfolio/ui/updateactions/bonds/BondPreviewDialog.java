package name.abuchen.portfolio.ui.updateactions.bonds;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;

import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.updates.bonds.BondUpdate;
import name.abuchen.portfolio.updates.bonds.BondUpdate.Plan;

public final class BondPreviewDialog extends TitleAreaDialog
{
    private final Client client;
    private final List<Plan> plans;

    public BondPreviewDialog(Shell shell, Client client, List<Plan> plans)
    {
        super(shell);
        this.client = client;
        this.plans = List.copyOf(plans);
        setShellStyle(getShellStyle() | SWT.RESIZE);
    }

    private static String price(BigDecimal value)
    {
        return value.setScale(6, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    @Override
    protected Control createDialogArea(Composite parent)
    {
        var area = (Composite) super.createDialogArea(parent);
        setTitle("Obligations — aperçu des changements");
        boolean stale = plans.stream().anyMatch(p -> p.quotes().getLast().date().isBefore(LocalDate.now().minusDays(7)));
        setMessage("Cours par 100 de nominal, coupon couru inclus. Sélectionnez une ligne pour voir les sources et le calcul.\n"
                        + "La validation met à jour le portefeuille ouvert et règle ces titres sur le fournisseur Manuel."
                        + (stale ? "\nAttention : au moins une source date de plus de sept jours." : ""));
        var table = new Table(area, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.H_SCROLL);
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        GridDataFactory.fillDefaults().grab(true, true).hint(980, 220).applyTo(table);
        String[] labels = { "Obligation", "Dernier cours", "Cours après", "Historique après", "Traitement", "Dates retirées" };
        int[] widths = { 245, 95, 100, 210, 170, 100 };
        for (int i = 0; i < labels.length; i++)
        {
            var column = new TableColumn(table, SWT.NONE);
            column.setText(labels[i]);
            column.setWidth(widths[i]);
        }
        var details = new Text(area, SWT.READ_ONLY | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL | SWT.BORDER);
        GridDataFactory.fillDefaults().grab(true, false).hint(980, 195).applyTo(details);
        for (var plan : plans)
        {
            var last = plan.quotes().getLast();
            var item = new TableItem(table, SWT.NONE);
            item.setData(plan);
            item.setText(new String[] { BondUpdate.security(client, plan.securityId()).getName(), last.date().toString(), price(last.dirty()),
                            plan.after().getFirst().date() + " → " + plan.after().getLast().date() + " (" + plan.after().size() + ")",
                            plan.migration() ? "Remplacement intégral" : "Conserver et compléter", Integer.toString(plan.removed()) });
        }
        Runnable show = () -> {
            if (table.getSelectionCount() == 0)
                return;
            var plan = (Plan) table.getSelection()[0].getData();
            var security = BondUpdate.security(client, plan.securityId());
            var last = plan.quotes().getLast();
            var before = security.getPricesIncludingLatest();
            details.setText(security.getName() + " — " + security.getIsin()
                            + "\nAvant : " + (before.isEmpty() ? "aucun cours" : before.size() + " dates du " + before.getFirst().getDate() + " au " + before.getLast().getDate()
                                            + "; dernier cours " + price(BigDecimal.valueOf(before.getLast().getValue(), 8)))
                            + "\nAprès : clean " + price(last.clean()) + "; coupon couru " + price(last.accrued()) + "; dirty " + price(last.dirty())
                            + (last.coefficient() == null ? "\nCours dirty publié par la Bundesbank."
                                            : "\nRèglement T+2 : " + last.settlement() + "; coefficient AFT " + price(last.coefficient())
                                                            + "; formule : (clean + coupon couru ACT/ACT) × coefficient.")
                            + "\nSource : " + last.source()
                            + (plan.migration() ? "\nCONVERSION : l'ancien historique sera remplacé ; " + plan.removed() + " dates seront retirées." : ""));
        };
        table.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> show.run()));
        table.select(0);
        show.run();
        return area;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent)
    {
        createButton(parent, IDialogConstants.OK_ID, "Appliquer au portefeuille ouvert", true);
        createButton(parent, IDialogConstants.CANCEL_ID, "Annuler", false);
    }
}
