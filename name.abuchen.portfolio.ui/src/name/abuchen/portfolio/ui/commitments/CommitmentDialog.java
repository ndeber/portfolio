package name.abuchen.portfolio.ui.commitments;

import java.time.LocalDate;
import java.util.*;
import java.util.List;
import org.eclipse.jface.dialogs.*;
import org.eclipse.jface.layout.*;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.*;
import name.abuchen.portfolio.commitments.Commitments;
import name.abuchen.portfolio.model.*;
import name.abuchen.portfolio.money.*;
import name.abuchen.portfolio.ui.util.StringToCurrencyConverter;

/** Stages input attributes until OK. No transactions or files are written. */
public final class CommitmentDialog extends TitleAreaDialog
{
    private record Input(Long total, List<Long> years) { }
    private final Client client;
    private final CurrencyConverter converter;
    private final List<Security> securities;
    private final Map<Security, Input> pending = new LinkedHashMap<>();
    private final Map<Security, Map<String, Object>> original = new HashMap<>();
    private Combo choice;
    private Text total;
    private final List<Text> years = new ArrayList<>();
    private Label calculated;
    private Table table;
    private Security selected;
    private boolean loading;

    public CommitmentDialog(Shell shell, Client client, CurrencyConverter converter)
    {
        super(shell); this.client = client; this.converter = converter;
        securities = client.getSecurities().stream().filter(s -> !Commitments.excluded(s)).sorted(Comparator.comparing((Security s) -> s.getName())).toList();
        for (var security : securities) original.put(security, new HashMap<>(security.getAttributes().getMap()));
        setShellStyle(getShellStyle() | SWT.RESIZE);
    }
    @Override protected Control createDialogArea(Composite parent)
    {
        var area = (Composite) super.createDialogArea(parent);
        setTitle("Engagements PE — montants et prévisions en EUR");
        setMessage("Réalisé = achat initial hors frais + appels de fonds, sans déduire les distributions.\n"
                        + "Ventilez le restant entre 2026, 2027, 2028 et 2029+. Les écarts sont signalés ; les années ne se décalent pas automatiquement.");
        var form = new Composite(area, SWT.NONE); GridLayoutFactory.fillDefaults().numColumns(2).margins(10, 8).applyTo(form);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(form);
        new Label(form, SWT.NONE).setText("Fonds / titre");
        choice = new Combo(form, SWT.READ_ONLY); choice.setItems(securities.stream().map(Security::getName).toArray(String[]::new));
        GridDataFactory.fillDefaults().grab(true, false).applyTo(choice);
        new Label(form, SWT.NONE).setText("Engagement total (EUR)"); total = field(form);
        for (String year : Commitments.LABELS)
        { new Label(form, SWT.NONE).setText("Appels encore prévus — " + year); years.add(field(form)); }
        calculated = new Label(form, SWT.WRAP); GridDataFactory.fillDefaults().span(2, 1).grab(true, false).hint(950, 60).applyTo(calculated);
        choice.addListener(SWT.Selection, e -> {
            if (stage()) load(securities.get(choice.getSelectionIndex()));
            else choice.select(securities.indexOf(selected));
        });
        total.addModifyListener(e -> refreshCalculation()); years.forEach(t -> t.addModifyListener(e -> refreshCalculation()));
        table = new Table(area, SWT.BORDER | SWT.FULL_SELECTION | SWT.H_SCROLL | SWT.V_SCROLL);
        table.setHeaderVisible(true); table.setLinesVisible(true); GridDataFactory.fillDefaults().grab(true, true).hint(1050, 260).applyTo(table);
        String[] headers = {"Fonds", "Total", "Réalisé", "Restant", "2026", "2027", "2028", "2029+", "Total prévisions", "Non ventilé"};
        for (int i = 0; i < headers.length; i++) { var col = new TableColumn(table, i == 0 ? SWT.LEFT : SWT.RIGHT); col.setText(headers[i]); col.setWidth(i == 0 ? 260 : 100); }
        table.addListener(SWT.Selection, e -> {
            if (table.getSelectionCount() == 0) return;
            var security = (Security) table.getSelection()[0].getData();
            if (security != null && stage()) load(security);
        });
        var candidates = Commitments.scope(client);
        if (!securities.isEmpty()) load(candidates.isEmpty() ? securities.getFirst() : candidates.getFirst());
        refreshTable(); return area;
    }
    private Text field(Composite parent)
    { var field = new Text(parent, SWT.BORDER | SWT.RIGHT); GridDataFactory.fillDefaults().grab(true, false).applyTo(field); return field; }
    private Long parse(String text, boolean nullable)
    {
        if (text.isBlank()) return nullable ? null : 0L;
        long amount = new StringToCurrencyConverter(Values.Amount).convert(text);
        if (amount < 0) throw new IllegalArgumentException("Saisir des montants positifs ou nuls.");
        return amount;
    }
    private Input read()
    {
        Long amount = parse(total.getText(), true);
        var forecast = years.stream().map(t -> parse(t.getText(), false)).toList();
        if (amount == null && forecast.stream().anyMatch(v -> v != 0)) throw new IllegalArgumentException("Renseigner l'engagement total.");
        return new Input(amount, forecast);
    }
    private boolean stage()
    {
        if (selected == null) return true;
        try { pending.put(selected, read()); setErrorMessage(null); refreshTable(); return true; }
        catch (IllegalArgumentException e) { setErrorMessage(e.getMessage()); return false; }
    }
    private Input input(Security security)
    {
        if (pending.containsKey(security)) return pending.get(security);
        return new Input(Commitments.value(security, Commitments.TOTAL), Commitments.YEARS.stream().map(id -> {
            Long value = Commitments.value(security, id); return value == null ? 0L : value;
        }).toList());
    }
    private void load(Security security)
    {
        loading = true; selected = security; choice.select(securities.indexOf(security));
        try
        {
            var values = input(security); total.setText(values.total() == null ? "" : Values.Amount.format(values.total()));
            for (int i = 0; i < 4; i++) years.get(i).setText(Values.Amount.format(values.years().get(i)));
        }
        catch (IllegalArgumentException e) { total.setText(""); years.forEach(t -> t.setText("")); setErrorMessage(e.getMessage()); }
        loading = false; refreshCalculation();
    }
    private static String amount(Long value) { return value == null ? "—" : Values.Amount.format(value); }
    private void refreshCalculation()
    {
        if (loading || selected == null) return;
        try
        {
            var values = read(); var row = Commitments.row(client, selected, converter, LocalDate.now());
            Long remaining = values.total() == null || row.paid() == null ? null : Math.subtractExact(values.total(), row.paid());
            long sum = 0; for (long value : values.years()) sum = Math.addExact(sum, value);
            calculated.setText("Réalisé : " + amount(row.paid()) + " EUR   •   Restant : " + amount(remaining) + " EUR   •   Prévisions : " + amount(sum)
                            + " EUR   •   Non ventilé : " + (remaining == null ? "—" : amount(Math.subtractExact(remaining, sum))) + " EUR\n"
                            + (row.paid() == null ? String.join(" ; ", row.warnings()) : remaining != null && remaining < 0 ? "Le réalisé dépasse l'engagement total." : "Un écart négatif signifie que les prévisions dépassent le restant."));
        }
        catch (RuntimeException e) { calculated.setText("Montants à vérifier : " + e.getMessage()); }
    }
    private void refreshTable()
    {
        if (table == null) return;
        table.removeAll(); var all = new LinkedHashSet<>(Commitments.scope(client)); all.addAll(pending.keySet());
        var totals = new TableItem(table, SWT.NONE);
        long[] sums = new long[9]; boolean complete = !all.isEmpty();
        for (var security : all)
        {
            var row = new TableItem(table, SWT.NONE); row.setData(security);
            try
            {
                var values = input(security); var data = Commitments.row(client, security, converter, LocalDate.now());
                Long remaining = values.total() == null || data.paid() == null ? null : Math.subtractExact(values.total(), data.paid());
                long sum = 0; for (long value : values.years()) sum = Math.addExact(sum, value);
                row.setText(new String[] {security.getName(), amount(values.total()), amount(data.paid()), amount(remaining),
                                amount(values.years().get(0)), amount(values.years().get(1)), amount(values.years().get(2)), amount(values.years().get(3)), amount(sum), remaining == null ? "—" : amount(remaining - sum)});
                if (remaining != null)
                {
                    long[] amounts = {values.total(), data.paid(), remaining, values.years().get(0), values.years().get(1), values.years().get(2), values.years().get(3), sum, remaining - sum};
                    for (int i = 0; i < sums.length; i++) sums[i] = Math.addExact(sums[i], amounts[i]);
                    if (remaining < 0) complete = false;
                }
                else complete = false;
            }
            catch (RuntimeException e) { complete = false; row.setText(new String[] {security.getName(), "À vérifier : " + e.getMessage()}); }
        }
        String[] cells = new String[10]; cells[0] = complete ? "TOTAL" : "TOTAL RENSEIGNÉ";
        for (int i = 0; i < sums.length; i++) cells[i + 1] = amount(sums[i]);
        totals.setText(cells);
    }
    @Override protected void createButtonsForButtonBar(Composite parent)
    { createButton(parent, IDialogConstants.OK_ID, "Appliquer au portefeuille", true); createButton(parent, IDialogConstants.CANCEL_ID, "Annuler", false); }
    @Override protected void okPressed()
    {
        if (!stage()) return;
        try
        {
            for (var security : pending.keySet())
                if (!client.getSecurities().contains(security) || !original.get(security).equals(security.getAttributes().getMap()))
                    throw new IllegalArgumentException("Les attributs ont changé pendant la saisie. Rouvrez ce tableau.");
            Commitments.ensureAttributes(client);
            pending.forEach((security, values) -> Commitments.save(client, security, values.total(), values.years()));
            client.markDirty(); super.okPressed();
        }
        catch (RuntimeException e) { setErrorMessage(e.getMessage()); }
    }
}
