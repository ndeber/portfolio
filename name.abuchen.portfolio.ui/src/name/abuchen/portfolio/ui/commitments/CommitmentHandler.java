package name.abuchen.portfolio.ui.commitments;

import jakarta.inject.Named;
import org.eclipse.e4.core.di.annotations.*;
import org.eclipse.e4.ui.model.application.ui.basic.MPart;
import org.eclipse.e4.ui.services.IServiceConstants;
import org.eclipse.swt.widgets.Shell;
import name.abuchen.portfolio.money.CurrencyConverterImpl;
import name.abuchen.portfolio.ui.handlers.MenuHelper;

public final class CommitmentHandler
{
    @CanExecute public boolean canExecute(@Optional @Named(IServiceConstants.ACTIVE_PART) MPart part)
    { return MenuHelper.getActiveClientInput(part, false).isPresent(); }
    @Execute public void execute(@Optional @Named(IServiceConstants.ACTIVE_PART) MPart part, @Named(IServiceConstants.ACTIVE_SHELL) Shell shell)
    {
        MenuHelper.getActiveClientInput(part, true).ifPresent(input -> new CommitmentDialog(shell, input.getClient(),
                        new CurrencyConverterImpl(input.getExchangeRateProviderFacory(), "EUR")).open());
    }
}
