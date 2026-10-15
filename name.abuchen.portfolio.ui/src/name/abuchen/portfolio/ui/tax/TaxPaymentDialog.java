package name.abuchen.portfolio.ui.tax;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

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

import name.abuchen.portfolio.model.Account;
import name.abuchen.portfolio.model.AccountTransaction;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.money.CurrencyConverter;
import name.abuchen.portfolio.money.Values;
import name.abuchen.portfolio.tax.PositionTaxEstimates.Row;
import name.abuchen.portfolio.tax.TaxPaymentLinks;

@SuppressWarnings("nls")
public final class TaxPaymentDialog extends TitleAreaDialog
{
    private record Payment(Account account, AccountTransaction transaction, long euros)
    {
        String label()
        {
            return (transaction.getType() == AccountTransaction.Type.TAX_REFUND ? "Remboursement · " : "Paiement · ") + transaction.getDateTime().toLocalDate() + " · " + account.getName() + " · " + Values.Amount.format(euros) + " EUR";
        }
    }

    private final Client client;
    private final Row row;
    private final String expected;
    private final TaxPaymentLinks.Settings settings;
    private final List<TaxPaymentLinks.Link> links;
    private final List<Payment> payments = new ArrayList<>();
    private Text debt;
    private Text amount;
    private Combo payment;
    private org.eclipse.swt.widgets.List list;

    public TaxPaymentDialog(Shell shell, Client client, Row row, CurrencyConverter converter)
    {
        super(shell);
        this.client = client;
        this.row = row;
        expected = client.getProperty(TaxPaymentLinks.PROPERTY);
        settings = TaxPaymentLinks.load(client);
        links = new ArrayList<>(settings.links());
        for (var account : client.getAccounts())
            for (var transaction : account.getTransactions())
                if ((transaction.getType() == AccountTransaction.Type.TAXES || transaction.getType() == AccountTransaction.Type.TAX_REFUND))
                    payments.add(new Payment(account, transaction,
                                    name.abuchen.portfolio.tax.TaxPositionLedger.euros(converter, transaction.getDateTime().toLocalDate(), transaction.getMonetaryAmount())));
        payments.sort(Comparator.comparing(p -> p.transaction().getDateTime()));
        setShellStyle(getShellStyle() | SWT.RESIZE);
    }

    @Override
    protected Control createDialogArea(Composite parent)
    {
        var area = (Composite) super.createDialogArea(parent);
        setTitle("Rapprocher les impôts — " + row.security().getName());
        setMessage(row.portfolio().getName() + ". Aucun paiement n'est créé : on rattache une part d'une opération existante.");
        var form = new Composite(area, SWT.NONE);
        GridLayoutFactory.fillDefaults().numColumns(2).margins(16, 12).applyTo(form);
        GridDataFactory.fillDefaults().grab(true, true).hint(720, SWT.DEFAULT).applyTo(form);
        new Label(form, SWT.NONE).setText("Dette (+) / créance (−) initiale (EUR)");
        debt = new Text(form, SWT.BORDER);
        debt.setText(BigDecimal.valueOf(settings.openingDebt().getOrDefault(row.key(), 0L), 2).toPlainString());
        GridDataFactory.fillDefaults().grab(true, false).applyTo(debt);
        new Label(form, SWT.NONE).setText("Paiement réel");
        payment = new Combo(form, SWT.READ_ONLY);
        payment.setItems(payments.stream().map(Payment::label).toArray(String[]::new));
        GridDataFactory.fillDefaults().grab(true, false).applyTo(payment);
        new Label(form, SWT.NONE).setText("Part affectée à cette position (EUR)");
        amount = new Text(form, SWT.BORDER);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(amount);
        var add = new org.eclipse.swt.widgets.Button(form, SWT.PUSH);
        add.setText("Ajouter ce rapprochement");
        GridDataFactory.fillDefaults().span(2, 1).applyTo(add);
        add.addListener(SWT.Selection, e -> {
            try
            {
                if (payment.getSelectionIndex() < 0)
                    throw new IllegalArgumentException("Sélectionner un paiement.");
                var selected = payments.get(payment.getSelectionIndex());
                long euros = TaxEstimateLabels.cents(amount.getText());
                long allocated = links.stream().filter(l -> l.account().equals(selected.account().getUUID())
                                && l.transaction().equals(selected.transaction().getUUID())).mapToLong(TaxPaymentLinks.Link::euros).sum();
                if (euros <= 0 || euros > selected.euros() - allocated)
                    throw new IllegalArgumentException("Montant positif requis, dans la limite du solde non affecté.");
                links.add(new TaxPaymentLinks.Link(selected.account().getUUID(), selected.transaction().getUUID(), row.key(), euros));
                refresh();
                setErrorMessage(null);
            }
            catch (RuntimeException ex)
            {
                setErrorMessage(ex.getMessage());
            }
        });
        list = new org.eclipse.swt.widgets.List(form, SWT.BORDER | SWT.V_SCROLL);
        GridDataFactory.fillDefaults().span(2, 1).grab(true, true).hint(680, 140).applyTo(list);
        var remove = new org.eclipse.swt.widgets.Button(form, SWT.PUSH);
        remove.setText("Retirer le rapprochement sélectionné");
        GridDataFactory.fillDefaults().span(2, 1).applyTo(remove);
        remove.addListener(SWT.Selection, e -> {
            int index = list.getSelectionIndex();
            var own = links.stream().filter(l -> l.position().equals(row.key())).toList();
            if (index >= 0)
                links.remove(own.get(index));
            refresh();
        });
        refresh();
        return area;
    }

    private void refresh()
    {
        list.setItems(links.stream().filter(l -> l.position().equals(row.key())).map(l -> {
            var real = payments.stream().filter(p -> p.transaction().getUUID().equals(l.transaction()) && p.account().getUUID().equals(l.account())).findFirst();
            return (real.isPresent() ? real.get().label() : "Opération absente") + " → " + Values.Amount.format(l.euros()) + " EUR affectés";
        }).toArray(String[]::new));
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent)
    {
        createButton(parent, IDialogConstants.OK_ID, "Enregistrer", true);
        createButton(parent, IDialogConstants.CANCEL_ID, "Annuler", false);
    }

    @Override
    protected void okPressed()
    {
        try
        {
            var opening = new HashMap<>(settings.openingDebt());
            String value = debt.getText().strip();
            opening.put(row.key(), value.startsWith("-") ? -TaxEstimateLabels.cents(value.substring(1)) : TaxEstimateLabels.cents(value));
            TaxPaymentLinks.save(client, new TaxPaymentLinks.Settings(1, links, opening), expected);
            super.okPressed();
        }
        catch (RuntimeException e)
        {
            setErrorMessage(e.getMessage());
        }
    }
}
