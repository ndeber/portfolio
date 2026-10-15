package name.abuchen.portfolio.ui.factors;

import java.time.LocalDate;
import java.util.Set;
import java.util.HashSet;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.*;
import name.abuchen.portfolio.factors.CurrencyProposals;

@SuppressWarnings("nls")
public final class CurrencyPreviewDialog extends TitleAreaDialog
{
    private final CurrencyProposals.Plan plan;
    private Table table;
    private final Set<String> selection = new HashSet<>();

    public CurrencyPreviewDialog(Shell shell, CurrencyProposals.Plan plan)
    {
        super(shell); this.plan = plan; setShellStyle(getShellStyle() | SWT.RESIZE);
    }

    @Override protected Control createDialogArea(Composite parent)
    {
        var area = (Composite)super.createDialogArea(parent);
        setTitle("Expositions – Devises : proposition d'allocation");
        long available = plan.rows().stream().filter(r -> !r.evidence().weights().isEmpty()).count();
        setMessage(plan.rows().size() + " fonds détenus + ELM examinés · " + available + " propositions · "
                        + (plan.rows().size() - available) + " à documenter / traités séparément.\n"
                        + "Cochez les propositions à appliquer. Aucune proposition n’est cochée automatiquement. Annuler conserve le portefeuille.");
        var tabs = new TabFolder(area, SWT.NONE);
        GridDataFactory.fillDefaults().grab(true, true).hint(1120, 540).applyTo(tabs);
        var changes = new TabItem(tabs, SWT.NONE); changes.setText("Avant / après");
        table = new Table(tabs, SWT.CHECK | SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.H_SCROLL);
        table.setHeaderVisible(true); table.setLinesVisible(true);
        String[] labels = {"Fonds", "Actuel", "Proposition", "Méthode", "Date de source"};
        int[] widths = {280, 145, 330, 190, 160};
        for (int i=0;i<labels.length;i++) {var c = new TableColumn(table,SWT.LEFT);c.setText(labels[i]);c.setWidth(widths[i]);c.setMoveable(true);}
        for (var r : plan.rows().stream().sorted(java.util.Comparator.comparing(r -> r.evidence().weights().isEmpty())).toList())
        {
            var e = r.evidence(); var item = new TableItem(table, SWT.NONE); item.setData(r);
            String date = e.date() == null ? "Non documentée" : e.date().toString()
                            + (e.date().isBefore(plan.asof().minusDays(90)) ? " · ancienne" : "");
            item.setText(new String[]{r.name(),r.before(),e.weights().isEmpty()?"Inchangé — à documenter":CurrencyProposals.format(e.weights()),e.method(),date});
            item.setChecked(false);
        }
        table.addListener(SWT.Selection,e->{
            if(e.detail!=SWT.CHECK)return;
            var item=(TableItem)e.item;var row=(CurrencyProposals.Row)item.getData();
            if(row.evidence().weights().isEmpty())item.setChecked(false);
            if(item.getChecked())selection.add(row.id());else selection.remove(row.id());
            updateButton();
        });
        changes.setControl(table);
        // Cocoa needs a scroll after initial layout to keep the first row below the header.
        getShell().addListener(SWT.Show, event -> table.getDisplay().asyncExec(() -> {
            if (!table.isDisposed() && table.getItemCount() > 0)
            { getShell().layout(true, true); table.setTopIndex(Math.min(1, table.getItemCount() - 1)); table.setTopIndex(0); }
        }));
        var sources = new TabItem(tabs,SWT.NONE);sources.setText("Sources et limites");
        var text = new Text(tabs,SWT.READ_ONLY|SWT.MULTI|SWT.WRAP|SWT.V_SCROLL|SWT.BORDER);
        var details = new StringBuilder("Propositions préparées le "+LocalDate.now()+". Les dates de source restent distinctes de la consultation.\n"
                        + "Les profils fixes ne sont pas présentés comme des publications actualisées. La géographie, la devise de la part et le mandat ne remplacent jamais un tableau d’exposition nette.\n"
                        + "L'application conserve les objectifs, couleurs et catégories existants, les comptes et les fonds non sélectionnés.\n\n");
        for(var row:plan.rows())
        {
            var e=row.evidence();details.append(row.name()).append(" · ").append(row.isin()).append('\n')
                            .append(e.method()).append(" · ").append(e.date()==null?"Date non documentée":e.date()).append('\n')
                            .append(e.weights().isEmpty()?"Aucune proposition chiffrée":CurrencyProposals.format(e.weights())).append('\n')
                            .append(e.note()).append('\n').append(e.source()).append('\n');
            if(row.explicitOverride())details.append("ATTENTION : un paramétrage explicite du support reste prioritaire sur cette taxonomie dans la Factor Map.\n");
            details.append('\n');
        }
        text.setText(details.toString());sources.setControl(text);
        return area;
    }

    private void updateButton()
    {
        var b=getButton(IDialogConstants.OK_ID);
        if(b!=null){b.setEnabled(!selection.isEmpty());b.setText("Appliquer ("+selection.size()+")");}
    }
    @Override protected void createButtonsForButtonBar(Composite parent)
    {
        createButton(parent,IDialogConstants.OK_ID,"Appliquer la sélection",true);
        createButton(parent,IDialogConstants.CANCEL_ID,"Annuler",false);updateButton();
    }
    public Set<String> selected() {return Set.copyOf(selection);}
}
