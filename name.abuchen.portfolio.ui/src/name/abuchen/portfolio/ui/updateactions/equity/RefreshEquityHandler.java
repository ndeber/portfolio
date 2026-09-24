package name.abuchen.portfolio.ui.updateactions.equity;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.time.LocalDate;
import java.util.ArrayList;
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
import name.abuchen.portfolio.ui.PortfolioPlugin;
import name.abuchen.portfolio.ui.editor.ClientInput;
import name.abuchen.portfolio.ui.editor.ClientInputListener;
import name.abuchen.portfolio.ui.handlers.MenuHelper;
import name.abuchen.portfolio.updates.equity.EquityAdjustment;
import name.abuchen.portfolio.updates.equity.EquityComposition.Outcome;
import name.abuchen.portfolio.updates.equity.EquitySources;

public final class RefreshEquityHandler
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
            for (var family : name.abuchen.portfolio.updates.equity.EquityComposition.Family.values())
                EquityAdjustment.taxonomy(snapshot, family);
            var scope = EquityAdjustment.scope(snapshot, LocalDate.now());
            if (scope.isEmpty())
            {
                MessageDialog.openInformation(shell, "Actualiser les taxonomies Actions", "Aucun titre Actions détenu à actualiser.");
                return;
            }
            var prepared = new AtomicReference<EquityAdjustment.Plan>();
            new ProgressMonitorDialog(shell).run(true, true, monitor -> {
                monitor.beginTask("Compositions Actions : régions, secteurs et transparence…", scope.size());
                try
                {
                    var sources = new EquitySources();
                    var outcomes = new ArrayList<Outcome>();
                    for (var security : scope)
                    {
                        if (monitor.isCanceled()) throw new InterruptedException();
                        monitor.subTask(security.getName());
                        outcomes.addAll(sources.fetch(security, monitor::isCanceled,
                                        family -> monitor.subTask(security.getName() + " — " + family.title())));
                        monitor.worked(1);
                    }
                    if (monitor.isCanceled()) throw new InterruptedException();
                    prepared.set(EquityAdjustment.prepare(snapshot, outcomes));
                }
                catch (RuntimeException e) { throw new InvocationTargetException(e); }
                finally { monitor.done(); }
            });
            var plan = prepared.get();
            if (changed.get()) throw new IOException("Le portefeuille a changé. Relancez l'actualisation pour inclure ces changements.");
            if (new EquityPreviewDialog(shell, plan, scope.size()).open() != Window.OK || plan.isEmpty())
                return;
            if (changed.get()) throw new IOException("Le portefeuille a changé depuis l'aperçu. Relancez l'actualisation.");
            EquityAdjustment.apply(input.getClient(), plan);
        }
        catch (InterruptedException e) { /* Cancellation never mutates the live portfolio. */ }
        catch (InvocationTargetException e) { error(shell, e.getCause()); }
        catch (IOException | RuntimeException e) { error(shell, e); }
        finally { input.removeListener(listener); }
    }

    private static void error(Shell shell, Throwable error)
    {
        PortfolioPlugin.log(error);
        MessageDialog.openError(shell, "Actualiser les taxonomies Actions", error.getMessage() == null ? error.toString() : error.getMessage());
    }
}
