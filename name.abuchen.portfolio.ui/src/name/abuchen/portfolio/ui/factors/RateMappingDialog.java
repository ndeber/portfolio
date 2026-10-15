package name.abuchen.portfolio.ui.factors;

import java.time.LocalDate;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.*;

import name.abuchen.portfolio.factors.RateSensitivity;
import name.abuchen.portfolio.factors.RateSettings;
import name.abuchen.portfolio.model.Client;

@SuppressWarnings("nls")
public final class RateMappingDialog extends TitleAreaDialog
{
    private final Client client;
    private final RateSensitivity.Detail detail;
    private final String original;
    private final RateSettings.Settings settings;
    private Combo curve;
    private final Text[] duration = new Text[3];
    private Text date;
    private Text source;

    public RateMappingDialog(Shell shell, Client client, RateSensitivity.Detail detail)
    {
        super(shell);
        this.client = client;
        this.detail = detail;
        original = client.getProperty(RateSettings.PROPERTY);
        settings = RateSettings.load(client);
        setShellStyle(getShellStyle() | SWT.RESIZE);
    }

    @Override
    protected Control createDialogArea(Composite parent)
    {
        var area = (Composite) super.createDialogArea(parent);
        setTitle("Sensibilité taux — " + detail.position().vehicle().getName());
        setMessage("Contributions de duration en années, rapportées à la valeur entière du support.\nVide = inconnu ; zéro = absence confirmée. Ce ne sont pas des pourcentages.");
        var form = new Composite(area, SWT.NONE);
        GridLayoutFactory.fillDefaults().numColumns(2).margins(16, 12).spacing(12, 8).applyTo(form);
        GridDataFactory.fillDefaults().grab(true, true).hint(690, SWT.DEFAULT).applyTo(form);
        new Label(form, SWT.NONE).setText("Courbe de taux");
        curve = new Combo(form, SWT.READ_ONLY);
        curve.setItems(java.util.Arrays.stream(RateSettings.Curve.values()).map(RateSettings.Curve::label).toArray(String[]::new));
        GridDataFactory.fillDefaults().grab(true, false).applyTo(curve);
        var mapping = detail.mapping();
        curve.select(mapping == null ? RateSettings.Curve.COMMON_ONLY.ordinal() : mapping.curve().ordinal());
        for (int i = 0; i < 3; i++)
        {
            duration[i] = field(form, java.util.List.of("Courts (années)", "Intermédiaires (années)", "Longs (années)").get(i));
            if (mapping != null && mapping.durations()[i] != null)
                duration[i].setText(java.math.BigDecimal.valueOf(mapping.durations()[i]).stripTrailingZeros().toPlainString());
        }
        date = field(form, "Date de la sensibilité (AAAA-MM-JJ)");
        date.setText(mapping == null ? LocalDate.now().toString() : mapping.date());
        source = field(form, "Source / justification");
        source.setText(mapping == null ? "" : mapping.source());
        var help = new Label(form, SWT.WRAP);
        help.setText("Exemple : 50 % d'obligations de duration 6 ans dans un fonds = contribution de duration 3, pas 6. "
                        + "Sensibilité = valeur EUR × duration × 0,0001. Une duration négative représente un short.\n\n"
                        + "Allemagne alimente les taux communs et l'écart Allemagne–US ; France ajoute l'écart France–Allemagne. "
                        + "Ces sensibilités ne s'additionnent pas entre facteurs. Les taux réels et le monétaire restent séparés. "
                        + "Pour un fonds multizone sans ventilation par courbe, choisir « géographie non documentée » : les écarts resteront inconnus. "
                        + "Le crédit et la couverture de change ne sont pas modélisés ici.");
        GridDataFactory.fillDefaults().span(2, 1).grab(true, false).hint(670, SWT.DEFAULT).applyTo(help);
        return area;
    }

    private static Text field(Composite form, String label)
    {
        new Label(form, SWT.NONE).setText(label);
        var text = new Text(form, SWT.BORDER);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(text);
        return text;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent)
    {
        createButton(parent, 42, "Rétablir l'automatique", false);
        super.createButtonsForButtonBar(parent);
    }

    @Override
    protected void buttonPressed(int id)
    {
        if (id != 42) { super.buttonPressed(id); return; }
        try
        {
            RateSettings.save(client, settings.with(detail.position().vehicle().getUUID(), null), original);
            super.okPressed();
        }
        catch (RuntimeException e) { setErrorMessage(e.getMessage()); }
    }

    @Override
    protected void okPressed()
    {
        try
        {
            LocalDate observed = LocalDate.parse(date.getText().strip());
            if (observed.isAfter(LocalDate.now())) throw new IllegalArgumentException("Date future refusée.");
            Double[] values = new Double[3];
            for (int i = 0; i < 3; i++)
                if (!duration[i].getText().isBlank()) values[i] = Double.valueOf(duration[i].getText().strip().replace(',', '.'));
            var mapping = new RateSettings.Mapping(observed.toString(), source.getText().strip(),
                            RateSettings.Curve.values()[curve.getSelectionIndex()], values[0], values[1], values[2]);
            RateSettings.save(client, settings.with(detail.position().vehicle().getUUID(), mapping), original);
            super.okPressed();
        }
        catch (RuntimeException e) { setErrorMessage("Paramètres non enregistrés : " + e.getMessage()); }
    }
}
