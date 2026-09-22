package name.abuchen.portfolio.ui.updateactions.elm;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.inject.Named;

import org.eclipse.e4.core.di.annotations.CanExecute;
import org.eclipse.e4.core.di.annotations.Execute;
import org.eclipse.e4.core.di.annotations.Optional;
import org.eclipse.e4.ui.model.application.ui.basic.MPart;
import org.eclipse.e4.ui.services.IServiceConstants;
import org.eclipse.e4.ui.workbench.modeling.EPartService;
import org.eclipse.e4.ui.workbench.modeling.EPartService.PartState;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.dialogs.ProgressMonitorDialog;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Shell;

import name.abuchen.portfolio.model.ClientFactory;
import name.abuchen.portfolio.ui.PortfolioPlugin;
import name.abuchen.portfolio.ui.UIConstants;
import name.abuchen.portfolio.ui.dialogs.PasswordDialog;
import name.abuchen.portfolio.ui.editor.ClientInput;
import name.abuchen.portfolio.ui.editor.ClientInputFactory;
import name.abuchen.portfolio.ui.editor.ClientInputListener;
import name.abuchen.portfolio.ui.handlers.MenuHelper;
import name.abuchen.portfolio.ui.updateactions.UpdatePreviewDialog;
import name.abuchen.portfolio.updates.PortfolioUpdateCopy;
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
                    @Named(IServiceConstants.ACTIVE_SHELL) Shell shell, EPartService partService, ClientInputFactory inputFactory)
    {
        MenuHelper.getActiveClientInput(part, true).ifPresent(input -> run(input, part, shell, partService, inputFactory));
    }

    private void run(ClientInput input, MPart part, Shell shell, EPartService partService, ClientInputFactory inputFactory)
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
        char[] password = null;
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
                            + age + "\nPoids du titre affecté à chaque catégorie ; vos objectifs restent inchangés.";
            if (new UpdatePreviewDialog(shell, "Ajuster ELM — aperçu", summary, rows).open() != Window.OK)
                return;
            var source = input.getFile();
            String extension = extension(source);
            var dialog = new FileDialog(shell, SWT.SAVE);
            dialog.setText("Enregistrer une nouvelle copie ajustée ELM");
            dialog.setFilterExtensions(new String[] { "*" + extension });
            dialog.setOverwrite(false);
            if (source != null)
                dialog.setFilterPath(source.getParent());
            String base = source == null ? "Portefeuille" : source.getName().replaceFirst("\\.[^.]+$", "");
            dialog.setFileName(base + "-ELM-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + extension);
            String selected = dialog.open();
            if (selected == null)
                return;
            if (!selected.toLowerCase(Locale.ROOT).endsWith(extension))
                throw new IOException("Conservez l'extension " + extension + " pour la copie.");
            if (source != null && ClientFactory.isEncrypted(source))
            {
                var passwordDialog = new PasswordDialog(shell);
                if (passwordDialog.open() != Window.OK)
                    return;
                password = passwordDialog.getPassword().toCharArray();
            }
            requireUnchanged(changed);
            ElmAdjustment.apply(snapshot, plan);
            var verified = PortfolioUpdateCopy.save(snapshot, Path.of(selected), password);
            // Loading a distinct file also keeps the source editor and its unsaved state.
            var copy = partService.createPart(UIConstants.Part.PORTFOLIO);
            copy.setLabel(new File(selected).getName());
            copy.setTooltip(selected);
            copy.getPersistedState().put(UIConstants.PersistedState.FILENAME, selected);
            copy.getTransientData().put(ClientInput.class.getName(), inputFactory.openVerifiedCopy(new File(selected), verified));
            part.getParent().getChildren().add(copy);
            copy.setVisible(true);
            partService.showPart(copy, PartState.ACTIVATE);
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
            if (password != null)
                Arrays.fill(password, '\0');
            input.removeListener(listener);
        }
    }

    private static void requireUnchanged(AtomicBoolean changed) throws IOException
    {
        if (changed.get())
            throw new IOException("Le portefeuille a changé pendant la préparation. Relancez Ajuster ELM pour inclure ces changements.");
    }

    private static String extension(File source) throws IOException
    {
        if (source == null)
            return ".portfolio";
        var name = source.getName().toLowerCase(Locale.ROOT);
        for (var extension : new String[] { ".portfolio", ".xml", ".zip" })
            if (name.endsWith(extension))
                return extension;
        throw new IOException("Format du portefeuille non pris en charge pour cette copie.");
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
