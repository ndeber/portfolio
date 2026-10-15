package name.abuchen.portfolio.ui.factors;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import jakarta.inject.Named;
import org.eclipse.e4.core.di.annotations.*;
import org.eclipse.e4.ui.model.application.ui.basic.MPart;
import org.eclipse.e4.ui.services.IServiceConstants;
import org.eclipse.jface.dialogs.*;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.widgets.Shell;
import name.abuchen.portfolio.model.ClientFactory;
import name.abuchen.portfolio.factors.*;
import name.abuchen.portfolio.ui.PortfolioPlugin;
import name.abuchen.portfolio.ui.editor.*;
import name.abuchen.portfolio.ui.handlers.MenuHelper;

@SuppressWarnings("nls")
public final class RefreshCurrencyHandler
{
    @CanExecute public boolean canExecute(@Optional @Named(IServiceConstants.ACTIVE_PART) MPart part)
    {return MenuHelper.getActiveClientInput(part,false).isPresent();}
    @Execute public void execute(@Optional @Named(IServiceConstants.ACTIVE_PART) MPart part,
                    @Named(IServiceConstants.ACTIVE_SHELL) Shell shell)
    {MenuHelper.getActiveClientInput(part,true).ifPresent(input->run(input,shell));}

    public static void run(ClientInput input, Shell shell)
    {
        var changed=new AtomicBoolean();
        var listener=new ClientInputListener(){
            @Override public void onDirty(boolean dirty){changed.set(true);}
            @Override public void onRecalculationNeeded(){changed.set(true);}
            @Override public void onSaved(){changed.set(true);}
            @Override public void onDisposed(){changed.set(true);}
        };
        input.addListener(listener);
        try
        {
            var snapshot=ClientFactory.duplicate(input.getClient());
            var today=LocalDate.now();var scope=CurrencyProposals.scope(snapshot,today);
            var result=new AtomicReference<CurrencyProposals.Plan>();
            new ProgressMonitorDialog(shell).run(true,true,monitor->{
                monitor.beginTask("Propositions devises : fonds détenus et ELM…",scope.size());
                try
                {
                    var sources=new CurrencySources();var evidence=new LinkedHashMap<String,CurrencyProposals.Evidence>();
                    for(var security:scope)
                    {
                        if(monitor.isCanceled())throw new InterruptedException();
                        monitor.subTask(security.getName());
                        evidence.put(security.getUUID(),sources.fetch(snapshot,security,today,monitor::isCanceled));
                        monitor.worked(1);
                    }
                    if(monitor.isCanceled())throw new InterruptedException();
                    result.set(CurrencyProposals.prepare(snapshot,today,evidence));
                }
                catch(RuntimeException e){throw new InvocationTargetException(e);}
                finally{monitor.done();}
            });
            if(changed.get())throw new IOException("Le portefeuille a changé. Relancer la proposition.");
            var dialog=new CurrencyPreviewDialog(shell,result.get());
            if(dialog.open()!=Window.OK)return;
            if(changed.get())throw new IOException("Le portefeuille a changé depuis l'aperçu. Relancer la proposition.");
            CurrencyProposals.apply(input.getClient(),result.get(),dialog.selected());
        }
        catch(InterruptedException e){ /* Cancellation leaves the portfolio untouched. */ }
        catch(InvocationTargetException e){error(shell,e.getCause());}
        catch(IOException|RuntimeException e){error(shell,e);}
        finally{input.removeListener(listener);}
    }
    private static void error(Shell shell,Throwable error)
    {PortfolioPlugin.log(error);MessageDialog.openError(shell,"Propositions devises",error.getMessage()==null?error.toString():error.getMessage());}
}
