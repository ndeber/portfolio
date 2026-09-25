package name.abuchen.portfolio.ui.commitments;

import jakarta.inject.Named;
import org.eclipse.e4.core.di.annotations.*;
import org.eclipse.e4.ui.model.application.ui.basic.MPart;
import org.eclipse.e4.ui.services.IServiceConstants;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Shell;
import name.abuchen.portfolio.money.CurrencyConverterImpl;
import name.abuchen.portfolio.ui.handlers.MenuHelper;

public final class AvailabilityHandler
{
    @CanExecute public boolean canExecute(@Optional @Named(IServiceConstants.ACTIVE_PART) MPart part)
    { return MenuHelper.getActiveClientInput(part, false).isPresent(); }
    @Execute public void execute(@Optional @Named(IServiceConstants.ACTIVE_PART) MPart part, @Named(IServiceConstants.ACTIVE_SHELL) Shell shell)
    {
        MenuHelper.getActiveClientInput(part, true).ifPresent(input -> {
            try { new AvailabilityDialog(shell, input.getClient(), new CurrencyConverterImpl(input.getExchangeRateProviderFacory(), "EUR")).open(); }
            catch (RuntimeException e) { MessageDialog.openError(shell, "Disponibilités Xapa", e.getMessage()); }
        });
    }
}
