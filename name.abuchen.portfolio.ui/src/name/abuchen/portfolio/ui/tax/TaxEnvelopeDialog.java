package name.abuchen.portfolio.ui.tax;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Portfolio;
import name.abuchen.portfolio.model.Transaction;
import name.abuchen.portfolio.tax.PositionTaxEstimates;
import name.abuchen.portfolio.tax.TaxEnvelopeProfiles;
import name.abuchen.portfolio.tax.TaxEstimateSettings;

@SuppressWarnings("nls")
public final class TaxEnvelopeDialog extends TitleAreaDialog
{
    private final Client client;
    private final Portfolio portfolio;
    private final String expected;
    private final TaxEnvelopeProfiles.Settings settings;
    private Text name;
    private Text opening;
    private Text rate;
    private Text note;
    private Button complete;
    private Button resident;
    private Label maturity;

    public TaxEnvelopeDialog(Shell shell, Client client, Portfolio portfolio)
    {
        super(shell);
        this.client = client;
        this.portfolio = portfolio;
        expected = client.getProperty(TaxEnvelopeProfiles.PROPERTY);
        settings = TaxEnvelopeProfiles.load(client);
        setShellStyle(getShellStyle() | SWT.RESIZE);
    }

    @Override
    protected Control createDialogArea(Composite parent)
    {
        var area = (Composite) super.createDialogArea(parent);
        setTitle("Profil d'enveloppe — PEA");
        setMessage("Un seul paramétrage pour tous les titres de « " + portfolio.getName() + " ».");
        var form = new Composite(area, SWT.NONE);
        GridLayoutFactory.fillDefaults().numColumns(2).margins(16, 12).spacing(12, 10).applyTo(form);
        GridDataFactory.fillDefaults().grab(true, true).hint(740, SWT.DEFAULT).applyTo(form);
        var profile = settings.profiles().get(portfolio.getUUID());
        name = field(form, "Nom du profil", profile == null ? "PEA — " + portfolio.getName() : profile.name());
        value(form, "Compte-titres", portfolio.getName());
        value(form, "Espèces associées", portfolio.getReferenceAccount() == null ? "Compte espèces à rattacher" : portfolio.getReferenceAccount().getName());
        opening = field(form, "Ouverture / premier versement", profile == null ? "" : profile.openedOn());
        opening.setMessage("AAAA-MM-JJ");
        value(form, "Hypothèse", "Retrait après cinq ans · résident fiscal français");
        new Label(form, SWT.NONE).setText("À partir du");
        maturity = new Label(form, SWT.NONE);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(maturity);
        rate = field(form, "Prélèvements sociaux estimés (%)", BigDecimal.valueOf(profile == null ? 1860 : profile.socialRateBps(), 2).toPlainString().replace('.', ','));
        complete = new Button(form, SWT.CHECK);
        complete.setText("Historique complet depuis l'ouverture, sans transfert initial manquant");
        complete.setSelection(profile != null && profile.completeHistory());
        GridDataFactory.fillDefaults().span(2, 1).applyTo(complete);
        resident = new Button(form, SWT.CHECK);
        resident.setText("Résidence fiscale française confirmée");
        resident.setSelection(profile != null && profile.frenchResident());
        GridDataFactory.fillDefaults().span(2, 1).applyTo(resident);
        note = field(form, "Source / justification", profile == null ? "Taux actuel de 18,6 % : impots.gouv.fr, vérifié le 06/10/2026. Hypothèse modifiable pour le retrait futur." : profile.note());
        var titles = portfolio.getTransactions().stream().map(Transaction::getSecurity).distinct().toList();
        long closed = titles.stream().filter(s -> PositionTaxEstimates.sharesAt(portfolio, s, LocalDate.now()) == 0).count();
        long overrides = titles.stream().filter(s -> TaxEstimateSettings.load(client).positions()
                        .containsKey(TaxEstimateSettings.key(portfolio.getUUID(), s.getUUID()))).count();
        var summary = new Label(form, SWT.WRAP);
        summary.setText(titles.size() + " titres repérés, dont " + closed + " positions fermées. "
                        + (titles.size() - overrides) + " héritent automatiquement ; " + overrides + " exceptions manuelles conservées.\n"
                        + "Les prochains achats hériteront aussi du profil. Les bases et quantités sont reconstruites ; aucune clôture annuelle à saisir.\n\n"
                        + "Les gains des ventes internes restent provisionnés dans le PEA. Les pertes réduisent les gains ; la provision du PEA complet ne devient pas une créance fiscale. "
                        + "Le taux du scénario s'applique à toute la période comparée et ne garantit pas le taux futur. Un retrait réel demandera un traitement complémentaire.");
        GridDataFactory.fillDefaults().span(2, 1).grab(true, false).hint(708, SWT.DEFAULT).applyTo(summary);
        opening.addModifyListener(e -> updateMaturity());
        updateMaturity();
        return area;
    }

    private void updateMaturity()
    {
        try
        {
            maturity.setText(LocalDate.parse(opening.getText().strip()).plusYears(5).toString());
        }
        catch (RuntimeException e)
        {
            maturity.setText("Renseigner la date d'ouverture");
        }
    }

    private static Text field(Composite form, String label, String value)
    {
        new Label(form, SWT.NONE).setText(label);
        var text = new Text(form, SWT.BORDER);
        GridDataFactory.fillDefaults().grab(true, false).hint(420, SWT.DEFAULT).applyTo(text);
        text.setText(value);
        return text;
    }

    private static void value(Composite form, String label, String text)
    {
        new Label(form, SWT.NONE).setText(label);
        var value = new Label(form, SWT.WRAP);
        value.setText(text);
        GridDataFactory.fillDefaults().grab(true, false).hint(420, SWT.DEFAULT).applyTo(value);
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent)
    {
        createButton(parent, IDialogConstants.OK_ID, "Enregistrer pour cette enveloppe", true);
        createButton(parent, IDialogConstants.CANCEL_ID, "Annuler", false);
    }

    @Override
    protected void okPressed()
    {
        try
        {
            if (portfolio.getReferenceAccount() == null)
                throw new IllegalArgumentException("Rattacher d'abord le compte espèces du PEA.");
            if (LocalDate.parse(opening.getText().strip()).isAfter(LocalDate.now()))
                throw new IllegalArgumentException("La date d'ouverture ne peut pas être future.");
            var profile = new TaxEnvelopeProfiles.Pea(name.getText().strip(), portfolio.getUUID(),
                            portfolio.getReferenceAccount().getUUID(), opening.getText().strip(),
                            Math.toIntExact(TaxEstimateLabels.cents(rate.getText())), complete.getSelection(), resident.getSelection(),
                            LocalDate.now().toString(), note.getText().strip());
            TaxEnvelopeProfiles.save(client, settings.with(profile), expected);
            super.okPressed();
        }
        catch (RuntimeException e)
        {
            setErrorMessage(e instanceof java.time.format.DateTimeParseException ? "Date attendue : AAAA-MM-JJ." : e.getMessage());
        }
    }
}
