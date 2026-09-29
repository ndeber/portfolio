"""Exercise capitalisation preview and live Vanguard source with synthetic data; opens no portfolio."""
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
with tempfile.TemporaryDirectory(prefix='caps-ui-') as directory:
    work = Path(directory)
    source = work / 'CapsUiCheck.java'
    source.write_text('''package name.abuchen.portfolio.ui.updateactions.equity;
import org.eclipse.swt.widgets.*;
import name.abuchen.portfolio.model.*;
import name.abuchen.portfolio.updates.equity.*;
public class CapsUiCheck implements org.eclipse.equinox.app.IApplication {
 public Object start(org.eclipse.equinox.app.IApplicationContext context) throws Exception {
  var display = new Display();
  try {
   var shell = new Shell(display); var client = new Client();
   var security = new Security("Vanguard FTSE All-World", "EUR"); security.setIsin("IE00BK5BQT80"); client.addSecurity(security);
   for (String name : new String[]{"Pilotage Global", "Objectifs et Classes d'actifs"}) {
    var tax = new Taxonomy(name); var root = new Classification(name, name); tax.setRootNode(root); client.addTaxonomy(tax);
    var actions = new Classification(root, name+"-actions", "Actions"); root.addChild(actions);
    for(String cap : new String[]{"Large Caps", "Small Caps"}) {
     var c = new Classification(actions, name+cap, cap); actions.addChild(c);
     if(cap.equals("Large Caps")) c.addAssignment(new Classification.Assignment(security));
    }
   }
   var outcome = new EquityCapSources().fetch(security, () -> false);
   if(outcome.error()!=null) throw new AssertionError(outcome.error());
   var caps = EquityCaps.prepare(client, java.util.List.of(outcome));
   var empty = new EquityAdjustment.Plan("",java.util.List.of(),java.util.List.of(),java.util.List.of(),java.util.List.of());
   var dialog = new EquityPreviewDialog(shell, empty, 1, caps); dialog.create(); dialog.getShell().layout(true,true); dialog.close();
   if(client.getTaxonomies().getFirst().getAllClassifications().stream().anyMatch(c->c.getName().equals("Mid Cap"))) throw new AssertionError("Preview mutated model");
   EquityCaps.apply(client,caps);
   if(!EquityCaps.prepare(client,java.util.List.of(outcome)).changes().isEmpty()) throw new AssertionError("Not idempotent");
   RefreshEquityHandler.class.getDeclaredMethods();
   shell.dispose();
   System.out.println("CAPS_UI_PASS: official live source, preview, both taxonomies, idempotent apply; no portfolio opened");
  } finally { display.dispose(); }
  return EXIT_OK;
 }
 public void stop() {}
}''')
    subprocess.run([str(java/'javac'), '-cp', classpath, '-d', str(work), str(source)], check=True)
    bundle = work / 'probe.jar'
    with zipfile.ZipFile(bundle, 'w') as archive:
        archive.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\nBundle-ManifestVersion: 2\nBundle-SymbolicName: probe.caps;singleton:=true\nBundle-Version: 1.0.0\nFragment-Host: name.abuchen.portfolio.ui\nRequire-Bundle: org.eclipse.equinox.app\n\n')
        archive.writestr('fragment.xml', '<fragment><extension point="org.eclipse.core.runtime.applications" id="check"><application thread="main"><run class="name.abuchen.portfolio.ui.updateactions.equity.CapsUiCheck"/></application></extension></fragment>')
        entry = 'name/abuchen/portfolio/ui/updateactions/equity/CapsUiCheck.class'
        archive.write(work / entry, entry)
    base = args.eclipse.resolve()
    config = work / 'configuration'
    shutil.copytree(base / 'configuration', config)
    with (config / 'org.eclipse.equinox.simpleconfigurator/bundles.info').open('a') as info:
        info.write(f'\nprobe.caps,1.0.0,{bundle.as_uri()},4,false\n')
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

    if "CAPS_UI_PASS" not in result.stdout:
        raise SystemExit(1)
