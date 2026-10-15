package name.abuchen.portfolio.ui.tax;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;

import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.money.Values;
import name.abuchen.portfolio.tax.PositionTaxEstimates;
import name.abuchen.portfolio.tax.PositionTaxEstimates.Row;
import name.abuchen.portfolio.tax.TaxEstimateSettings;
import name.abuchen.portfolio.tax.TaxEstimateSettings.Basis;
import name.abuchen.portfolio.tax.TaxEstimateSettings.LossTreatment;
import name.abuchen.portfolio.tax.TaxEstimateSettings.Profile;
import name.abuchen.portfolio.tax.TaxEstimateSettings.Settings;

@SuppressWarnings("nls")
public final class TaxEstimateDialog extends TitleAreaDialog
{
    private final Client client;
    private final Row row;
    private final Settings settings;
    private final String originalProperty;
    private final String originalMovements;
    private final LocalDate date = LocalDate.now();
    private Combo profile;
    private Combo losses;
    private Text amount;
    private Text effective;
    private Text rate;
    private Text review;
    private Text note;
    private Label preview;

    public TaxEstimateDialog(Shell shell, Client client, Row row)
    {
        super(shell);
        this.client = client;
        this.row = row;
        settings = TaxEstimateSettings.load(client);
        originalProperty = client.getProperty(TaxEstimateSettings.PROPERTY);
        originalMovements = PositionTaxEstimates.movements(client, row.portfolio(), row.security(), date);
        setShellStyle(getShellStyle() | SWT.RESIZE);
    }

    @Override
    protected Control createDialogArea(Composite parent)
    {
        var area = (Composite) super.createDialogArea(parent);
        setTitle("Fiscalité — " + row.security().getName());
        setMessage(row.portfolio().getName() + " · " + Values.Share.format(row.shares()) + " unités\n"
                        + "Renseigner la base totale en EUR à la date historique choisie.");
        var form = new Composite(area, SWT.NONE);
        GridLayoutFactory.fillDefaults().numColumns(2).margins(16, 12).spacing(12, 10).applyTo(form);
        GridDataFactory.fillDefaults().grab(true, true).hint(720, SWT.DEFAULT).applyTo(form);

        label(form, "Profil");
        profile = new Combo(form, SWT.READ_ONLY);
        profile.setItems(Arrays.stream(Profile.values()).filter(p -> p != Profile.PEA).map(TaxEstimateLabels::profile).toArray(String[]::new));
        GridDataFactory.fillDefaults().grab(true, false).hint(340, SWT.DEFAULT).applyTo(profile);
        effective = field(form, "Date de la base fiscale (AAAA-MM-JJ)");
        amount = field(form, "Base fiscale totale à cette date (EUR)");
        rate = field(form, "Taux d'estimation (%)");
        label(form, "Pertes");
        losses = new Combo(form, SWT.READ_ONLY);
        losses.setItems("Retenir une économie potentielle", "Ne pas retenir d'économie sur les pertes");
        GridDataFactory.fillDefaults().grab(true, false).hint(340, SWT.DEFAULT).applyTo(losses);
        review = field(form, "Prochaine clôture / révision (AAAA-MM-JJ)");
        note = field(form, "Source / justification");
        label(form, "Calcul");
        var calculation = new Label(form, SWT.WRAP);
        calculation.setText("Quantités à la date saisie. Ventes : coût fiscal moyen pondéré.");
        GridDataFactory.fillDefaults().grab(true, false).hint(340, SWT.DEFAULT).applyTo(calculation);
        var help = new Label(form, SWT.WRAP);
        help.setText("Le profil ne fixe aucun taux automatiquement. L'assurance-vie est une convention par support. "
                        + "Le même taux conventionnel s’applique aux gains et revenus. La clôture annuelle indiquée sera simulée chaque année. "
                        + "Rapprocher les paiements séparément. Une base historique est nécessaire pour une performance historique.");
        GridDataFactory.fillDefaults().span(2, 1).grab(true, false).hint(688, SWT.DEFAULT).applyTo(help);
        preview = new Label(form, SWT.WRAP);
        GridDataFactory.fillDefaults().span(2, 1).grab(true, false).hint(688, 55).applyTo(preview);

        var basis = row.basis();
        effective.setText(basis == null ? date.toString() : basis.validatedOn());
        profile.select(basis == null ? Profile.CTO.ordinal() : basis.profile().ordinal());
        losses.select(basis == null || basis.losses() == LossTreatment.POTENTIAL_SAVING ? 0 : 1);
        amount.setText(basis == null ? "" : decimal(basis.amount()));
        rate.setText(basis == null ? "" : decimal(basis.rateBps()));
        review.setText(basis == null || basis.reviewOn() == null ? "" : basis.reviewOn());
        note.setText(basis == null ? "" : basis.note());
        for (var text : new Text[] { amount, rate, review, note, effective })
            text.addModifyListener(e -> refreshPreview());
        profile.addListener(SWT.Selection, e -> {
            if (Profile.values()[profile.getSelectionIndex()] == Profile.EXEMPT)
                rate.setText("0");
            refreshPreview();
        });
        losses.addListener(SWT.Selection, e -> refreshPreview());
        refreshPreview();
        return area;
    }

    private static void label(Composite parent, String text)
    {
        new Label(parent, SWT.NONE).setText(text);
    }

    private static Text field(Composite parent, String label)
    {
        label(parent, label);
        var field = new Text(parent, SWT.BORDER);
        GridDataFactory.fillDefaults().grab(true, false).hint(340, SWT.DEFAULT).applyTo(field);
        return field;
    }

    private static String decimal(long cents)
    {
        return BigDecimal.valueOf(cents, 2).toPlainString().replace('.', ',');
    }

    private Basis read()
    {
        long bps = TaxEstimateLabels.cents(rate.getText());
        if (bps > 10_000)
            throw new IllegalArgumentException("Le taux doit être compris entre 0 et 100 %.");
        LocalDate seedDate = LocalDate.parse(effective.getText().strip());
        if (seedDate.isAfter(date))
            throw new IllegalArgumentException("La date de base ne peut pas être future.");
        long quantity = PositionTaxEstimates.sharesAt(row.portfolio(), row.security(), seedDate);
        return new Basis(Profile.values()[profile.getSelectionIndex()], (int) bps,
                        losses.getSelectionIndex() == 0 ? LossTreatment.POTENTIAL_SAVING : LossTreatment.ZERO_FLOOR,
                        TaxEstimateLabels.cents(amount.getText()), quantity, seedDate.toString(),
                        review.getText().isBlank() ? null : review.getText().strip(), PositionTaxEstimates.movements(client, row.portfolio(), row.security(), seedDate), note.getText().strip());
    }

    private void refreshPreview()
    {
        try
        {
            var basis = read();
            setErrorMessage(null);
            if (basis.shares() != row.shares())
                preview.setText("Base historique pour " + Values.Share.format(basis.shares()) + " unités. Le suivi rejouera les mouvements depuis cette date.");
            else if (row.marketValue() == null)
                preview.setText("Le calcul sera disponible lorsqu'une valorisation et un change seront disponibles.");
            else
            {
                var value = PositionTaxEstimates.calculate(row.marketValue(), basis.amount(), basis.rateBps(), basis.losses());
                preview.setText("Aperçu à la base saisie, hors mouvements ultérieurs : contribution " + Values.Amount.format(value.contribution())
                                + " EUR ; valeur nette estimée " + Values.Amount.format(value.netValue())
                                + " EUR.\nUn montant négatif est une économie potentielle, non garantie.");
            }
        }
        catch (RuntimeException e)
        {
            preview.setText(e instanceof DateTimeParseException ? "Date attendue : AAAA-MM-JJ." : e.getMessage());
        }
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent)
    {
        createButton(parent, IDialogConstants.OK_ID, "Valider la base et enregistrer", true);
        createButton(parent, IDialogConstants.CANCEL_ID, "Annuler", false);
    }

    @Override
    protected void okPressed()
    {
        try
        {
            if (!date.equals(LocalDate.now()) || !originalMovements.equals(
                            PositionTaxEstimates.movements(client, row.portfolio(), row.security(), LocalDate.now()))
                            || row.shares() != PositionTaxEstimates.sharesAt(row.portfolio(), row.security(), LocalDate.now()))
                throw new IllegalArgumentException("La position a changé. Actualiser puis rouvrir le formulaire.");
            TaxEstimateSettings.save(client, settings.with(row.key(), read()), originalProperty);
            super.okPressed();
        }
        catch (RuntimeException e)
        {
            setErrorMessage(e instanceof DateTimeParseException ? "Date attendue : AAAA-MM-JJ." : e.getMessage());
        }
    }
}
