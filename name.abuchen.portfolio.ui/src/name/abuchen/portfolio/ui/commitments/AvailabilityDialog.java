package name.abuchen.portfolio.ui.commitments;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.List;
import org.eclipse.jface.dialogs.*;
import org.eclipse.jface.layout.*;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.*;
import name.abuchen.portfolio.commitments.*;
import name.abuchen.portfolio.model.*;
import name.abuchen.portfolio.money.*;
import name.abuchen.portfolio.snapshot.ClientSnapshot;

/** The authoritative availability inputs are staged until Apply, then the taxonomy is rebuilt. */
public final class AvailabilityDialog extends TitleAreaDialog
{
    private final Client client;
    private final AvailabilityPlan.Plan initial;
    private final String originalProperty;
    private final Map<String, Map<Integer, Integer>> inputs = new TreeMap<>();
    private final List<Security> securities;
    private final Map<InvestmentVehicle, Long> values = new HashMap<>();
    private String valuationWarning;
    private final Map<Integer, Text> fields = new LinkedHashMap<>();
    private Combo choice;
    private Table table;
    private Label total, annualTotals;
    private Security selected;
    private boolean loading;

    public AvailabilityDialog(Shell shell, Client client, CurrencyConverter converter)
    {
        super(shell); this.client = client; initial = AvailabilityPlan.load(client); originalProperty = client.getProperty(AvailabilityPlan.PROPERTY);
        initial.allocations().forEach((id, weights) -> inputs.put(id, new TreeMap<>(weights)));
        securities = AvailabilityPlan.securities(client, initial, LocalDate.now());
        try
        {
            var eur = Commitments.strictEur(converter);
            ClientSnapshot.create(Availability.scope(client), converter.with("EUR"), LocalDate.now()).getPositionsByVehicle().forEach((vehicle, position) -> {
                if (!Availability.excluded(vehicle)) values.put(vehicle, eur.convert(LocalDate.now(), position.getPosition().calculateValue()).getAmount());
            });
        }
        catch (RuntimeException e) { values.clear(); valuationWarning = "Valorisation indisponible : " + e.getMessage(); }
        setShellStyle(getShellStyle() | SWT.RESIZE);
    }
    @Override protected Control createDialogArea(Composite parent)
    {
        var area = (Composite) super.createDialogArea(parent);
        setTitle("Disponibilités Xapa — tableau de référence");
        setMessage("Saisir des pourcentages de la valeur actuelle, avec la marge déjà incluse dans les années.\n"
                        + "Appliquer enregistre le tableau et recalcule la taxonomie. Le solde non réparti reste « À planifier ». Les comptes espèces sont à 100 % en Immédiate.");
        var form = new Composite(area, SWT.NONE); GridLayoutFactory.fillDefaults().numColumns(6).margins(10, 8).applyTo(form);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(form);
        new Label(form, SWT.NONE).setText("Fonds / titre"); choice = new Combo(form, SWT.READ_ONLY);
        choice.setItems(securities.stream().map(Security::getName).toArray(String[]::new)); GridDataFactory.fillDefaults().span(5, 1).grab(true, false).applyTo(choice);
        for (int year : AvailabilityPlan.HORIZONS)
        {
            new Label(form, SWT.NONE).setText(AvailabilityPlan.label(year) + " (%)"); var field = new Text(form, SWT.BORDER | SWT.RIGHT);
            GridDataFactory.fillDefaults().grab(true, false).hint(100, SWT.DEFAULT).applyTo(field); fields.put(year, field);
            field.addModifyListener(e -> refreshTotals());
        }
        // Ten fields occupy four rows of three pairs; pad the final row.
        for (int i = 0; i < (3 - fields.size() % 3) % 3; i++) { new Label(form, SWT.NONE); new Label(form, SWT.NONE); }
        total = new Label(form, SWT.WRAP); GridDataFactory.fillDefaults().span(6, 1).grab(true, false).applyTo(total);
        choice.addListener(SWT.Selection, e -> { if (stage()) load(securities.get(choice.getSelectionIndex())); else choice.select(securities.indexOf(selected)); });
        table = new Table(area, SWT.FULL_SELECTION | SWT.BORDER | SWT.H_SCROLL | SWT.V_SCROLL); table.setHeaderVisible(true); table.setLinesVisible(true);
        GridDataFactory.fillDefaults().grab(true, true).hint(1100, 240).applyTo(table);
        var labels = new ArrayList<>(List.of("Type", "Fonds / compte", "Valeur EUR")); AvailabilityPlan.HORIZONS.forEach(y -> labels.add(AvailabilityPlan.label(y) + " %")); labels.add("Total planifié %"); labels.add("À planifier %");
        for (int i = 0; i < labels.size(); i++) { var column = new TableColumn(table, i <= 1 ? SWT.LEFT : SWT.RIGHT); column.setText(labels.get(i)); column.setWidth(i == 1 ? 250 : 105); }
        table.addListener(SWT.Selection, e -> { if (e.item != null && e.item.getData() instanceof Security security && stage()) load(security); });
        annualTotals = new Label(area, SWT.WRAP); GridDataFactory.fillDefaults().grab(true, false).hint(1050, 65).applyTo(annualTotals);
        if (!securities.isEmpty()) load(securities.getFirst()); refreshTable(); return area;
    }
    private Map<Integer, Integer> read()
    {
        var weights = new TreeMap<Integer, Integer>();
        fields.forEach((year, field) -> {
            String text = field.getText().strip().replace(',', '.');
            int weight;
            try { weight = text.isEmpty() ? 0 : new BigDecimal(text).movePointRight(2).intValueExact(); }
            catch (ArithmeticException | NumberFormatException e) { throw new IllegalArgumentException("Pourcentages avec deux décimales au maximum."); }
            if (weight < 0 || weight > 10000) throw new IllegalArgumentException("Saisir des pourcentages entre 0 et 100.");
            if (weight != 0) weights.put(year, weight);
        });
        if (AvailabilityPlan.planned(weights) > 10000) throw new IllegalArgumentException("La somme dépasse 100 %.");
        return weights;
    }
    private boolean stage()
    {
        if (selected == null) return true;
        try { inputs.put(selected.getUUID(), read()); setErrorMessage(null); refreshTable(); return true; }
        catch (IllegalArgumentException e) { setErrorMessage(e.getMessage()); return false; }
    }
    private static String percent(int value) { return String.format(Locale.FRANCE, "%.2f", value / 100.0); }
    private void load(Security security)
    {
        loading = true; selected = security; choice.select(securities.indexOf(security));
        var weights = inputs.getOrDefault(security.getUUID(), Map.of());
        fields.forEach((year, field) -> { int weight = weights.getOrDefault(year, 0); field.setText(weight == 0 ? "" : percent(weight)); });
        loading = false; refreshTotals();
    }
    private void refreshTotals()
    {
        if (loading || total == null) return;
        try { var weights = read(); int planned = AvailabilityPlan.planned(weights); if (selected != null) { inputs.put(selected.getUUID(), weights); refreshTable(); } total.setText("Total planifié : " + percent(planned) + " %   •   À planifier : " + percent(10000 - planned) + " %"); }
        catch (IllegalArgumentException e) { total.setText(e.getMessage()); }
    }
    private void refreshTable()
    {
        if (table == null || table.isDisposed()) return;
        table.removeAll(); var sums = new TreeMap<Integer, Long>();
        var vehicles = new ArrayList<InvestmentVehicle>(securities); vehicles.addAll(Availability.scope(client).getAccounts());
        for (var vehicle : vehicles.stream().sorted(AssetClasses.comparator(client)).toList())
        {
            var weights = vehicle instanceof Account ? Map.of(0, 10000) : inputs.getOrDefault(vehicle.getUUID(), Map.of());
            int planned = AvailabilityPlan.planned(weights); Long value = values.get(vehicle);
            var cells = new ArrayList<>(List.of(AssetClasses.type(client, vehicle).label(), vehicle.getName(), value == null ? "—" : Values.Amount.format(value)));
            for (int year : AvailabilityPlan.HORIZONS)
            {
                int weight = weights.getOrDefault(year, 0); cells.add(weight == 0 ? "" : percent(weight));
                if (value != null) sums.merge(year, Math.round(value * weight / 10000.0), Math::addExact);
            }
            cells.add(percent(planned)); cells.add(percent(10000 - planned));
            var row = new TableItem(table, SWT.NONE); row.setText(cells.toArray(String[]::new)); row.setData(vehicle);
        }
        if (annualTotals != null)
            annualTotals.setText(valuationWarning != null ? valuationWarning : "Totaux estimés (EUR), comptes espèces inclus :\n" + AvailabilityPlan.HORIZONS.stream()
                            .map(y -> AvailabilityPlan.label(y) + " : " + Values.Amount.format(sums.getOrDefault(y, 0L))).collect(java.util.stream.Collectors.joining("   •   ")));
    }
    @Override protected void createButtonsForButtonBar(Composite parent)
    {
        createButton(parent, IDialogConstants.OK_ID, "Appliquer et recalculer la taxonomie", true);
        createButton(parent, IDialogConstants.CANCEL_ID, "Annuler", false);
    }
    @Override protected void okPressed()
    {
        if (!stage()) return;
        try
        {
            if (!Objects.equals(originalProperty, client.getProperty(AvailabilityPlan.PROPERTY))
                            || originalProperty == null && !initial.equals(AvailabilityPlan.load(client)))
                throw new IllegalArgumentException("Les données ont changé pendant la saisie. Rouvrir le tableau.");
            AvailabilityPlan.apply(client, new AvailabilityPlan.Plan(1, initial.taxonomyId(), inputs)); super.okPressed();
        }
        catch (RuntimeException e) { setErrorMessage(e.getMessage()); }
    }
}
