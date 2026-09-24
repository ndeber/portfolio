package name.abuchen.portfolio.ui.updateactions.bonds;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

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
import name.abuchen.portfolio.updates.bonds.BondSources;
import name.abuchen.portfolio.updates.bonds.BondSpec;
import name.abuchen.portfolio.updates.bonds.BondUpdate;

public final class UpdateBondsHandler
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

    private void run(ClientInput input, Shell shell)
    {
        var changed = new AtomicBoolean();
        var listener = new ClientInputListener()
        {
            @Override
            public void onDirty(boolean dirty)
            {
                changed.set(true);
            }

            @Override
            public void onRecalculationNeeded()
            {
                changed.set(true);
            }

            @Override
            public void onSaved()
            {
                changed.set(true);
            }

            @Override
            public void onDisposed()
            {
                changed.set(true);
            }
        };
        input.addListener(listener);
        try
        {
            var snapshot = ClientFactory.duplicate(input.getClient());
            var securities = snapshot.getSecurities().stream().filter(s -> BondSpec.find(s.getIsin()).isPresent()
                            && "EUR".equals(s.getCurrencyCode())).toList();
            if (securities.isEmpty())
            {
                MessageDialog.openInformation(shell, "Obligations", "Aucune obligation prise en charge en EUR dans ce portefeuille.\n"
                                + "ISIN actuellement pris en charge :\n" + String.join(", ", BondSpec.SUPPORTED.stream().map(BondSpec::isin).toList()));
                return;
            }
            var configuration = new BondConfigurationDialog(shell, securities);
            if (configuration.open() != Window.OK)
                return;
            var plans = new ArrayList<BondUpdate.Plan>();
            new ProgressMonitorDialog(shell).run(true, true, monitor -> {
                monitor.beginTask("Récupération des cours obligataires…", configuration.selected().size());
                var sources = new BondSources(monitor::isCanceled, LocalDate.now());
                try
                {
                    for (var id : configuration.selected())
                    {
                        if (monitor.isCanceled())
                            throw new InterruptedException();
                        var security = BondUpdate.security(snapshot, id);
                        monitor.subTask(security.getName() + " — " + security.getIsin());
                        try
                        {
                            var quotes = sources.fetch(BondSpec.find(security.getIsin()).orElseThrow());
                            plans.add(BondUpdate.prepare(security, quotes, configuration.allowMigration()));
                        }
                        catch (IOException | RuntimeException e)
                        {
                            throw new InvocationTargetException(new IOException(security.getName() + " : " + e.getMessage()
                                            + "\nAucune obligation n'a été modifiée. Vous pouvez réessayer en décochant ce titre.", e));
                        }
                        monitor.worked(1);
                    }
                    if (monitor.isCanceled())
                        throw new InterruptedException();
                }
                finally
                {
                    monitor.done();
                }
            });
            requireUnchanged(changed);
            if (new BondPreviewDialog(shell, snapshot, plans).open() != Window.OK)
                return;
            requireUnchanged(changed);
            BondUpdate.apply(input.getClient(), plans);
        }
        catch (InterruptedException e)
        {
            // Cancellation leaves the current model and file untouched.
        }
        catch (InvocationTargetException e)
        {
            error(shell, e.getCause());
        }
        catch (IOException | RuntimeException e)
        {
            error(shell, e);
        }
        finally
        {
            input.removeListener(listener);
        }
    }

    private static void requireUnchanged(AtomicBoolean changed) throws IOException
    {
        if (changed.get())
            throw new IOException("Le portefeuille a changé pendant la préparation. Relancez la mise à jour des obligations.");
    }

    private static void error(Shell shell, Throwable error)
    {
        PortfolioPlugin.log(error);
        MessageDialog.openError(shell, "Obligations", error.getMessage() == null ? error.toString() : error.getMessage());
    }
}
