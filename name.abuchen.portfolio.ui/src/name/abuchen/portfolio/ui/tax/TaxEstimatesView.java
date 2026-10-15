package name.abuchen.portfolio.ui.tax;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.inject.Inject;

import org.eclipse.jface.action.ToolBarManager;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.ColumnViewerToolTipSupport;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;

import name.abuchen.portfolio.money.CurrencyConverterImpl;
import name.abuchen.portfolio.money.ExchangeRateProviderFactory;
import name.abuchen.portfolio.money.Values;
import name.abuchen.portfolio.tax.PositionTaxEstimates;
import name.abuchen.portfolio.tax.PositionTaxEstimates.Row;
import name.abuchen.portfolio.tax.PositionTaxPerformance;
import name.abuchen.portfolio.tax.TaxEstimateSettings;
import name.abuchen.portfolio.tax.TaxEnvelopeProfiles;
import name.abuchen.portfolio.tax.TaxPerformance;
import name.abuchen.portfolio.tax.TaxScope;
import name.abuchen.portfolio.tax.TaxPaymentLinks;
import name.abuchen.portfolio.tax.TaxPositionLedger;
import name.abuchen.portfolio.snapshot.filter.PortfolioClientFilter;
import name.abuchen.portfolio.util.Interval;
import name.abuchen.portfolio.ui.editor.AbstractFinanceView;
import name.abuchen.portfolio.ui.util.SimpleAction;
import name.abuchen.portfolio.ui.util.TableViewerCSVExporter;

@SuppressWarnings("nls")
public final class TaxEstimatesView extends AbstractFinanceView
{
    @Inject
    private ExchangeRateProviderFactory factory;

    private TableViewer viewer;
    private Label totals;
    private Label period;
    private LocalDate performanceStart;
    private Map<String, PositionTaxPerformance.Result> performances = Map.of();
    private Map<String, Long> currentBases = Map.of();
    private Map<String, Long> realizedReserves = Map.of();

    @Override
    protected String getDefaultTitle()
    {
        return TaxEstimateLabels.TITLE;
    }

    @Override
    protected void addButtons(ToolBarManager toolbar)
    {
        toolbar.add(new SimpleAction("Profil d’enveloppe…", a -> editEnvelope()));
        toolbar.add(new SimpleAction("Paramétrer la position…", a -> edit()));
        toolbar.add(new SimpleAction("Rapprocher un paiement…", a -> reconcile()));
        toolbar.add(new SimpleAction("Journal fiscal…", a -> showLedger()));
        toolbar.add(new SimpleAction("Ventiler une distribution…", a -> splitDistribution()));
        toolbar.add(new SimpleAction("Début de période…", a -> choosePeriod()));
        toolbar.add(new SimpleAction("Actualiser", a -> notifyModelUpdated()));
        toolbar.add(new SimpleAction("Exporter…", a -> new TableViewerCSVExporter(viewer).export("Fiscalite-estimee.csv")));
    }

    @Override
    protected Control createBody(Composite parent)
    {
        var body = new Composite(parent, SWT.NONE);
        GridLayoutFactory.fillDefaults().margins(10, 8).spacing(0, 10).applyTo(body);
        var description = new Label(body, SWT.WRAP);
        description.setText("Estimation indépendante par titre et par compte, en EUR. Double-cliquer pour modifier le profil hérité ou la position.\n"
                        + "Performance nette = performance avant impôts − fiscalité acquise − variation de fiscalité latente. "
                        + "Une contribution négative représente une économie potentielle.");
        GridDataFactory.fillDefaults().grab(true, false).hint(900, SWT.DEFAULT).applyTo(description);
        period = new Label(body, SWT.WRAP);
        GridDataFactory.fillDefaults().grab(true, false).hint(900, SWT.DEFAULT).applyTo(period);
        viewer = new TableViewer(body, SWT.FULL_SELECTION | SWT.BORDER | SWT.H_SCROLL | SWT.V_SCROLL);
        viewer.getTable().setHeaderVisible(true);
        viewer.getTable().setLinesVisible(true);
        GridDataFactory.fillDefaults().grab(true, true).applyTo(viewer.getTable());
        viewer.setContentProvider(ArrayContentProvider.getInstance());
        ColumnViewerToolTipSupport.enableFor(viewer);
        column("Titre", 185, SWT.LEFT, r -> r.security().getName());
        column("Compte", 150, SWT.LEFT, r -> r.portfolio().getName());
        column("Perf. avant impôts EUR", 155, SWT.RIGHT, r -> performance(r, PositionTaxPerformance.Result::beforeTax));
        column("Fiscalité acquise EUR", 145, SWT.RIGHT, r -> performance(r, PositionTaxPerformance.Result::accruedTax));
        column("Variation impôt latent EUR", 180, SWT.RIGHT, r -> performance(r, PositionTaxPerformance.Result::latentTaxChange));
        column("Perf. nette estimée EUR", 160, SWT.RIGHT, r -> performance(r, PositionTaxPerformance.Result::afterTax));
        column("Profil", 185, SWT.LEFT, r -> TaxEnvelopeProfiles.inherited(getClient(), r.portfolio(), r.key())
                        ? TaxEnvelopeProfiles.forPortfolio(getClient(), r.portfolio()).name() + " · hérité"
                        : r.basis() == null ? "—" : TaxEstimateLabels.profile(r.basis().profile()));
        column("Réserve / dette EUR", 145, SWT.RIGHT, r -> money(realizedReserves.get(r.key())));
        column("Quantité", 100, SWT.RIGHT, r -> Values.Share.format(r.shares()));
        column("Date valeur", 100, SWT.LEFT, r -> r.quoteDate() == null ? "—" : r.quoteDate().toString());
        column("Valeur EUR", 120, SWT.RIGHT, r -> money(r.marketValue()));
        column("Base courante EUR", 140, SWT.RIGHT, r -> money(currentBases.get(r.key())));
        column("Validation", 100, SWT.LEFT, r -> r.basis() == null ? "—" : r.basis().validatedOn());
        column("Taux %", 75, SWT.RIGHT, r -> r.basis() == null ? "—" : BigDecimal.valueOf(r.basis().rateBps(), 2).toPlainString());
        column("Écart fiscal EUR", 125, SWT.RIGHT, r -> r.estimate() == null ? "—" : money(r.estimate().taxableGain()));
        column("Contribution EUR", 135, SWT.RIGHT, r -> r.estimate() == null ? "—" : money(r.estimate().contribution()));
        column("Valeur nette estimée EUR", 165, SWT.RIGHT, r -> r.estimate() == null ? "—" : money(r.estimate().netValue()));
        column("État", 155, SWT.LEFT, r -> TaxEstimateLabels.status(r.status()));
        viewer.addDoubleClickListener(e -> edit());
        viewer.addSelectionChangedListener(e -> {
            if (e.getStructuredSelection().getFirstElement() instanceof Row row)
                setInformationPaneInput(row.security());
        });
        totals = new Label(body, SWT.WRAP);
        GridDataFactory.fillDefaults().grab(true, false).hint(900, SWT.DEFAULT).applyTo(totals);
        notifyModelUpdated();
        return body;
    }

    private static String money(Long value)
    {
        return value == null ? "—" : Values.Amount.format(value);
    }

    private String performance(Row row, Function<PositionTaxPerformance.Result, Long> amount)
    {
        var result = performances.get(row.key());
        return result == null ? "—" : money(amount.apply(result));
    }

    private void choosePeriod()
    {
        var initial = performanceStart == null ? LocalDate.now() : performanceStart;
        var dialog = new InputDialog(getActiveShell(), "Début de période",
                        "Date au format AAAA-MM-JJ. La fin de période est aujourd'hui.", initial.toString(), value -> {
                            try
                            {
                                return LocalDate.parse(value).isAfter(LocalDate.now()) ? "Choisir une date passée ou aujourd'hui." : null;
                            }
                            catch (RuntimeException e)
                            {
                                return "Date attendue : AAAA-MM-JJ.";
                            }
                        });
        if (dialog.open() == Window.OK)
        {
            performanceStart = LocalDate.parse(dialog.getValue());
            notifyModelUpdated();
        }
    }

    private void column(String title, int width, int style, Function<Row, String> text)
    {
        var column = new TableViewerColumn(viewer, style);
        column.getColumn().setText(title);
        column.getColumn().setWidth(width);
        column.getColumn().setMoveable(true);
        column.setLabelProvider(new ColumnLabelProvider()
        {
            @Override
            public String getText(Object element)
            {
                return text.apply((Row) element);
            }

            @Override
            public String getToolTipText(Object element)
            {
                var row = (Row) element;
                var result = performances.get(row.key());
                return row.detail() + (result == null ? "" : "\n" + result.detail());
            }
        });
    }

    private void edit()
    {
        if (viewer == null || !(viewer.getStructuredSelection().getFirstElement() instanceof Row row))
            return;
        try
        {
            if (TaxEnvelopeProfiles.inherited(getClient(), row.portfolio(), row.key()))
            {
                openEnvelope(row.portfolio());
                return;
            }
            if (new TaxEstimateDialog(getActiveShell(), getClient(), row).open() == Window.OK)
            {
                markDirty();
                notifyModelUpdated();
            }
        }
        catch (RuntimeException e)
        {
            MessageDialog.openError(getActiveShell(), TaxEstimateLabels.TITLE, e.getMessage());
        }
    }

    private void editEnvelope()
    {
        if (viewer != null && viewer.getStructuredSelection().getFirstElement() instanceof Row row)
        {
            openEnvelope(row.portfolio());
            return;
        }
        var selection = new name.abuchen.portfolio.ui.dialogs.ListSelectionDialog(getActiveShell(), new org.eclipse.jface.viewers.LabelProvider()
        {
            @Override
            public String getText(Object object)
            {
                return ((name.abuchen.portfolio.model.Portfolio) object).getName();
            }
        });
        selection.setTitle("Profil d'enveloppe — PEA");
        selection.setMessage("Choisir le compte-titres du PEA. Son compte espèces doit être dédié.");
        selection.setElements(getClient().getPortfolios());
        selection.setMultiSelection(false);
        if (selection.open() == Window.OK && selection.getResult().length > 0)
            openEnvelope((name.abuchen.portfolio.model.Portfolio) selection.getResult()[0]);
    }

    private void openEnvelope(name.abuchen.portfolio.model.Portfolio portfolio)
    {
        try
        {
            if (new TaxEnvelopeDialog(getActiveShell(), getClient(), portfolio).open() == Window.OK)
            {
                markDirty();
                notifyModelUpdated();
            }
        }
        catch (RuntimeException e)
        {
            MessageDialog.openError(getActiveShell(), TaxEstimateLabels.TITLE, e.getMessage());
        }
    }

    private void splitDistribution()
    {
        if (!(viewer.getStructuredSelection().getFirstElement() instanceof Row row))
            return;
        var transactions = getClient().getAccounts().stream().flatMap(a -> a.getTransactions().stream())
                        .filter(t -> t.getSecurity() == row.security() && t.getType() == name.abuchen.portfolio.model.AccountTransaction.Type.DISTRIBUTION)
                        .sorted(java.util.Comparator.comparing(t -> t.getDateTime())).toList();
        var selection = new name.abuchen.portfolio.ui.dialogs.ListSelectionDialog(getActiveShell(), new org.eclipse.jface.viewers.LabelProvider()
        {
            @Override
            public String getText(Object object)
            {
                var t = (name.abuchen.portfolio.model.AccountTransaction) object;
                return t.getDateTime().toLocalDate() + " · " + Values.Money.format(t.getMonetaryAmount());
            }
        });
        selection.setTitle("Ventiler une distribution PE");
        selection.setMessage("Sélectionner la distribution dont la part imposable est connue.");
        selection.setElements(transactions);
        selection.setMultiSelection(false);
        if (selection.open() != Window.OK)
            return;
        var t = (name.abuchen.portfolio.model.AccountTransaction) selection.getResult()[0];
        try
        {
            var converter = new CurrencyConverterImpl(factory, "EUR");
            long gross = TaxPositionLedger.euros(converter, t.getDateTime().toLocalDate(), t.getMonetaryAmount().add(t.getUnitSum(name.abuchen.portfolio.model.Transaction.Unit.Type.TAX)));
            var previous = name.abuchen.portfolio.tax.TaxDistributionSplits.load(getClient()).get(t.getUUID());
            var input = new InputDialog(getActiveShell(), "Part imposable de la distribution",
                            "Distribution totale : " + money(gross) + " EUR. Part imposable en EUR (0 autorisé) ; le solde remboursera la base fiscale.",
                            previous == null ? "" : BigDecimal.valueOf(previous, 2).toPlainString(), value -> {
                                try
                                {
                                    return TaxEstimateLabels.cents(value) <= gross ? null : "La part imposable dépasse la distribution.";
                                }
                                catch (RuntimeException e)
                                {
                                    return "Saisir un montant positif ou nul en EUR.";
                                }
                            });
            if (input.open() == Window.OK)
            {
                name.abuchen.portfolio.tax.TaxDistributionSplits.save(getClient(), t.getUUID(), TaxEstimateLabels.cents(input.getValue()));
                markDirty();
                notifyModelUpdated();
            }
        }
        catch (RuntimeException e)
        {
            MessageDialog.openError(getActiveShell(), TaxEstimateLabels.TITLE, e.getMessage());
        }
    }

    private void reconcile()
    {
        if (!(viewer.getStructuredSelection().getFirstElement() instanceof Row row))
            return;
        try
        {
            if (new TaxPaymentDialog(getActiveShell(), getClient(), row, new CurrencyConverterImpl(factory, "EUR")).open() == Window.OK)
            {
                markDirty();
                notifyModelUpdated();
            }
        }
        catch (RuntimeException e)
        {
            MessageDialog.openError(getActiveShell(), TaxEstimateLabels.TITLE, e.getMessage());
        }
    }

    private void showLedger()
    {
        if (!(viewer.getStructuredSelection().getFirstElement() instanceof Row row) || row.basis() == null)
            return;
        try
        {
            var ledger = new TaxPositionLedger(getClient(), row.portfolio(), row.security(), row.basis(), TaxPaymentLinks.load(getClient()),
                            new CurrencyConverterImpl(factory, "EUR"), LocalDate.now());
            String details = ledger.events().stream().map(e -> e.date() + " · " + e.kind() + " · assiette " + money(e.taxableGain())
                            + " EUR · impôt " + money(e.tax()) + " EUR").collect(Collectors.joining("\n"));
            MessageDialog.openInformation(getActiveShell(), "Journal fiscal — " + row.security().getName(),
                            details.isEmpty() ? "Aucun événement fiscal depuis la base validée." : details);
        }
        catch (RuntimeException e)
        {
            MessageDialog.openError(getActiveShell(), TaxEstimateLabels.TITLE, e.getMessage());
        }
    }

    @Override
    public void notifyModelUpdated()
    {
        if (viewer == null || viewer.getControl().isDisposed())
            return;
        var selected = viewer.getStructuredSelection().getFirstElement();
        try
        {
            var settings = TaxEnvelopeProfiles.effectiveSettings(getClient());
            var end = LocalDate.now();
            if (performanceStart == null)
                performanceStart = settings.positions().values().stream().map(b -> LocalDate.parse(b.validatedOn()))
                                .filter(d -> !d.isAfter(end)).min(LocalDate::compareTo).orElse(end);
            var converter = new CurrencyConverterImpl(factory, "EUR");
            var rows = PositionTaxEstimates.rows(getClient(), settings, converter, end);
            var data = new java.util.HashMap<String, PositionTaxPerformance.Result>();
            var refreshed = new java.util.ArrayList<Row>();
            var bases = new java.util.HashMap<String, Long>();
            var reserves = new java.util.HashMap<String, Long>();
            for (var row : rows)
            {
                var scope = TaxScope.security(getClient(), new PortfolioClientFilter(row.portfolio()), row.security());
                var result = TaxPerformance.calculate(getClient(), scope, converter, Interval.of(performanceStart, end));
                var initial = TaxPerformance.calculate(getClient(), scope, converter, Interval.of(performanceStart, performanceStart));
                Long change = result.latent() == null || initial.latent() == null ? null : result.latent() - initial.latent();
                Long charge = result.after().gain() == null ? null : result.events().stream().mapToLong(e -> e.tax()).sum();
                data.put(row.key(), new PositionTaxPerformance.Result(result.before().gain(), charge, change, result.after().gain(), result.detail()));
                if (row.basis() != null)
                {
                    try
                    {
                        var ledger = new TaxPositionLedger(getClient(), row.portfolio(), row.security(), row.basis(), TaxPaymentLinks.load(getClient()), converter, end);
                        var day = ledger.at(end);
                        bases.put(row.key(), day.basis());
                        reserves.put(row.key(), day.debt());
                        var estimate = row.marketValue() == null ? null : new PositionTaxEstimates.Estimate(row.marketValue(),
                                        row.marketValue() - day.basis(), day.latent(), row.marketValue() - day.latent());
                        row = new Row(row.portfolio(), row.security(), row.shares(), row.quoteDate(), row.marketValue(), row.basis(),
                                        estimate == null ? PositionTaxEstimates.Status.UNAVAILABLE : PositionTaxEstimates.Status.READY,
                                        estimate, "Base courante : " + money(day.basis()) + " EUR ; "
                                                        + (row.basis().profile() == TaxEstimateSettings.Profile.PEA ? "réserve PEA virtuelle" : row.basis().profile() == TaxEstimateSettings.Profile.LIFE_INSURANCE ? "réserve AV virtuelle" : "impôt dû estimé")
                                                        + " : " + money(day.debt()) + " EUR. " + result.detail());
                    }
                    catch (RuntimeException e)
                    {
                        row = new Row(row.portfolio(), row.security(), row.shares(), row.quoteDate(), row.marketValue(), row.basis(),
                                        PositionTaxEstimates.Status.UNAVAILABLE, null, e.getMessage());
                    }
                }
                refreshed.add(row);
            }
            rows = List.copyOf(refreshed);
            performances = Map.copyOf(data);
            currentBases = Map.copyOf(bases);
            realizedReserves = Map.copyOf(reserves);
            period.setText("Performance du " + performanceStart + " au " + end
                            + " · Base historique validée, coût fiscal moyen des ventes, clôtures annuelles estimées. Détail au survol.");
            viewer.setInput(rows);
            if (selected instanceof Row previous)
                rows.stream().filter(r -> r.key().equals(previous.key())).findFirst()
                                .ifPresent(r -> viewer.setSelection(new StructuredSelection(r)));
            updateTotals(rows);
        }
        catch (RuntimeException e)
        {
            performances = Map.of();
            viewer.setInput(List.of());
            totals.setText("Fiscalité indisponible : " + e.getMessage());
        }
        totals.getParent().layout(true, true);
    }

    private void updateTotals(List<Row> rows)
    {
        long count = 0;
        long charges = 0;
        long savings = 0;
        long value = 0;
        long net = 0;
        for (var row : rows)
        {
            var estimate = row.estimate();
            if (estimate == null)
                continue;
            count++;
            charges = Math.addExact(charges, Math.max(0, estimate.contribution()));
            savings = Math.addExact(savings, -Math.min(0, estimate.contribution()));
            value = Math.addExact(value, estimate.marketValue());
            net = Math.addExact(net, estimate.netValue());
        }
        long held = rows.size();
        var complete = performances.values().stream().filter(PositionTaxPerformance.Result::isComplete).toList();
        long beforeTax = complete.stream().mapToLong(PositionTaxPerformance.Result::beforeTax).reduce(0, Math::addExact);
        long accruedTax = complete.stream().mapToLong(PositionTaxPerformance.Result::accruedTax).reduce(0, Math::addExact);
        long latentChange = complete.stream().mapToLong(PositionTaxPerformance.Result::latentTaxChange).reduce(0, Math::addExact);
        long afterTax = complete.stream().mapToLong(PositionTaxPerformance.Result::afterTax).reduce(0, Math::addExact);
        totals.setText("Au " + LocalDate.now() + " · " + count + " / " + held + " positions calculées. "
                        + "Totaux limités à ces positions : valeur " + money(value) + " EUR ; valeur nette estimée " + money(net) + " EUR.\n"
                        + "Charges latentes : " + money(charges) + " EUR · Économies potentielles : " + money(savings)
                        + " EUR · Contribution signée : " + money(Math.subtractExact(charges, savings))
                        + " EUR. Somme indicative, sans compensation fiscale entre lignes.\n"
                        + "Performance sur " + complete.size() + " / " + held + " positions : avant impôts " + money(beforeTax)
                        + " EUR − fiscalité acquise " + money(accruedTax) + " EUR − variation latente " + money(latentChange)
                        + " EUR = net estimé " + money(afterTax) + " EUR.");
    }
}
