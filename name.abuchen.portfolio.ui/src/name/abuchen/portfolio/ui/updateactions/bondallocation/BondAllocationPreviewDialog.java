package name.abuchen.portfolio.ui.updateactions.bondallocation;

import java.util.Locale;

import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.TabFolder;
import org.eclipse.swt.widgets.TabItem;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;

import name.abuchen.portfolio.updates.bondallocation.BondAllocationAdjustment.Plan;

/** Keeps full provenance and failures readable even for large portfolios. */
public final class BondAllocationPreviewDialog extends TitleAreaDialog
{
    private final Plan plan;
    private final int securities;

    public BondAllocationPreviewDialog(Shell shell, Plan plan, int securities)
    {
        super(shell);
        this.plan = plan;
        this.securities = securities;
        setShellStyle(getShellStyle() | SWT.RESIZE);
    }

    @Override
    protected Control createDialogArea(Composite parent)
    {
        var area = (Composite) super.createDialogArea(parent);
        setTitle("Taxonomies Obligations — aperçu");
        long unchanged = plan.notices().stream().filter(n -> n.contains("— inchangé :")).count();
        setMessage(securities + " titres · " + plan.changes().size() + " affectations à modifier · " + unchanged + " compositions non actualisées.\n"
                        + "Tri des catégories par montant décroissant dans les quatre taxonomies ; Other / Others en dernier.\n"
                        + "Consultez les dates et les profils fixes dans l'onglet Sources et avertissements.");
        var tabs = new TabFolder(area, SWT.NONE);
        GridDataFactory.fillDefaults().grab(true, true).hint(900, 440).applyTo(tabs);
        var changes = new TabItem(tabs, SWT.NONE);
        changes.setText("Changements");
        var table = new Table(tabs, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.H_SCROLL);
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        String[] headers = { "Taxonomie / catégorie / titre", "Avant", "Après" };
        for (int i = 0; i < headers.length; i++)
        {
            var column = new TableColumn(table, SWT.NONE);
            column.setText(headers[i]);
            column.setWidth(i == 0 ? 660 : 100);
        }
        for (var change : plan.changes())
        {
            var row = new TableItem(table, SWT.NONE);
            row.setText(new String[] { change.location() + (change.create() ? " (nouvelle catégorie)" : ""),
                            String.format(Locale.FRANCE, "%.2f %%", change.before() / 100.0),
                            String.format(Locale.FRANCE, "%.2f %%", change.after() / 100.0) });
        }
        changes.setControl(table);
        var provenance = new TabItem(tabs, SWT.NONE);
        provenance.setText("Sources et avertissements");
        var text = new Text(tabs, SWT.READ_ONLY | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL | SWT.BORDER);
        text.setText(String.join("\n\n", plan.notices()));
        provenance.setControl(text);
        if (plan.isEmpty()) tabs.setSelection(provenance);
        return area;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent)
    {
        createButton(parent, IDialogConstants.OK_ID, plan.isEmpty() ? "Appliquer le tri au portefeuille ouvert"
                        : "Appliquer au portefeuille ouvert", true);
        createButton(parent, IDialogConstants.CANCEL_ID, "Annuler", false);
    }
}
