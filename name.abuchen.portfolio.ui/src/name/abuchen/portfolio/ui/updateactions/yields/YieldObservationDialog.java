package name.abuchen.portfolio.ui.updateactions.yields;

import java.time.LocalDate;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.jface.layout.*;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.*;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.updates.yields.*;

final class YieldObservationDialog extends TitleAreaDialog
{
    private final Client client;
    private final BondYields.Row row;
    private final LocalDate cutoff;
    private Text value, fees, source;
    private DateTime date;
    private Button net, real, ytw;
    private BondYields.Observation result;
    YieldObservationDialog(Shell shell, Client client, BondYields.Row row, LocalDate cutoff)
    { super(shell); this.client = client; this.row = row; this.cutoff = cutoff; }
    private Text field(Composite parent, String label, String initial)
    { new Label(parent, SWT.NONE).setText(label); var text = new Text(parent, SWT.BORDER); text.setText(initial); GridDataFactory.fillDefaults().grab(true, false).hint(470, SWT.DEFAULT).applyTo(text); return text; }
    @Override protected Control createDialogArea(Composite parent)
    {
        var area = (Composite) super.createDialogArea(parent); setTitle(row.security().getName());
        setMessage("Saisir un rendement vérifié et sa date. Si le YTM manque, identifier explicitement le YTW.");
        var body = new Composite(area, SWT.NONE); GridLayoutFactory.fillDefaults().numColumns(2).margins(10, 10).applyTo(body);
        var o = row.observation();
        value = field(body, "Rendement publié (%)", o == null ? "" : Double.toString(o.published() * 100));
        Double fee = BondYields.direct(row.security()) ? Double.valueOf(0) : o != null ? o.fees() : BondYields.attribute(client, row.security(), "Frais");
        fees = field(body, "Frais annuels (%)", fee == null ? "" : Double.toString(fee * 100)); fees.setEnabled(!BondYields.direct(row.security()));
        source = field(body, "Source du YTM", o == null ? "" : o.source());
        new Label(body, SWT.NONE).setText("Date de la donnée"); date = new DateTime(body, SWT.DATE | SWT.DROP_DOWN);
        var initial = o == null ? cutoff : o.date(); date.setDate(initial.getYear(), initial.getMonthValue() - 1, initial.getDayOfMonth());
        new Label(body, SWT.NONE).setText("Convention"); net = new Button(body, SWT.CHECK); net.setText("Déjà net des frais annuels (ne pas redéduire)"); net.setSelection(o != null && o.net());
        new Label(body, SWT.NONE); real = new Button(body, SWT.CHECK); real.setText("YTM réel : convertir avec l’IPCH EU sur un an"); real.setSelection(o != null && o.real());
        new Label(body, SWT.NONE); ytw = new Button(body, SWT.CHECK); ytw.setText("YTW utilisé à défaut de YTM"); ytw.setSelection(o != null && o.ytw());
        return area;
    }
    BondYields.Observation result() { return result; }
    @Override protected void okPressed()
    {
        try
        {
            var when = LocalDate.of(date.getYear(), date.getMonth()+1, date.getDay());
            if (when.isAfter(cutoff)) throw new IllegalArgumentException("Date de donnée postérieure à la date choisie.");
            Double fee = fees.getText().isBlank() ? null : Double.parseDouble(fees.getText().replace(',', '.')) / 100;
            result = new BondYields.Observation(row.security().getUUID(), when, Double.parseDouble(value.getText().replace(',', '.')) / 100,
                            fee, net.getSelection(), real.getSelection(), source.getText().strip(), (ytw.getSelection() ? "YTW utilisé à défaut de YTM, vérifié manuellement" : "YTM vérifié manuellement") + (net.getSelection() ? " — déjà net" : " — brut"));
            super.okPressed();
        }
        catch (RuntimeException e) { setErrorMessage(e.getMessage()); }
    }
}
