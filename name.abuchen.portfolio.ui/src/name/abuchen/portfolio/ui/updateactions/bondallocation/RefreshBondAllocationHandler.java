package name.abuchen.portfolio.ui.updateactions.bondallocation;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.inject.Named;
import org.eclipse.e4.core.di.annotations.CanExecute;
import org.eclipse.e4.core.di.annotations.Execute;
import org.eclipse.e4.core.di.annotations.Optional;
import org.eclipse.e4.ui.model.application.ui.basic.MPart;
import org.eclipse.e4.ui.services.IServiceConstants;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.dialogs.ProgressMonitorDialog;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.widgets.Shell;

import name.abuchen.portfolio.model.ClientFactory;
import name.abuchen.portfolio.money.CurrencyConverterImpl;
import name.abuchen.portfolio.snapshot.ClientSnapshot;
import name.abuchen.portfolio.updates.equity.EquityTaxonomyOrder;
import name.abuchen.portfolio.ui.PortfolioPlugin;
import name.abuchen.portfolio.ui.editor.ClientInput;
import name.abuchen.portfolio.ui.editor.ClientInputListener;
import name.abuchen.portfolio.ui.handlers.MenuHelper;
import name.abuchen.portfolio.updates.bondallocation.BondAllocationAdjustment;
import name.abuchen.portfolio.updates.bondallocation.BondComposition.Outcome;
import name.abuchen.portfolio.updates.bondallocation.BondAllocationSources;

public final class RefreshBondAllocationHandler
{
    @CanExecute
    public boolean canExecute(@Optional @Named(IServiceConstants.ACTIVE_PART) MPart part)
    {
        return MenuHelper.getActiveClientInput(part, false).isPresent();
    }

    @Execute
    public void execute(@Optional @Named(IServiceConstants.ACTIVE_PART) MPart part,
                    @Named(IServiceConstants.ACTIVE_SHELL) Shell shell)
    {
        MenuHelper.getActiveClientInput(part, true).ifPresent(input -> run(input, shell));
    }

    public static void run(ClientInput input, Shell shell)
    {
        var changed = new AtomicBoolean();
        var listener = new ClientInputListener()
        {
            @Override public void onDirty(boolean dirty) { changed.set(true); }
            @Override public void onRecalculationNeeded() { changed.set(true); }
            @Override public void onSaved() { changed.set(true); }
            @Override public void onDisposed() { changed.set(true); }
        };
        input.addListener(listener);
        try
        {
            var snapshot = ClientFactory.duplicate(input.getClient());
            for (var family : name.abuchen.portfolio.updates.bondallocation.BondComposition.Family.values())
                BondAllocationAdjustment.taxonomy(snapshot, family);
            var scope = BondAllocationAdjustment.scope(snapshot, LocalDate.now());
            if (scope.isEmpty())
            {
                MessageDialog.openInformation(shell, "Actualiser les taxonomies Obligations", "Aucun titre Obligations détenu à actualiser.");
                return;
            }
            var prepared = new AtomicReference<BondAllocationAdjustment.Plan>();
            var valuations = new AtomicReference<Map<String, Long>>();
            var converter = new CurrencyConverterImpl(input.getExchangeRateProviderFacory(), snapshot.getBaseCurrency());
            new ProgressMonitorDialog(shell).run(true, true, monitor -> {
                monitor.beginTask("Compositions Obligations : notation, échéance, régions et émetteurs…", scope.size());
                try
                {
                    var sources = new BondAllocationSources();
                    var outcomes = new ArrayList<Outcome>();
                    for (var security : scope)
                    {
                        if (monitor.isCanceled()) throw new InterruptedException();
                        monitor.subTask(security.getName());
                        outcomes.addAll(sources.fetch(security, LocalDate.now(), monitor::isCanceled,
                                        family -> monitor.subTask(security.getName() + " — " + family.title())));
                        monitor.worked(1);
                    }
                    if (monitor.isCanceled()) throw new InterruptedException();
                    valuations.set(ClientSnapshot.create(snapshot, converter, LocalDate.now()).getAssetPositions()
                                    .collect(Collectors.toMap(p -> p.getInvestmentVehicle().getUUID(), p -> p.getValuation().getAmount())));
                    if (monitor.isCanceled()) throw new InterruptedException();
                    prepared.set(BondAllocationAdjustment.prepare(snapshot, outcomes));
                }
                catch (RuntimeException e) { throw new InvocationTargetException(e); }
                finally { monitor.done(); }
            });
            var plan = prepared.get();
            if (changed.get()) throw new IOException("Le portefeuille a changé. Relancez l'actualisation pour inclure ces changements.");
            if (new BondAllocationPreviewDialog(shell, plan, scope.size()).open() != Window.OK)
                return;
            if (changed.get()) throw new IOException("Le portefeuille a changé depuis l'aperçu. Relancez l'actualisation.");
            BondAllocationAdjustment.apply(input.getClient(), plan);
            EquityTaxonomyOrder.apply(input.getClient(), java.util.Arrays.stream(name.abuchen.portfolio.updates.bondallocation.BondComposition.Family.values())
                            .map(f -> BondAllocationAdjustment.taxonomy(input.getClient(), f)).toList(), valuations.get());
        }
        catch (InterruptedException e) { /* Cancellation never mutates the live portfolio. */ }
        catch (InvocationTargetException e) { error(shell, e.getCause()); }
        catch (IOException | RuntimeException e) { error(shell, e); }
        finally { input.removeListener(listener); }
    }

    private static void error(Shell shell, Throwable error)
    {
        PortfolioPlugin.log(error);
        MessageDialog.openError(shell, "Actualiser les taxonomies Obligations", error.getMessage() == null ? error.toString() : error.getMessage());
    }
}
