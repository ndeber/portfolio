package name.abuchen.portfolio.ui.updateactions.inflation;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.time.LocalDate;
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
import name.abuchen.portfolio.updates.inflation.AftInflation;

public final class UpdateInflationHandler
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
            var security = AftInflation.security(snapshot);
            var result = new java.util.concurrent.atomic.AtomicReference<AftInflation.Plan>();
            new ProgressMonitorDialog(shell).run(true, true, monitor -> {
                monitor.beginTask("Récupération des références quotidiennes IPCH auprès de l’AFT…", -1);
                try
                {
                    result.set(AftInflation.prepare(security, AftInflation.fetch(monitor::isCanceled, LocalDate.now())));
                    if (monitor.isCanceled()) throw new InterruptedException();
                }
                catch (IOException | RuntimeException e) { throw new InvocationTargetException(e); }
                finally { monitor.done(); }
            });
            requireUnchanged(changed);
            if (new InflationPreviewDialog(shell, result.get()).open() != Window.OK)
                return;
            requireUnchanged(changed);
            AftInflation.apply(input.getClient(), result.get());
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
            throw new IOException("Le portefeuille a changé pendant la préparation. Relancez la mise à jour IPCH.");
    }

    private static void error(Shell shell, Throwable error)
    {
        PortfolioPlugin.log(error);
        MessageDialog.openError(shell, "Inflation EU (IPCH)", error.getMessage() == null ? error.toString() : error.getMessage());
    }
}
