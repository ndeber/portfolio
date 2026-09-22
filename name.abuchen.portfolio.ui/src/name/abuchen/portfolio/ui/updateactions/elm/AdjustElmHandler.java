package name.abuchen.portfolio.ui.updateactions.elm;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.time.LocalDate;
import java.util.Locale;
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
import name.abuchen.portfolio.ui.updateactions.UpdatePreviewDialog;
import name.abuchen.portfolio.updates.elm.ElmAdjustment;
import name.abuchen.portfolio.updates.elm.ElmAllocation;

public final class AdjustElmHandler
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
            // Capture unsaved edits as well. Network work never mutates the live model.
            var snapshot = ClientFactory.duplicate(input.getClient());
            var configuration = new ElmConfigurationDialog(shell, snapshot);
            if (configuration.open() != Window.OK)
                return;
            var allocation = new AtomicReference<ElmAllocation>();
            new ProgressMonitorDialog(shell).run(true, true, monitor -> {
                monitor.beginTask("Lecture de la répartition publiée par ELM…", -1);
                try
                {
                    if (monitor.isCanceled())
                        throw new InterruptedException();
                    var fetched = ElmAllocation.fetch();
                    if (monitor.isCanceled())
                        throw new InterruptedException();
                    allocation.set(fetched);
                }
                catch (IOException e)
                {
                    throw new InvocationTargetException(e);
                }
                finally
                {
                    monitor.done();
                }
            });
            requireUnchanged(changed);
            var data = allocation.get();
            var plan = ElmAdjustment.prepare(snapshot, configuration.getOptions(), data);
            if (plan.changes().isEmpty())
            {
                MessageDialog.openInformation(shell, "Ajuster ELM", "Les affectations sélectionnées sont déjà à jour.\n"
                                + "Allocation publiée le " + data.date() + " : monétaire " + percent(data.cash())
                                + ", obligations " + percent(data.bonds()) + ", actions " + percent(data.equities()) + ".");
                return;
            }
            var rows = plan.changes().stream().map(c -> new UpdatePreviewDialog.Change(c.location(), percent(c.before()), percent(c.after()))).toList();
            var age = data.date().isBefore(LocalDate.now().minusDays(45)) ? "\nAttention : publication de plus de 45 jours." : "";
            String summary = ElmAdjustment.security(snapshot, plan.options().securityId()).getName()
                            + " — allocation cible publiée le " + data.date() + "\n" + ElmAllocation.SOURCE
                            + age + "\nPoids du titre affecté à chaque catégorie ; vos objectifs restent inchangés.\nLa validation modifie le portefeuille actuellement ouvert.";
            if (new UpdatePreviewDialog(shell, "Ajuster ELM — aperçu", summary, rows, "Appliquer au portefeuille ouvert").open() != Window.OK)
                return;
            requireUnchanged(changed);
            ElmAdjustment.apply(input.getClient(), plan);
        }
        catch (InterruptedException e)
        {
            // User cancellation: no plan is applied and no file is written.
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
            throw new IOException("Le portefeuille a changé pendant la préparation. Relancez Ajuster ELM pour inclure ces changements.");
    }

    private static String percent(int weight)
    {
        return String.format(Locale.FRANCE, "%.2f %%", weight / 100.0);
    }

    private static void error(Shell shell, Throwable error)
    {
        PortfolioPlugin.log(error);
        MessageDialog.openError(shell, "Ajuster ELM", error.getMessage() == null ? error.toString() : error.getMessage());
    }
}
