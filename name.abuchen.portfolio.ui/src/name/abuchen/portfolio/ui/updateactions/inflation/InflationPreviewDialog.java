package name.abuchen.portfolio.ui.updateactions.inflation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.TreeMap;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.*;
import name.abuchen.portfolio.updates.inflation.AftInflation;

@SuppressWarnings("nls")
public final class InflationPreviewDialog extends TitleAreaDialog
{
    private final AftInflation.Plan plan;
    public InflationPreviewDialog(Shell shell, AftInflation.Plan plan)
    {
        super(shell); this.plan = plan; setShellStyle(getShellStyle() | SWT.RESIZE);
    }
    private static String value(Long amount)
    { return amount == null ? "—" : BigDecimal.valueOf(amount, 8).stripTrailingZeros().toPlainString(); }
    @Override protected Control createDialogArea(Composite parent)
    {
        var area = (Composite) super.createDialogArea(parent);
        setTitle("EU (IPCH) — aperçu de la mise à jour AFT");
        var points = plan.download().points();
        long future = points.stream().filter(p -> p.date().isAfter(LocalDate.now())).count();
        setMessage("Référence quotidienne OAT€i, base 2025 = 100. " + plan.added() + " ajouts ; " + plan.corrected() + " corrections.\n"
                        + points.getFirst().date() + " → " + points.getLast().date()
                        + (future > 0 ? " · " + future + " références futures déjà publiées par l’AFT (pas des prévisions)." : ""));
        var source = new Text(area, SWT.READ_ONLY | SWT.WRAP);
        source.setText("Source : " + plan.download().url() + "\nSeules les dates modifiées sont listées ci-dessous. L’autre instrument IPCH reste inchangé.");
        GridDataFactory.fillDefaults().grab(true, false).hint(770, 60).applyTo(source);
        var table = new Table(area, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL);
        table.setHeaderVisible(true); table.setLinesVisible(true);
        GridDataFactory.fillDefaults().grab(true, true).hint(770, 280).applyTo(table);
        for (String name : new String[] {"Date", "Avant", "Après — base 2025"})
        { var column = new TableColumn(table, SWT.LEFT); column.setText(name); column.setWidth(240); }
        var old = new TreeMap<LocalDate, Long>();
        plan.before().prices().forEach(p -> old.put(p.date(), p.value()));
        if (plan.before().latest() != null)
        {
            var fields = plan.before().latest().split(":");
            old.put(LocalDate.parse(fields[0]), Long.parseLong(fields[1]));
        }
        for (var point : points)
            if (!java.util.Objects.equals(old.get(point.date()), point.value()))
                new TableItem(table, SWT.NONE).setText(new String[] {point.date().toString(), value(old.get(point.date())), value(point.value())});
        return area;
    }
    @Override protected void createButtonsForButtonBar(Composite parent)
    {
        createButton(parent, IDialogConstants.OK_ID, "Appliquer au portefeuille ouvert", true);
        createButton(parent, IDialogConstants.CANCEL_ID, "Annuler", false);
    }
}
