package name.abuchen.portfolio.ui.updateactions.bonds;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;

import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.updates.bonds.BondSources;
import name.abuchen.portfolio.updates.bonds.BondSpec;
import name.abuchen.portfolio.updates.bonds.BondUpdate;

public final class BondConfigurationDialog extends TitleAreaDialog
{
    private final List<Security> securities;
    private Table table;
    private Button migrate;
    private List<String> selected;
    private boolean migration;
    private final Map<Boolean, Text> aftFields = new HashMap<>();
    private final Map<Boolean, String> aftFallbacks = new HashMap<>();

    public BondConfigurationDialog(Shell shell, List<Security> securities)
    {
        super(shell);
        this.securities = List.copyOf(securities);
        setShellStyle(getShellStyle() | SWT.RESIZE);
    }

    @Override
    protected Control createDialogArea(Composite parent)
    {
        var area = (Composite) super.createDialogArea(parent);
        setTitle("Actualiser les obligations individuelles");
        setMessage("Sélectionnez les titres à actualiser. Les cours incluent le coupon couru et l'indexation applicable.\n"
                        + "Les changements seront prévisualisés puis appliqués au portefeuille ouvert.");
        table = new Table(area, SWT.CHECK | SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL);
        table.setHeaderVisible(true);
        GridDataFactory.fillDefaults().grab(true, true).hint(820, 230).applyTo(table);
        String[] headers = { "Obligation", "ISIN", "Historique existant" };
        int[] widths = { 380, 135, 290 };
        for (int i = 0; i < headers.length; i++)
        {
            var column = new TableColumn(table, SWT.NONE);
            column.setText(headers[i]);
            column.setWidth(widths[i]);
        }
        for (var security : securities)
        {
            var row = new TableItem(table, SWT.NONE);
            row.setData(security);
            row.setChecked(true);
            row.setText(new String[] { security.getName(), security.getIsin(), BondUpdate.managed(security)
                            ? "Coupon couru inclus — conserver et compléter" : security.getPricesIncludingLatest().isEmpty()
                                            ? "Aucun cours" : "Convention inconnue — remplacement nécessaire" });
        }
        migrate = new Button(area, SWT.CHECK);
        migrate.setText("Autoriser le remplacement des historiques dont la convention est inconnue");
        var help = new Label(area, SWT.WRAP);
        help.setText("Cette conversion peut supprimer les dates pour lesquelles aucun cours avec coupon couru n'est disponible.\n"
                        + "L'aperçu donnera les périodes et le nombre de dates retirées. Les cours déjà gérés par le script seront conservés.\n"
                        + "Le fournisseur automatique des titres sélectionnés passera à « Manuel » pour éviter le mélange de conventions.");
        GridDataFactory.fillDefaults().grab(true, false).hint(810, SWT.DEFAULT).applyTo(help);
        if (securities.stream().anyMatch(s -> BondSpec.find(s.getIsin()).filter(BondSpec::indexed).isPresent()))
        {
            var recovery = new Group(area, SWT.NONE);
            recovery.setText("Fichiers AFT de secours — facultatif");
            recovery.setLayout(new GridLayout(1, false));
            GridDataFactory.fillDefaults().grab(true, false).applyTo(recovery);
            var explanation = new Label(recovery, SWT.WRAP);
            explanation.setText("Si la page AFT est indisponible, le fichier officiel indiqué sera téléchargé directement.\n"
                            + "La dernière source connue est proposée. Vous pouvez coller un lien AFT plus récent ; les dates seront vérifiées.");
            GridDataFactory.fillDefaults().grab(true, false).hint(810, SWT.DEFAULT).applyTo(explanation);
            for (boolean euroIndex : List.of(true, false))
            {
                if (securities.stream().noneMatch(s -> BondSpec.find(s.getIsin())
                                .filter(b -> b.indexed() && b.euroIndex() == euroIndex).isPresent()))
                    continue;
                new Label(recovery, SWT.NONE).setText(euroIndex ? "Coefficients OAT€i — inflation zone euro" : "Coefficients OATi — inflation française");
                var field = new Text(recovery, SWT.BORDER);
                field.setMessage("Lien HTTPS du fichier .xls ou .xlsx sur www.aft.gouv.fr");
                field.setText(BondSources.previousAftCoefficientFile(securities, euroIndex));
                GridDataFactory.fillDefaults().grab(true, false).hint(810, SWT.DEFAULT).applyTo(field);
                aftFields.put(euroIndex, field);
            }
        }
        return area;
    }

    @Override
    protected void okPressed()
    {
        var chosen = Arrays.stream(table.getItems()).filter(TableItem::getChecked).map(i -> (Security) i.getData()).toList();
        if (chosen.isEmpty())
        {
            setErrorMessage("Sélectionnez au moins une obligation.");
            return;
        }
        if (!migrate.getSelection() && chosen.stream().anyMatch(s -> !BondUpdate.managed(s) && !s.getPricesIncludingLatest().isEmpty()))
        {
            setErrorMessage("Autorisez le remplacement des historiques inconnus, ou décochez les titres concernés.");
            return;
        }
        aftFallbacks.clear();
        for (var entry : aftFields.entrySet())
        {
            if (chosen.stream().noneMatch(s -> BondSpec.find(s.getIsin())
                            .filter(b -> b.indexed() && b.euroIndex() == entry.getKey()).isPresent()))
                continue;
            String url = entry.getValue().getText().strip();
            if (url.isEmpty())
                continue;
            try
            {
                BondSources.validateAftCoefficientFile(url, entry.getKey());
                aftFallbacks.put(entry.getKey(), url);
            }
            catch (java.io.IOException e)
            {
                setErrorMessage(e.getMessage());
                entry.getValue().setFocus();
                return;
            }
        }
        selected = chosen.stream().map(Security::getUUID).toList();
        migration = migrate.getSelection();
        super.okPressed();
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent)
    {
        createButton(parent, IDialogConstants.OK_ID, "Récupérer et prévisualiser", true);
        createButton(parent, IDialogConstants.CANCEL_ID, "Annuler", false);
    }

    public List<String> selected()
    {
        return selected;
    }

    public boolean allowMigration()
    {
        return migration;
    }

    public String aftFallback(boolean euroIndex)
    {
        return aftFallbacks.getOrDefault(euroIndex, "");
    }
}
