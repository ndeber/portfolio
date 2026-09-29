"""Check the PE calculated quote history on synthetic data; opens no portfolio."""
from pathlib import Path
import argparse
import subprocess
import sys
import tempfile
import zipfile
import shutil

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('eclipse', type=Path)
parser.add_argument('--java-home', required=True, type=Path)
args = parser.parse_args()
java = args.java_home.resolve() / 'bin'
classpath = ':'.join(str(p.resolve()) for p in (args.eclipse / 'plugins').glob('*.jar'))
with tempfile.TemporaryDirectory(prefix='pe-history-ui-') as directory:
    work = Path(directory)
    source = work / 'PeHistoryCheck.java'
    source.write_text('''package name.abuchen.portfolio.ui.views.panes;
import org.eclipse.swt.widgets.*;
import name.abuchen.portfolio.model.*;
import name.abuchen.portfolio.money.*;
public class PeHistoryCheck implements org.eclipse.equinox.app.IApplication {
 public static class View extends name.abuchen.portfolio.ui.editor.AbstractFinanceView {
  protected String getDefaultTitle(){return "Test";}
  protected Control createBody(Composite parent){return parent;}
 }
 private static void inject(Object target,String name,Object value) throws Exception {
  var f=target.getClass().getDeclaredField(name); f.setAccessible(true); f.set(target,value);
 }
 public Object start(org.eclipse.equinox.app.IApplicationContext context) throws Exception {
  var display = new Display();
  try {
   var shell = new Shell(display); shell.setLayout(new org.eclipse.swt.layout.FillLayout());
   var client = new Client(); client.setBaseCurrency("EUR");
   var fund = new Security("Synthetic PE", "EUR"); client.addSecurity(fund);
   var account = new Account("Cash"); account.setCurrencyCode("EUR"); client.addAccount(account);
   var portfolio=new Portfolio("PE"); client.addPortfolio(portfolio);
   var date = java.time.LocalDate.of(2026,1,1);
   fund.addPrice(new SecurityPrice(date,Values.Quote.factorize(100)));
   portfolio.addTransaction(new PortfolioTransaction(date.atStartOfDay(),"EUR",1000000,fund,Values.Share.factorize(100),PortfolioTransaction.Type.DELIVERY_INBOUND,0,0));
   var call=new AccountTransaction(date.plusDays(1).atStartOfDay(),"EUR",200000,fund,AccountTransaction.Type.CAPITAL_CALL); account.addTransaction(call);
   var pane=new HistoricalPricesPane(); inject(pane,"client",client); inject(pane,"preferences",new org.eclipse.jface.preference.PreferenceStore()); inject(pane,"view",new View());
   pane.createViewControl(shell); pane.setInput(fund); shell.setSize(600,400); shell.layout(true,true);
   var f=HistoricalPricesPane.class.getDeclaredField("prices");f.setAccessible(true);var viewer=(org.eclipse.jface.viewers.TableViewer)f.get(pane);
   var list=(java.util.List<?>)viewer.getInput();
   if(list.size()!=2)throw new AssertionError("Missing calculated quote");
   var calculated=(SecurityPrice)list.get(1);
   if(calculated.getValue()!=Values.Quote.factorize(120))throw new AssertionError("Wrong NAV");
   viewer.editElement(calculated,1);
   if(viewer.isCellEditorActive())throw new AssertionError("Calculated quote editable");
   call.setAmount(300000); pane.onRecalculationNeeded();
   if(((SecurityPrice)((java.util.List<?>)viewer.getInput()).get(1)).getValue()!=Values.Quote.factorize(130))throw new AssertionError("History not refreshed");
   if(fund.getPrices().size()!=1)throw new AssertionError("History mutated stored NAVs");
   shell.dispose();
   System.out.println("PE_HISTORY_UI_PASS: calculated quote visible, read-only and refreshed without changing stored prices");
  } finally { display.dispose(); }
  return EXIT_OK;
 }
 public void stop() {}
}''')
    subprocess.run([str(java/'javac'), '-cp', classpath, '-d', str(work), str(source)], check=True)
    bundle = work / 'probe.jar'
    with zipfile.ZipFile(bundle, 'w') as archive:
        archive.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\nBundle-ManifestVersion: 2\nBundle-SymbolicName: probe.pehistory;singleton:=true\nBundle-Version: 1.0.0\nFragment-Host: name.abuchen.portfolio.ui\nRequire-Bundle: org.eclipse.equinox.app\n\n')
        archive.writestr('fragment.xml', '<fragment><extension point="org.eclipse.core.runtime.applications" id="check"><application thread="main"><run class="name.abuchen.portfolio.ui.views.panes.PeHistoryCheck"/></application></extension></fragment>')
        entry = 'name/abuchen/portfolio/ui/views/panes/PeHistoryCheck.class'
        for compiled in (work / 'name/abuchen/portfolio/ui/views/panes').glob('PeHistoryCheck*.class'):
            archive.write(compiled, str(compiled.relative_to(work)))
    base = args.eclipse.resolve()
    config = work / 'configuration'
    shutil.copytree(base / 'configuration', config)
    with (config / 'org.eclipse.equinox.simpleconfigurator/bundles.info').open('a') as info:
        info.write(f'\nprobe.pehistory,1.0.0,{bundle.as_uri()},4,false\n')
    command = [str(java/'java')]
    if sys.platform == 'darwin':
        command.append('-XstartOnFirstThread')
    launcher = next((base / 'plugins').glob('org.eclipse.equinox.launcher_*.jar'))
    command += ['-jar', str(launcher), '-nosplash', '-install', str(base), '-configuration', str(config),
                '-data', str(work / 'workspace'), '-application', 'name.abuchen.portfolio.ui.check', '-consoleLog']
    result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=45)
    print(result.stdout)
    if result.returncode:
        raise SystemExit(result.returncode)

    if "PE_HISTORY_UI_PASS" not in result.stdout:
        raise SystemExit(1)
