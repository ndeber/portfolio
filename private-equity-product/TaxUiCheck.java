package name.abuchen.portfolio.ui.views;
import java.time.*;
import java.nio.file.*;
import org.eclipse.swt.*;
import org.eclipse.swt.widgets.*;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.graphics.*;
import org.eclipse.e4.core.contexts.*;
import org.eclipse.e4.ui.services.IStylingEngine;
import org.eclipse.jface.preference.IPreferenceStore;
import name.abuchen.portfolio.model.*;
import name.abuchen.portfolio.money.*;
import name.abuchen.portfolio.snapshot.*;
import name.abuchen.portfolio.ui.editor.*;
import name.abuchen.portfolio.ui.selection.*;
public class TaxUiCheck implements org.eclipse.equinox.app.IApplication {
 static void set(Object o,Class<?> c,String n,Object v)throws Exception{var f=c.getDeclaredField(n);f.setAccessible(true);f.set(o,v);}
 static Object get(Object o,Class<?> c,String n)throws Exception{var f=c.getDeclaredField(n);f.setAccessible(true);return f.get(o);}
 static void capture(Shell shell,String name)throws Exception{
  shell.layout(true,true);shell.update();var d=shell.getDisplay();long until=System.nanoTime()+300_000_000L;
  while(System.nanoTime()<until){if(!d.readAndDispatch())Thread.sleep(5);}
  var r=shell.getClientArea();var image=new Image(d,r.width,r.height);var gc=new GC(shell);gc.copyArea(image,0,0);gc.dispose();
  var loader=new ImageLoader();loader.data=new ImageData[]{image.getImageData()};loader.save(System.getProperty("probe.output")+"/"+name+".png",SWT.IMAGE_PNG);image.dispose();
 }
 public Object start(org.eclipse.equinox.app.IApplicationContext app)throws Exception{
  var display=new Display();
  try{
   var client=ClientFactory.load(java.nio.file.Path.of(System.getProperty("probe.portfolio")).toFile(),null,new org.eclipse.core.runtime.NullProgressMonitor());
   String before=client.getProperty(name.abuchen.portfolio.tax.TaxEstimateSettings.PROPERTY);
   String linksBefore=client.getProperty(name.abuchen.portfolio.tax.TaxPaymentLinks.PROPERTY);
   var ctor=ClientInput.class.getDeclaredConstructor(String.class,java.io.File.class);ctor.setAccessible(true);var input=ctor.newInstance("Fiscalité V15",null);set(input,ClientInput.class,"client",client);
   var part=new PortfolioPart();set(part,PortfolioPart.class,"clientInput",input);set(part,PortfolioPart.class,"selectedPeriod",new ReportingPeriod.FromXtoY(LocalDate.of(2025,10,6),LocalDate.now()));
   var context=EclipseContextFactory.getServiceContext(org.osgi.framework.FrameworkUtil.getBundle(TaxUiCheck.class).getBundleContext()).createChild("tax-test");
   var styling=(IStylingEngine)java.lang.reflect.Proxy.newProxyInstance(IStylingEngine.class.getClassLoader(),new Class[]{IStylingEngine.class},(p,m,a)->null);
   var factory=new ExchangeRateProviderFactory(client);set(input,ClientInput.class,"exchangeRateProviderFacory",factory);
   context.set(Client.class,client);context.set(name.abuchen.portfolio.ui.UIConstants.Context.ACTIVE_CLIENT,client);context.set(PortfolioPart.class,part);context.set(IPreferenceStore.class,input.getPreferenceStore());context.set(IStylingEngine.class,styling);context.set(ExchangeRateProviderFactory.class,factory);context.set(SelectionService.class,new SelectionService());
   var view=ContextInjectionFactory.make(StatementOfAssetsView.class,context);context.set(AbstractFinanceView.class,view);
   var shell=new Shell(display);shell.setText("Actifs — fiscalité V15");shell.setSize(1450,700);shell.setLayout(new FillLayout());
   view.createViewControl(shell,true);shell.open();capture(shell,"actifs-apres-impots");
   var viewer=(StatementOfAssetsViewer)get(view,StatementOfAssetsView.class,"assetViewer");
   var table=viewer.getTableViewer().getTable();
   if(table.getColumnCount()!=7)throw new AssertionError("Expected 7 configured columns, got "+table.getColumnCount());
   for(var item:table.getItems()){StringBuilder text=new StringBuilder();for(int i=0;i<table.getColumnCount();i++)text.append(item.getText(i)).append(" | ");System.out.println("NATIVE_TAX_ROW "+text);}
   var model=(StatementOfAssetsViewer.Model)get(viewer,StatementOfAssetsViewer.class,"model");
   var total=model.getElements().stream().filter(StatementOfAssetsViewer.Element::isGroupByTaxonomy).findFirst().orElseThrow();
   var expected=model.getTaxValues().performance(total,name.abuchen.portfolio.util.Interval.of(LocalDate.of(2025,10,6),LocalDate.now()));
   if(!Long.valueOf(131250).equals(expected.after().gain()))throw new AssertionError(expected);
   System.out.println("TAX_ASSETS_UI_PASS: five net metrics in native table");
   var dashShell=new Shell(display);dashShell.setText("Portefeuille investissable — après impôts");dashShell.setSize(1050,550);dashShell.setLayout(new GridLayout(3,true));
   var data=new name.abuchen.portfolio.ui.views.dashboard.DashboardData(client,input.getPreferenceStore(),styling,factory,new name.abuchen.portfolio.ui.views.dataseries.DataSeriesCache(client,factory));
   data.setDashboard(client.getDashboards().findFirst().orElseThrow());data.setDefaultReportingPeriod(new ReportingPeriod.FromXtoY(LocalDate.of(2025,10,6),LocalDate.now()));
   var resources=new name.abuchen.portfolio.ui.views.dashboard.DashboardResources(dashShell);
   var delegates=new java.util.ArrayList<name.abuchen.portfolio.ui.views.dashboard.AfterTaxWidget>();
   for(var column:data.getDashboard().getColumns()){
    var panel=new Composite(dashShell,SWT.NONE);panel.setLayout(new GridLayout());panel.setLayoutData(new GridData(SWT.FILL,SWT.FILL,true,true));
    for(var widget:column.getWidgets()){
     var delegate=(name.abuchen.portfolio.ui.views.dashboard.AfterTaxWidget)name.abuchen.portfolio.ui.views.dashboard.WidgetFactory.valueOf(widget.getType()).constructDelegate(widget,data);
     ContextInjectionFactory.inject(delegate,context);
     delegates.add(delegate);
     var control=delegate.createControl(panel,resources);control.setLayoutData(new GridData(SWT.FILL,SWT.FILL,true,true));
     var result=delegate.getUpdateTask().get();delegate.update(result);
     if(!expected.after().equals(result.after()))throw new AssertionError("Widget/table mismatch "+result);
    }
   }
   dashShell.open();capture(dashShell,"widgets-apres-impots");
   System.out.println("TAX_WIDGETS_UI_PASS: five rendered widgets agree with native total");
   var fiscalView=ContextInjectionFactory.make(name.abuchen.portfolio.ui.tax.TaxEstimatesView.class,context);context.set(AbstractFinanceView.class,fiscalView);
   var fiscalShell=new Shell(display);fiscalShell.setText("Fiscalité estimée");fiscalShell.setSize(1450,650);fiscalShell.setLayout(new FillLayout());
   fiscalView.createViewControl(fiscalShell,true);fiscalShell.open();capture(fiscalShell,"suivi-fiscal");
   var converter=new CurrencyConverterImpl(factory,"EUR");
   var row=name.abuchen.portfolio.tax.PositionTaxEstimates.rows(client,name.abuchen.portfolio.tax.TaxEstimateSettings.load(client),converter,LocalDate.now()).getFirst();
   var settings=new name.abuchen.portfolio.ui.tax.TaxEstimateDialog(shell,client,row);settings.create();settings.getShell().open();capture(settings.getShell(),"parametres-fiscaux");settings.close();
   var payment=new name.abuchen.portfolio.ui.tax.TaxPaymentDialog(shell,client,row,converter);payment.create();payment.getShell().open();capture(payment.getShell(),"rapprochement-fiscal");payment.close();
   if(!before.equals(client.getProperty(name.abuchen.portfolio.tax.TaxEstimateSettings.PROPERTY)))throw new AssertionError("Cancelled dialog changed fiscal basis");
   if(!linksBefore.equals(client.getProperty(name.abuchen.portfolio.tax.TaxPaymentLinks.PROPERTY)))throw new AssertionError("Cancelled dialog changed tax reconciliation");
   System.out.println("TAX_DIALOGS_UI_PASS: historical basis, payment reconciliation, cancel preserves settings");
   var action=client.getSecurities().stream().filter(s->s.getName().startsWith("Action A")).findFirst().orElseThrow();
   action.addPrice(new SecurityPrice(LocalDate.now(),130L*Values.Quote.factor()));
   view.notifyModelUpdated();data.clearCache();
   var changedModel=(StatementOfAssetsViewer.Model)get(viewer,StatementOfAssetsViewer.class,"model");
   var changedTotal=changedModel.getElements().stream().filter(StatementOfAssetsViewer.Element::isGroupByTaxonomy).findFirst().orElseThrow();
   var changed=changedModel.getTaxValues().performance(changedTotal,name.abuchen.portfolio.util.Interval.of(LocalDate.of(2025,10,6),LocalDate.now()));
   if(!Long.valueOf(168750).equals(changed.after().gain()))throw new AssertionError("Quote refresh was not reflected: "+changed);
   for(var delegate:delegates){var result=delegate.getUpdateTask().get();delegate.update(result);if(!changed.after().equals(result.after()))throw new AssertionError("Stale widget after quote update");}
   System.out.println("TAX_REFRESH_UI_PASS: changing A from 120 to 130 increases net gain by 375 EUR in table and all widgets");
   fiscalShell.dispose();dashShell.dispose();shell.dispose();context.dispose();
  }finally{display.dispose();}
  return EXIT_OK;
 }
 public void stop(){}
}
