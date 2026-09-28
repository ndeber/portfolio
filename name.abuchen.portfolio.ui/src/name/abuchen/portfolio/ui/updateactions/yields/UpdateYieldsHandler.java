package name.abuchen.portfolio.ui.updateactions.yields;

import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.inject.Named;

import org.eclipse.e4.core.di.annotations.CanExecute;
import org.eclipse.e4.core.di.annotations.Execute;
import org.eclipse.e4.core.di.annotations.Optional;
import org.eclipse.e4.ui.model.application.ui.basic.MPart;
import org.eclipse.e4.ui.services.IServiceConstants;
import org.eclipse.swt.widgets.Shell;

import name.abuchen.portfolio.ui.editor.ClientInput;
import name.abuchen.portfolio.ui.editor.ClientInputListener;
import name.abuchen.portfolio.ui.handlers.MenuHelper;

public final class UpdateYieldsHandler
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
            var converter = new name.abuchen.portfolio.money.CurrencyConverterImpl(input.getExchangeRateProviderFacory(), "EUR");
            new YieldDialog(shell, input.getClient(), converter, changed::get).open();
        }

        catch (RuntimeException e)
        {
            org.eclipse.jface.dialogs.MessageDialog.openError(shell, "Rendements obligataires", e.getMessage() == null ? e.toString() : e.getMessage());
        }
        finally
        {
            input.removeListener(listener);
        }
    }

}
