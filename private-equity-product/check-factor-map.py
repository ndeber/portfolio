"""Exercise the packaged Factor Map; optionally prepare a distinct test copy."""
from pathlib import Path
import argparse
import shutil
import subprocess
import sys
import tempfile
import zipfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('eclipse', type=Path)
parser.add_argument('--java-home', required=True, type=Path)
parser.add_argument('--output', required=True, type=Path)
parser.add_argument('--portfolio', type=Path)
parser.add_argument('--fx-cache', type=Path)
parser.add_argument('--duration-pdf', type=Path)
parser.add_argument('--live', action='store_true')
parser.add_argument('--refresh-dbi', action='store_true')
parser.add_argument('--refresh-invesco', action='store_true', help='exercise the native update button against the public source')
parser.add_argument('--currency-proposals', action='store_true', help='prepare and inspect currency proposals, never applies to input')
parser.add_argument('--prepare-portfolio', type=Path, help='new copy with currency taxonomy; refuses existing output')
args = parser.parse_args()
if args.prepare_portfolio and args.prepare_portfolio.exists():
    parser.error('Refusing to overwrite the prepared portfolio')
args.output.mkdir(parents=True, exist_ok=True)
base = args.eclipse.resolve()
java = args.java_home.resolve() / 'bin'
classpath = ':'.join(str(p) for p in (base / 'plugins').glob('*.jar'))
with tempfile.TemporaryDirectory(prefix='factor-map-check-') as directory:
    work = Path(directory)
    source = work / 'FactorMapCheck.java'
    source.write_text(r'''package name.abuchen.portfolio.ui.factors;
import java.time.*;
import java.nio.file.*;
import org.eclipse.swt.*;
import org.eclipse.swt.widgets.*;
import org.eclipse.swt.graphics.*;
import org.eclipse.swt.layout.*;
import org.eclipse.jface.viewers.*;
import name.abuchen.portfolio.model.*;
import name.abuchen.portfolio.money.*;
import name.abuchen.portfolio.factors.*;
import name.abuchen.portfolio.ui.editor.*;
public class FactorMapCheck implements org.eclipse.equinox.app.IApplication {
 static Object get(Object o,Class<?> c,String name)throws Exception {var f=c.getDeclaredField(name);f.setAccessible(true);return f.get(o);}
 static void set(Object o,Class<?> c,String name,Object value)throws Exception {var f=c.getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
 static void capture(Shell shell, String name)throws Exception {
  shell.layout(true,true);shell.redraw();shell.update();
  var d=shell.getDisplay();long until=System.nanoTime()+250_000_000L;
  while(System.nanoTime()<until){if(!d.readAndDispatch())Thread.sleep(5);}
  var size=shell.getClientArea();var image=new Image(d,size.width,size.height);var gc=new GC(shell);
  gc.copyArea(image,0,0);gc.dispose();var loader=new ImageLoader();loader.data=new ImageData[]{image.getImageData()};
  loader.save(System.getProperty("probe.output")+"/"+name+".png",SWT.IMAGE_PNG);image.dispose();
 }
 static void holding(Client client,Portfolio p,String name,String isin,long eur) {
  var s=new Security(name,"EUR");s.setIsin(isin);s.addPrice(new SecurityPrice(LocalDate.now().minusDays(1),Values.Quote.factor()));client.addSecurity(s);
  var t=new PortfolioTransaction();t.setSecurity(s);t.setType(PortfolioTransaction.Type.DELIVERY_INBOUND);t.setDateTime(LocalDate.now().minusDays(5).atStartOfDay());
  t.setCurrencyCode("EUR");t.setAmount(eur*100);t.setShares(eur*Values.Share.factor());p.addTransaction(t);
 }
 public Object start(org.eclipse.equinox.app.IApplicationContext context)throws Exception {
  if(System.getProperty("probe.durationPdf")!=null) {
   var text=new name.abuchen.portfolio.pdfbox3.PDFBox3Adapter().convertToText(new java.io.File(System.getProperty("probe.durationPdf")));
   Files.writeString(java.nio.file.Path.of(System.getProperty("probe.output"),"duration-source-extracted.txt"),text);
   var duration=DbiRateData.parse(text,LocalDate.now());
   if(!duration.equals(DbiRateData.bundled()))throw new AssertionError("Bundled duration differs from source PDF: "+duration);
   System.out.println("DURATION_PDF_PASS: "+duration);
  }
  if(Boolean.getBoolean("probe.live")) {
   var data=new DbiData();var snapshot=data.fetch(DbiData.bundled(),LocalDate.now());
   if(snapshot.holdings().size()<5)throw new AssertionError("Incomplete official response");
   var ucits=data.fetchUcits(null,LocalDate.now());
   var invesco=InvescoData.fetch(InvescoData.bundled(),LocalDate.now());
   System.out.println("INVESCO_LIVE_PASS: "+invesco.date()+", rows="+invesco.holdings().size());
   System.out.println("DBI_LIVE_PASS: DBMF "+snapshot.date()+", futures="+snapshot.holdings().size()+", UCITS "+ucits.date());
  }
  var display=new Display();
  try {
   var client=new Client();client.setBaseCurrency("EUR");
   String file=System.getProperty("probe.portfolio");
   if(file!=null)client=ClientFactory.load(new java.io.File(file),null,new org.eclipse.core.runtime.NullProgressMonitor());
   else {
    var a=new Account("Cash EUR");a.setCurrencyCode("EUR");client.addAccount(a);var p=new Portfolio();p.setReferenceAccount(a);client.addPortfolio(p);
    holding(client,p,"Or physique","JE00B588CD74",100);holding(client,p,"DBi EUR couvert",FactorMap.UCITS_HEDGED,50);
    var t=new AccountTransaction();t.setType(AccountTransaction.Type.DEPOSIT);t.setCurrencyCode("EUR");t.setAmount(85000);t.setDateTime(LocalDate.now().atStartOfDay());a.addTransaction(t);
   }
   CurrencyExposure.ensure(client);
   String output=System.getProperty("probe.prepare");
   if(output!=null) {
    var target=java.nio.file.Path.of(output);Files.createFile(target);
    var before=clientSnapshotValue(client);
    InvescoData.save(client,InvescoData.bundled(),client.getProperty(InvescoData.PROPERTY));
    ClientFactory.save(client,target.toFile());
    var reloaded=ClientFactory.load(target.toFile(),null,new org.eclipse.core.runtime.NullProgressMonitor());
    if(CurrencyExposure.find(reloaded)==null || !InvescoData.load(reloaded).equals(InvescoData.bundled())
       || client.getSecurities().size()!=reloaded.getSecurities().size()
       || client.getAccounts().size()!=reloaded.getAccounts().size()
       || !before.equals(clientSnapshotValue(reloaded)))throw new AssertionError("Prepared copy failed round trip");
    client=reloaded;System.out.println("DBI_COPY_PASS: "+output);
   }
   var inputConstructor=ClientInput.class.getDeclaredConstructor(String.class,java.io.File.class);inputConstructor.setAccessible(true);
   var input=inputConstructor.newInstance("Factor check",null);set(input,ClientInput.class,"client",client);
   input.getPreferenceStore().setValue("fork.factor-map.checked.v1",LocalDate.now().toString());
   var part=new PortfolioPart();set(part,PortfolioPart.class,"clientInput",input);
   var view=new FactorMapView();set(view,AbstractFinanceView.class,"part",part);
   set(view,FactorMapView.class,"factory",new ExchangeRateProviderFactory(client));
   var shell=new Shell(display);shell.setText("Positions & impact DBi — vérification");shell.setLayout(new FillLayout());shell.setSize(1340,1030);
   view.createBody(shell);shell.open();capture(shell,"factor-map");
   var mapTable=(TableViewer)get(view,FactorMapView.class,"map");
   var tabs=(TabFolder)mapTable.getTable().getParent().getParent().getParent();
   if(tabs.getItemCount()!=4 || tabs.getSelectionIndex()!=0 || !tabs.getItem(0).getText().equals("Expositions"))throw new AssertionError("Exposures not the default tab");
   var result=(FactorMap.Result)get(view,FactorMapView.class,"result");
   if(result==null || result.rows().size()!=10)throw new AssertionError("Factor rows missing");
   if(result.rows().get(5).factor()!=Factor.USD_EUR || result.rows().get(6).factor()!=Factor.JPY_EUR)throw new AssertionError("Notional axes not in EUR");
   System.out.println("DBI_MODEL: scope="+result.scope()+", valued="+result.valued()+", DBi weight="+result.dbiWeight()+", positions="+result.positions().size());
   for(var row:result.rows())System.out.println("FACTOR: "+row);
   var proxy=(Button)get(view,FactorMapView.class,"proxy");
   if(!proxy.getSelection())throw new AssertionError("US proxy not enabled by default");
   proxy.setSelection(false);proxy.notifyListeners(SWT.Selection,new Event());
   if(input.getPreferenceStore().getBoolean("fork.factor-map.us-proxy.v1"))throw new AssertionError("Proxy choice not remembered");
   proxy.setSelection(true);proxy.notifyListeners(SWT.Selection,new Event());capture(shell,"factor-map-simulation");
   result=(FactorMap.Result)get(view,FactorMapView.class,"result");
   if(result.valued() && result.rows().stream().anyMatch(r->r.overlay()==null))throw new AssertionError("Missing proxy overlay");
   var toolbar=new org.eclipse.jface.action.ToolBarManager();view.addButtons(toolbar);
   if(toolbar.getItems().length!=0)throw new AssertionError("Header actions still present");
   assertNoRetiredUi(shell);
   if(proxy.getParent()!=((Composite)tabs.getItem(2).getControl()).getChildren()[0])throw new AssertionError("Proxy must be inside source tab");
   System.out.println("EXPOSURES_PASS: notional only, four tabs, no VA/scenarios/rates pane, no header actions");
   var table=(TableViewer)get(view,FactorMapView.class,"positions");
   if(!table.getTable().getColumn(9).getText().startsWith("USD/EUR") || !table.getTable().getColumn(10).getText().startsWith("JPY/EUR"))throw new AssertionError("Positions FX headers incorrect");
   tabs.setSelection(1);tabs.notifyListeners(SWT.Selection,new Event());
   checkSorting(table);
   view.notifyModelUpdated();
   table.getTable().getColumn(1).notifyListeners(SWT.Selection,new Event());
   capture(shell,"factor-map-positions");
   tabs.setSelection(2);tabs.notifyListeners(SWT.Selection,new Event());
   var sourceDate=(Label)get(view,FactorMapView.class,"dbmfDate");
   var current=(DbiData.Snapshot)get(view,FactorMapView.class,"snapshot");
   if(!sourceDate.getText().contains(current.date()) || sourceDate.getParent()!=tabs.getItem(2).getControl())throw new AssertionError("DBMF source publication date missing");
   var update=findButton(shell,"Mettre à jour les positions DBi");
   if(update==null || update.getListeners(SWT.Selection).length==0 || update.getParent().getParent()!=tabs.getItem(2).getControl())throw new AssertionError("Source update button missing");
   if(Boolean.getBoolean("probe.refreshDbi")) {
    update.notifyListeners(SWT.Selection,new Event());
    if(update.getEnabled() || !(boolean)get(view,FactorMapView.class,"fetching"))throw new AssertionError("Refresh not started");
    long deadline=System.nanoTime()+110_000_000_000L;
    while((boolean)get(view,FactorMapView.class,"fetching") && System.nanoTime()<deadline) {
     if(!display.readAndDispatch())Thread.sleep(10);
    }
    if(!update.getEnabled() || (boolean)get(view,FactorMapView.class,"fetching"))throw new AssertionError("Refresh did not finish");
    var updated=(DbiData.Snapshot)get(view,FactorMapView.class,"snapshot");
    if(LocalDate.parse(updated.date()).isBefore(LocalDate.parse(current.date())) || !sourceDate.getText().contains(updated.date()))throw new AssertionError("Published date lost after refresh");
    System.out.println("DBI_REFRESH_PASS: "+((Label)get(view,FactorMapView.class,"status")).getText());
   }
   System.out.println("SOURCE_DATE_PASS: "+sourceDate.getText());
   capture(shell,"factor-map-sources");
   for(String field:new String[]{"ucitsPositions","futures"}) {
    var v=(TableViewer)get(view,FactorMapView.class,field);
    if(v.getTable().getTopIndex()!=0 || v.getTable().getItem(0).getBounds().y<0)throw new AssertionError("Initial table origin incorrect: "+field);
   }
   tabs.setSelection(3);tabs.notifyListeners(SWT.Selection,new Event());capture(shell,"factor-map-invesco");
   var commodities=(TableViewer)get(view,FactorMapView.class,"commodities");
   if(commodities.getTable().getItemCount()!=25)throw new AssertionError("Invesco rows missing");
   if(Boolean.getBoolean("probe.refreshInvesco")) {
    findButton(shell,"Mettre à jour depuis Invesco").notifyListeners(SWT.Selection,new Event());
    long deadline=System.nanoTime()+50_000_000_000L;
    while((boolean)get(view,FactorMapView.class,"fetchingInvesco") && System.nanoTime()<deadline) {
     if(!display.readAndDispatch())Thread.sleep(10);
    }
    var text=((Label)get(view,FactorMapView.class,"commodityStatus")).getText();
    if(!text.startsWith("Source vérifiée"))throw new AssertionError("Invesco button: "+text);
    capture(shell,"factor-map-invesco");System.out.println("INVESCO_BUTTON_PASS: "+text);
   }
   tabs.setSelection(1);tabs.notifyListeners(SWT.Selection,new Event());
   var position=result.positions().stream().filter(p->!p.dbi()).findFirst().orElseThrow();
   var dialog=new FactorMappingDialog(shell,client,position);dialog.create();dialog.getShell().open();capture(dialog.getShell(),"factor-map-mapping");dialog.close();
   var navConstructor=Navigation.class.getDeclaredConstructor(Client.class);navConstructor.setAccessible(true);var navigation=navConstructor.newInstance(client);
   if(navigation.findAll(i->i.getViewClass()==FactorMapView.class).count()!=1)throw new AssertionError("Navigation entry missing");
   var currencyTax=CurrencyExposure.find(client);
   if(navigation.findAll(i->i.getParameter()==currencyTax).count()!=1)throw new AssertionError("Currency taxonomy navigation missing");
   if(Boolean.getBoolean("probe.currencies")) {
    var evidence=new java.util.LinkedHashMap<String,CurrencyProposals.Evidence>();var source=new CurrencySources();
    for(var s:CurrencyProposals.scope(client,LocalDate.now()))evidence.put(s.getUUID(),source.fetch(client,s,LocalDate.now(),()->false));
    var plan=CurrencyProposals.prepare(client,LocalDate.now(),evidence);
    var report=new StringBuilder("PROPOSITION D'ALLOCATION DEVISES — "+LocalDate.now()+"\n\nAucune affectation appliquée. Les estimations nécessitent votre sélection explicite.\n\n");
    for(var r:plan.rows()) report.append(r.name()).append(" · ").append(r.isin()).append("\nAvant : ").append(r.before())
     .append("\nProposition : ").append(r.evidence().weights().isEmpty()?"Inchangé — à documenter":CurrencyProposals.format(r.evidence().weights()))
     .append("\n").append(r.evidence().method()).append(" · ").append(r.evidence().date()==null?"date non documentée":r.evidence().date())
     .append("\n").append(r.evidence().note()).append("\n").append(r.evidence().source()).append("\n\n");
    Files.writeString(java.nio.file.Path.of(System.getProperty("probe.output"),"Proposition-devises.txt"),report);
    Files.writeString(java.nio.file.Path.of(System.getProperty("probe.output"),"currency-proposals.json"),CurrencyProposals.JSON.toJson(plan.rows()));
    String before=client.getProperty(CurrencyProposals.AUDIT);
    var currencyDialog=new CurrencyPreviewDialog(shell,plan);currencyDialog.create();currencyDialog.getShell().open();capture(currencyDialog.getShell(),"currency-proposals");
    if(!currencyDialog.selected().isEmpty())throw new AssertionError("Unverified estimates preselected");
    var proposalTable=(Table)get(currencyDialog,CurrencyPreviewDialog.class,"table");
    if(proposalTable.getTopIndex()!=0 || proposalTable.getItem(0).getBounds().y<0)throw new AssertionError("Currency first row underneath header: "+proposalTable.getItem(0).getBounds());
    var first=java.util.Arrays.stream(proposalTable.getItems()).filter(i->!((CurrencyProposals.Row)i.getData()).evidence().weights().isEmpty()).findFirst().orElseThrow();
    first.setChecked(true);var event=new Event();event.detail=SWT.CHECK;event.item=first;proposalTable.notifyListeners(SWT.Selection,event);
    if(currencyDialog.selected().size()!=1)throw new AssertionError("Selection failed");currencyDialog.close();
    if(!java.util.Objects.equals(before,client.getProperty(CurrencyProposals.AUDIT)))throw new AssertionError("Preview changed model");
    var copy=ClientFactory.duplicate(client);var selected=plan.rows().stream().filter(r->!r.evidence().weights().isEmpty()).map(CurrencyProposals.Row::id).collect(java.util.stream.Collectors.toSet());
    CurrencyProposals.apply(copy,plan,selected);ClientFactory.duplicate(copy);
    System.out.println("CURRENCY_PREVIEW_PASS: examined="+plan.rows().size()+", proposed="+selected.size()+", unchanged="+(plan.rows().size()-selected.size())+"; preview and cancellation preserved file; application checked on memory copy");
   }
   shell.dispose();System.out.println("DBI_UI_PASS: navigation, 10 factors, proxy switch, notional-only view, positions, dated sources, mapping dialog");
  }finally{display.dispose();}
  return EXIT_OK;
 }
 public void stop(){}
 static void checkSorting(TableViewer viewer) {
  var rows=new java.util.ArrayList<FactorMap.Position>();
  Double[] numbers={2d,null,-100d,10d,0d};
  String[] names={"Charlie","Echo","Alpha","Delta","Bravo"};
  for(int i=0;i<numbers.length;i++) {
   var values=new java.util.EnumMap<Factor,Double>(Factor.class);
   var coverage=new java.util.EnumMap<Factor,Double>(Factor.class);
   if(numbers[i]!=null)for(var f:Factor.portfolioAxes()){values.put(f,numbers[i]);coverage.put(f,.5);}
   rows.add(new FactorMap.Position(new Security(names[i],"EUR"),numbers[i]==null?null:numbers[i].longValue(),
    numbers[i]==null?null:LocalDate.of(2026,1,1).plusDays(numbers[i].longValue()),i==0,values,coverage,names[i]));
  }
  viewer.setInput(rows);
  var table=viewer.getTable();
  if(table.getColumnCount()!=15)throw new AssertionError("Expected all 15 sortable columns");
  for(int c=0;c<table.getColumnCount();c++) {
   var col=table.getColumn(c);
   for(int click=0;click<2;click++) {
    col.notifyListeners(SWT.Selection,new Event());
    if(table.getSortColumn()!=col || table.getSortDirection()!=(click==0?SWT.DOWN:SWT.UP))throw new AssertionError("Sort arrow/direction: "+c);
    String expected;
    if(c==1 || c==2 || c>=4 && c<=13)expected=click==0?"Delta,Charlie,Bravo,Alpha,Echo":"Alpha,Bravo,Charlie,Delta,Echo";
    else if(c==3)expected=click==0?"DBi,Baseline,Baseline,Baseline,Baseline":"Baseline,Baseline,Baseline,Baseline,DBi";
    else expected=click==0?"Echo,Delta,Charlie,Bravo,Alpha":"Alpha,Bravo,Charlie,Delta,Echo";
    String actual=java.util.Arrays.stream(table.getItems()).map(i->{var r=(FactorMap.Position)i.getData();return cRole(col,table)?(r.dbi()?"DBi":"Baseline"):r.vehicle().getName();}).collect(java.util.stream.Collectors.joining(","));
    if(!expected.equals(actual))throw new AssertionError("Sort column "+c+": "+actual+" expected "+expected);
    viewer.setInput(rows);
    String refreshed=java.util.Arrays.stream(table.getItems()).map(i->i.getText(0)).collect(java.util.stream.Collectors.joining(","));
    if(c!=3 && !expected.equals(refreshed))throw new AssertionError("Sort lost after refresh");
   }
  }
  System.out.println("POSITIONS_SORT_PASS: 15 columns, both directions, signed numeric values, partial exposures, unknowns last, refresh");
 }
 static boolean cRole(TableColumn col,Table table){return col==table.getColumn(3);}
 static void assertNoRetiredUi(Control control) {
  String text=control instanceof Label l?l.getText():control instanceof Button b?b.getText():control instanceof Combo c?String.join(" ",c.getItems()):"";
  if(text.matches("(?is).*\\bVA\\b.*") || text.contains("Taux · compensation"))throw new AssertionError("Retired UI text: "+text);
  if(control instanceof Composite c)for(var child:c.getChildren())assertNoRetiredUi(child);
 }
 static Button findButton(Control control,String text) {
  if(control instanceof Button b && text.equals(b.getText()))return b;
  if(control instanceof Composite c)for(var child:c.getChildren()){var found=findButton(child,text);if(found!=null)return found;}
  return null;
 }
 static String clientSnapshotValue(Client client) {
  boolean pilotable=client.getTaxonomies().stream().anyMatch(t->"Pilotage Global".equals(t.getName()));
  var r=FactorMap.calculate(client,new CurrencyConverterImpl(new ExchangeRateProviderFactory(client),"EUR"),LocalDate.now(),pilotable,false,false,DbiData.bundled());
  return r.assets()+"/"+r.positions().stream().map(p->p.vehicle().getUUID()+":"+p.value()).sorted().toList();
 }
}''')
    compilation = subprocess.run([str(java / 'javac'), '-cp', classpath, '-d', str(work), str(source)])
    if compilation.returncode:
        raise SystemExit(compilation.returncode)
    bundle = work / 'probe.jar'
    with zipfile.ZipFile(bundle, 'w') as archive:
        archive.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\nBundle-ManifestVersion: 2\nBundle-SymbolicName: probe.factormap;singleton:=true\nBundle-Version: 1.0.0\nFragment-Host: name.abuchen.portfolio.ui\nRequire-Bundle: org.eclipse.equinox.app,name.abuchen.portfolio.pdfbox3\n\n')
        archive.writestr('fragment.xml', '<fragment><extension point="org.eclipse.core.runtime.applications" id="check"><application thread="main"><run class="name.abuchen.portfolio.ui.factors.FactorMapCheck"/></application></extension></fragment>')
        for item in work.rglob('*.class'):
            archive.write(item, str(item.relative_to(work)))
    config = work / 'configuration'
    shutil.copytree(base / 'configuration', config)
    with (config / 'org.eclipse.equinox.simpleconfigurator/bundles.info').open('a') as info:
        info.write(f'\nprobe.factormap,1.0.0,{bundle.as_uri()},4,false\n')
    workspace = work / 'workspace'
    if args.fx_cache:
        dest = workspace / '.metadata/.plugins/name.abuchen.portfolio/ecb_exchange_rates.pb'
        dest.parent.mkdir(parents=True)
        shutil.copy2(args.fx_cache, dest)
    command = [str(java / 'java')]
    if sys.platform == 'darwin':
        command.append('-XstartOnFirstThread')
    command += [f'-Dprobe.output={args.output.resolve()}', f'-Dprobe.live={str(args.live).lower()}',
                f'-Dprobe.refreshDbi={str(args.refresh_dbi).lower()}', f'-Dprobe.refreshInvesco={str(args.refresh_invesco).lower()}', f'-Dprobe.currencies={str(args.currency_proposals).lower()}']
    if args.duration_pdf:
        command.append(f'-Dprobe.durationPdf={args.duration_pdf.resolve()}')
    if args.prepare_portfolio:
        command.append(f'-Dprobe.prepare={args.prepare_portfolio.resolve()}')
    if args.portfolio:
        # Work on a copy, including when a user accidentally passes the original.
        copied = work / args.portfolio.name
        shutil.copy2(args.portfolio, copied)
        command.append(f'-Dprobe.portfolio={copied}')
    launcher = next((base / 'plugins').glob('org.eclipse.equinox.launcher_*.jar'))
    command += ['-jar', str(launcher), '-nosplash', '-install', str(base), '-configuration', str(config),
                '-data', str(workspace), '-application', 'name.abuchen.portfolio.ui.check', '-consoleLog']
    result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=150)
    print(result.stdout)
    if result.returncode or 'DBI_UI_PASS' not in result.stdout:
        raise SystemExit(result.returncode or 1)
