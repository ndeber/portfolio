package name.abuchen.portfolio.ui.factors;

import java.time.LocalDate;
import java.util.EnumMap;

import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import name.abuchen.portfolio.factors.Factor;
import name.abuchen.portfolio.factors.FactorMap;
import name.abuchen.portfolio.factors.FactorSettings;
import name.abuchen.portfolio.model.Client;

@SuppressWarnings("nls")
public final class FactorMappingDialog extends TitleAreaDialog
{
    private final Client client;
    private final FactorMap.Position position;
    private final FactorSettings.Settings settings;
    private final String original;
    private final EnumMap<Factor, Text> fields = new EnumMap<>(Factor.class);
    private Text date;
    private Text source;
    private boolean reset;

    public FactorMappingDialog(Shell shell, Client client, FactorMap.Position position)
    {
        super(shell);
        this.client = client;
        this.position = position;
        this.settings = FactorSettings.load(client);
        this.original = client.getProperty(FactorSettings.PROPERTY);
        setShellStyle(getShellStyle() | SWT.RESIZE);
    }

    @Override
    protected Control createDialogArea(Composite parent)
    {
        var area = (Composite) super.createDialogArea(parent);
        setTitle("Expositions — " + position.vehicle().getName());
        setMessage("Pourcentage signé de la valeur du support : 100 = 1×, −100 = short 1×.\n"
                        + "Vide = inconnu. Zéro = absence d'exposition explicitement confirmée.");
        var form = new Composite(area, SWT.NONE);
        GridLayoutFactory.fillDefaults().numColumns(2).margins(16, 10).spacing(12, 7).applyTo(form);
        GridDataFactory.fillDefaults().grab(true, true).hint(680, SWT.DEFAULT).applyTo(form);
        for (var factor : Factor.portfolioAxes())
        {
            var field = field(form, factor.label() + " (%)");
            fields.put(factor, field);
            Double value = position.exposures().get(factor);
            if (position.coverage().getOrDefault(factor, 0d) < .999999)
                value = null;
            field.setText(value == null ? "" : java.math.BigDecimal.valueOf(value * 100).stripTrailingZeros().toPlainString());
        }
        var mapping = settings.mappings().get(position.vehicle().getUUID());
        date = field(form, "Date de la composition (AAAA-MM-JJ)");
        date.setText(mapping == null ? LocalDate.now().toString() : mapping.date());
        source = field(form, "Source / justification");
        source.setText(mapping == null ? "" : mapping.source());
        var help = new Label(form, SWT.WRAP);
        help.setText("Taux : saisir un notionnel équivalent de même sensibilité (DV01), pas le poids obligataire. Références Treasury US. Les obligations européennes ne sont pas automatiquement assimilées à ces références US. "
                        + "DV01 = valeur × duration modifiée × 0,0001 ; équivalent = DV01 de la position / DV01 du contrat par unité notionnelle. "
                        + "Même courbe et même segment uniquement. OAT€i : taux réels ; monétaire EUR : pas assimilé au 2 ans ; crédit distinct.\n"
                        + "Actions : expositions géographiques, pas bêtas au S&P/EAFE. FX : +100 USD/EUR = 100 % exposé au dollar ; +100 JPY/EUR = 100 % exposé au yen. Un signe négatif indique une position vendeuse. "
                        + "Ces hypothèses remplacent toute la correspondance automatique de ce support.");
        GridDataFactory.fillDefaults().span(2, 1).grab(true, false).hint(660, SWT.DEFAULT).applyTo(help);
        return area;
    }

    private static Text field(Composite parent, String name)
    {
        new Label(parent, SWT.NONE).setText(name);
        var text = new Text(parent, SWT.BORDER);
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
        if (id == 42)
        {
            reset = true;
            okPressed();
        }
        else
            super.buttonPressed(id);
    }

    @Override
    protected void okPressed()
    {
        try
        {
            FactorSettings.Mapping mapping = null;
            if (!reset)
            {
                if (LocalDate.parse(date.getText().strip()).isAfter(LocalDate.now()))
                    throw new IllegalArgumentException("Une date future n'est pas admise.");
                var values = new EnumMap<Factor, Double>(Factor.class);
                fields.forEach((factor, field) -> {
                    if (!field.getText().isBlank())
                        values.put(factor, Double.parseDouble(field.getText().strip().replace(',', '.')) / 100);
                });
                mapping = new FactorSettings.Mapping(date.getText().strip(), source.getText().strip(), values);
            }
            FactorSettings.save(client, settings.with(position.vehicle().getUUID(), mapping), original);
            super.okPressed();
        }
        catch (RuntimeException e)
        {
            reset = false;
            setErrorMessage("Paramètres non enregistrés : " + e.getMessage());
        }
    }
}
