"""Exercise yield dialogs with synthetic data; opens no portfolio."""
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
with tempfile.TemporaryDirectory(prefix='yield-ui-') as directory:
    work = Path(directory)
    source = work / 'YieldUiCheck.java'
    source.write_text('''package name.abuchen.portfolio.ui.updateactions.yields;
import org.eclipse.swt.widgets.*;
import name.abuchen.portfolio.model.*;
import name.abuchen.portfolio.money.*;
import name.abuchen.portfolio.updates.yields.*;
public class YieldUiCheck implements org.eclipse.equinox.app.IApplication {
 public Object start(org.eclipse.equinox.app.IApplicationContext context) {
  var display = new Display();
  try {
   var shell = new Shell(display);
   var client = new Client(); client.setBaseCurrency("EUR");
   var security = new Security("Synthetic Vanguard", "EUR"); security.setIsin("IE00BG47KH54"); client.addSecurity(security);
   var converter = new CurrencyConverterImpl(new ExchangeRateProviderFactory(client), "EUR");
   var dialog = new YieldDialog(shell, client, converter, () -> false); dialog.create();
   if (!dialog.getShell().getVisible()) dialog.getShell().layout(true, true);
   dialog.close();
   var row = BondYields.calculate(client, converter, java.time.LocalDate.now()).rows().getFirst();
   var editor = new YieldObservationDialog(shell, client, row, java.time.LocalDate.now()); editor.create(); editor.close();
   if(client.getProperty(BondYields.HISTORY)!=null) throw new AssertionError("Preview mutated client");
   shell.dispose();
   System.out.println("YIELD_UI_PASS: preview and observation editor created without modifying portfolio");
  } finally { display.dispose(); }
  return EXIT_OK;
 }
 public void stop() {}
}''')
    subprocess.run([str(java/'javac'), '-cp', classpath, '-d', str(work), str(source)], check=True)
    bundle = work / 'probe.jar'
    with zipfile.ZipFile(bundle, 'w') as archive:
        archive.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\nBundle-ManifestVersion: 2\nBundle-SymbolicName: probe.yields;singleton:=true\nBundle-Version: 1.0.0\nFragment-Host: name.abuchen.portfolio.ui\nRequire-Bundle: org.eclipse.equinox.app\n\n')
        archive.writestr('fragment.xml', '<fragment><extension point="org.eclipse.core.runtime.applications" id="check"><application thread="main"><run class="name.abuchen.portfolio.ui.updateactions.yields.YieldUiCheck"/></application></extension></fragment>')
        entry = 'name/abuchen/portfolio/ui/updateactions/yields/YieldUiCheck.class'
        archive.write(work / entry, entry)
    base = args.eclipse.resolve()
    config = work / 'configuration'
    shutil.copytree(base / 'configuration', config)
    with (config / 'org.eclipse.equinox.simpleconfigurator/bundles.info').open('a') as info:
        info.write(f'\nprobe.yields,1.0.0,{bundle.as_uri()},4,false\n')
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
