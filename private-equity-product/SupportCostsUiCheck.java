package name.abuchen.portfolio.ui.views.dashboard;

import java.nio.file.*;
import java.nio.file.Path;
import java.time.LocalDate;
import org.eclipse.swt.*;
import org.eclipse.swt.widgets.*;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.graphics.*;
import org.eclipse.jface.preference.PreferenceStore;
import org.eclipse.e4.ui.services.IStylingEngine;
import name.abuchen.portfolio.model.*;
import name.abuchen.portfolio.money.*;
import name.abuchen.portfolio.snapshot.*;
import name.abuchen.portfolio.snapshot.filter.ClientFilter;
import name.abuchen.portfolio.supportcosts.SupportCosts;
import name.abuchen.portfolio.tax.TaxScope;
import name.abuchen.portfolio.ui.views.dataseries.DataSeriesCache;
import name.abuchen.portfolio.util.Interval;

public class SupportCostsUiCheck implements org.eclipse.equinox.app.IApplication {
 public Object start(org.eclipse.equinox.app.IApplicationContext app)throws Exception {
  var display=new Display();
  try {
   var theme=new name.abuchen.portfolio.ui.theme.CustomThemeEngineManager().getEngineForDisplay(display);
   theme.setTheme(name.abuchen.portfolio.ui.UIConstants.Theme.LIGHT,false);
   var css=org.eclipse.e4.ui.css.swt.dom.WidgetElement.getEngine(display);
   var referenceShell=new Shell(display);
   var reference=new name.abuchen.portfolio.ui.util.swt.ColoredLabel(referenceShell,SWT.NONE);
   reference.setText("1,45");reference.setData(name.abuchen.portfolio.ui.UIConstants.CSS.CLASS_NAME,name.abuchen.portfolio.ui.UIConstants.CSS.KPI);
   css.applyStyles(referenceShell,true);
   var client=ClientFactory.load(Path.of(System.getProperty("probe.portfolio")).toFile(),null,new org.eclipse.core.runtime.NullProgressMonitor());
   for(var p:ExchangeRateProviderFactory.getProviders())p.load(new org.eclipse.core.runtime.NullProgressMonitor());
   var factory=new ExchangeRateProviderFactory(client);var converter=new CurrencyConverterImpl(factory,"EUR");
   var prefs=new PreferenceStore();
   var styling=(IStylingEngine)java.lang.reflect.Proxy.newProxyInstance(IStylingEngine.class.getClassLoader(),new Class[]{IStylingEngine.class},(p,m,a)->null);
   var data=new DashboardData(client,prefs,styling,factory,new DataSeriesCache(client,factory));
   var dashboard=client.getDashboards().filter(d->d.getId().equals("support-costs-investable-v1")).findFirst().orElseThrow();
   data.setDashboard(dashboard);data.setDefaultReportingPeriod(new ReportingPeriod.YearToDate());
   var shell=new Shell(display);shell.setText("Frais supports");shell.setSize(1550,260);shell.setLayout(new GridLayout(3,true));
   shell.setData(name.abuchen.portfolio.ui.UIConstants.CSS.CLASS_NAME,"dashboard");
   var resources=new DashboardResources(shell);
   boolean checked=false;
   for(var column:dashboard.getColumns()) {
    var panel=new Composite(shell,SWT.NONE);panel.setLayout(new GridLayout());panel.setLayoutData(new GridData(SWT.FILL,SWT.FILL,true,true));
    for(var widget:column.getWidgets()) {
     var delegate=(SupportCostWidget)WidgetFactory.valueOf(widget.getType()).constructDelegate(widget,data);
     var control=delegate.createControl(panel,resources);control.setLayoutData(new GridData(SWT.FILL,SWT.FILL,true,true));
     var result=delegate.getUpdateTask().get();delegate.update(result);
     css.applyStyles(control,true);
     verifyKpi(control,reference);
     if(result.result()==null)throw new AssertionError(result.error());
     if(!checked) {
      var scope=delegate.get(DataSeriesConfig.class);var originalScope=scope.getDataSeries();
      var period=delegate.get(ReportingPeriodConfig.class);var originalPeriod=period.getReportingPeriod();
      period.setReportingPeriod(new ReportingPeriod.CurrentQuarter());
      var quarter=delegate.getUpdateTask().get();
      if(quarter.result()==null||!quarter.result().period().getEnd().equals(LocalDate.now()))throw new AssertionError("Current quarter failed: "+quarter.error());
      System.out.println("CURRENT_QUARTER_PASS cost="+quarter.result().estimatedCost()+" period="+quarter.result().period());
      var title=widget.getLabel();
      for(var type:new name.abuchen.portfolio.ui.views.dataseries.DataSeries.Type[]{name.abuchen.portfolio.ui.views.dataseries.DataSeries.Type.CLIENT,name.abuchen.portfolio.ui.views.dataseries.DataSeries.Type.PORTFOLIO,name.abuchen.portfolio.ui.views.dataseries.DataSeries.Type.SECURITY}) {
       var selected=data.getDataSeriesSet().getAvailableSeries().stream().filter(ds->ds.getType()==type)
        .filter(ds->SupportCosts.calculate(client,AfterTaxWidget.scope(client,ds),converter,new ReportingPeriod.CurrentQuarter().toInterval(LocalDate.now())).estimatedCost()>0).findFirst().orElseThrow();
       scope.setDataSeries(selected);var scoped=delegate.getUpdateTask().get();
       if(scoped.result()==null)throw new AssertionError("Scope failed "+type+": "+scoped.error());
       if(!selected.getUUID().equals(widget.getConfiguration().get("DATA_SERIES"))||!title.equals(widget.getLabel()))throw new AssertionError("Scope config/title persistence");
       var expected=SupportCosts.calculate(client,AfterTaxWidget.scope(client,selected),converter,new ReportingPeriod.CurrentQuarter().toInterval(LocalDate.now()));
       if(expected.estimatedCost()!=scoped.result().estimatedCost()||expected.rows().size()!=scoped.result().rows().size())throw new AssertionError("Scope calculation mismatch");
       System.out.println("SCOPE_PASS "+type+" | "+selected.getLabel()+" | cost="+scoped.result().estimatedCost());
      }
      scope.setDataSeries(originalScope);period.setReportingPeriod(originalPeriod);
      var menu=new org.eclipse.jface.action.MenuManager();menu.add(new org.eclipse.jface.action.GroupMarker(DashboardView.INFO_MENU_GROUP_NAME));scope.menuAboutToShow(menu);
      var nativeMenu=(org.eclipse.jface.action.MenuManager)menu.find("DATA_SERIES");
      if(nativeMenu==null||!"Périmètre".equals(nativeMenu.getMenuText()))throw new AssertionError("Native perimeter menu missing");menu.dispose();
      int dialogIndex=0;
      for(var dialog:java.util.List.of(delegate.infoDialog(),delegate.detailsDialog())) {
       dialog.create();dialog.getShell().open();dialog.getShell().layout(true,true);dialog.getShell().update();
       capture(dialog.getShell(),dialogIndex++==0?"Frais-infos":"Frais-detail");dialog.close();
      }
      checked=true;
     }
     var r=result.result();System.out.println("PERIOD "+widget.getLabel()+" cost="+r.estimatedCost()+" annual="+r.annualRate()+" coverage="+r.coverage()+" unavailable="+r.unavailableValuations());
     var lines=new java.util.ArrayList<String>();lines.add("Support;ISIN;Taux annuel;Estimation EUR;Jours;Source;Limites");
     for(var row:r.rows()) {
      if(row.annualRate()==null)System.out.println("MISSING "+widget.getLabel()+" | "+row.security().getName()+" | "+row.security().getIsin()+" | days="+row.days());
      lines.add(csv(row.security().getName())+";"+csv(row.security().getIsin())+";"+(row.annualRate()==null?"":row.annualRate())+";"+(row.annualRate()==null?"":row.estimatedCost()/100d)+";"+row.days()+";"+csv(row.source())+";"+csv(row.note()));
     }
     Files.write(Path.of(System.getProperty("probe.output"),"Frais-"+widget.getConfiguration().get("REPORTING_PERIOD")+".csv"),lines);
    }
   }
   var cl=client.getTaxonomies().stream().flatMap(t->t.getAllClassifications().stream()).filter(c->c.getName().equalsIgnoreCase("Portefeuille Investissable")).findFirst().orElseThrow();
   var current=SupportCosts.calculate(client,TaxScope.of(client,ClientFilter.NO_FILTER,cl),converter,Interval.of(LocalDate.now().minusDays(1),LocalDate.now()));
   if(!current.complete())throw new AssertionError("Current supports incomplete");
   System.out.println("CURRENT annual="+current.annualRate()+" scopeAnnual="+current.scopeAnnualRate()+" coverage="+current.coverage()+" supports="+current.rows().size());
   css.applyStyles(shell,true);
   shell.open();shell.layout(true,true);shell.update();
   long until=System.nanoTime()+300_000_000;while(System.nanoTime()<until){if(!display.readAndDispatch())Thread.sleep(5);}
   capture(shell,"Frais-supports-PP");shell.dispose();referenceShell.dispose();
   System.out.println("SUPPORT_COSTS_UI_PASS: compact widgets, quarter through today, native perimeter menu and three scopes, information/detail dialogs");
  }finally{display.dispose();}return 0;
 }
 static void verifyKpi(Composite control,name.abuchen.portfolio.ui.util.swt.ColoredLabel reference) {
  int count=0;
  for(var child:control.getChildren())if(child instanceof Composite composite)for(var metric:composite.getChildren())if(metric instanceof name.abuchen.portfolio.ui.util.swt.ColoredLabel) {
   if(!java.util.Arrays.equals(metric.getFont().getFontData(),reference.getFont().getFontData()))throw new AssertionError("Support value font differs from native KPI");
   if(metric.getFont().getFontData()[0].getHeight()<=metric.getDisplay().getSystemFont().getFontData()[0].getHeight())throw new AssertionError("Theme did not apply large KPI font: "+metric.getFont().getFontData()[0]);
   count++;
  }
  if(count!=2)throw new AssertionError("Two native KPI values required");
  System.out.println("NATIVE_KPI_FONT_PASS "+reference.getFont().getFontData()[0]);
 }
 static void capture(Shell shell,String name) {
  var rect=shell.getClientArea();var image=new Image(shell.getDisplay(),rect.width,rect.height);var gc=new GC(shell);gc.copyArea(image,0,0);gc.dispose();
  var loader=new ImageLoader();loader.data=new ImageData[]{image.getImageData()};loader.save(System.getProperty("probe.output")+"/"+name+".png",SWT.IMAGE_PNG);image.dispose();
 }
 static String csv(String v){return "\""+(v==null?"":v).replace("\"","\"\"")+"\"";}
 public void stop(){}
}
