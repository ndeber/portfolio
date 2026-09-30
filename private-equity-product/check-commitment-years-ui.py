"""Exercise commitment FYe/YTD dialog with synthetic data; opens no portfolio."""
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
with tempfile.TemporaryDirectory(prefix='commitment-years-ui-') as directory:
    work = Path(directory)
    source = work / 'CommitmentYearsCheck.java'
    source.write_text('''package name.abuchen.portfolio.ui.commitments;
import org.eclipse.swt.widgets.*;
import name.abuchen.portfolio.model.*;
import name.abuchen.portfolio.money.*;
import name.abuchen.portfolio.commitments.*;
public class CommitmentYearsCheck implements org.eclipse.equinox.app.IApplication {
 public Object start(org.eclipse.equinox.app.IApplicationContext context) throws Exception {
  var display = new Display();
  try {
   var shell = new Shell(display); var client = new Client(); client.setBaseCurrency("EUR");
   var security = new Security("Synthetic PE", "EUR"); client.addSecurity(security);
   var account = new Account("Cash"); account.setCurrencyCode("EUR"); client.addAccount(account);
   var date = java.time.LocalDate.now();
   var call = new AccountTransaction(date.atTime(12,0),"EUR",200000,security,AccountTransaction.Type.CAPITAL_CALL); account.addTransaction(call);
   var forecast = new java.util.ArrayList<Long>(java.util.Collections.nCopies(8,0L)); forecast.set(date.getYear()-2026,300000L);
   Commitments.save(client,security,1000000L,forecast);
   var converter = new CurrencyConverterImpl(new ExchangeRateProviderFactory(client),"EUR");
   for(boolean apply : new boolean[]{false,true}) {
    var dialog = new CommitmentDialog(shell,client,converter); dialog.create(); dialog.getShell().layout(true,true);
    var field = CommitmentDialog.class.getDeclaredField("table"); field.setAccessible(true); var table = (Table)field.get(dialog);
    var headers = java.util.Arrays.stream(table.getColumns()).map(TableColumn::getText).toList();
    if(!headers.contains(date.getYear()+" YTD") || !headers.contains(date.getYear()+" restant") || !headers.contains("2033 FYe")) throw new AssertionError(headers);
    if(table.getItemCount()!=2) throw new AssertionError("Missing fund or total");
    var row=table.getItem(1);
    if(!row.getText(headers.indexOf(date.getYear()+" YTD")).equals(Values.Amount.format(200000L))) throw new AssertionError("YTD display");
    if(!row.getText(headers.indexOf(date.getYear()+" restant")).equals(Values.Amount.format(300000L))) throw new AssertionError("Remaining display");
    if(!row.getText(headers.indexOf(date.getYear()+" FYe")).equals(Values.Amount.format(500000L))) throw new AssertionError("FYe conversion");
    if(apply) { var ok=CommitmentDialog.class.getDeclaredMethod("okPressed"); ok.setAccessible(true); ok.invoke(dialog); }
    else { dialog.close(); if(Commitments.usesFullYear(client,security)) throw new AssertionError("Cancel mutated model"); }
   }
   if(!Commitments.usesFullYear(client,security)) throw new AssertionError("Migration not saved");
   call.setAmount(250000);
   var row=Commitments.row(client,security,converter,date);
   if(row.ytd()!=250000 || row.yearRemaining()!=250000 || row.fullYear().get(date.getYear()-2026)!=500000) throw new AssertionError("Post migration recalculation");
   shell.dispose();
   System.out.println("COMMITMENT_YEARS_UI_PASS: headers, amounts, totals, cancel, migration and recalculation; no portfolio opened");
  } finally { display.dispose(); }
  return EXIT_OK;
 }
 public void stop() {}
}''')
    subprocess.run([str(java/'javac'), '-cp', classpath, '-d', str(work), str(source)], check=True)
    bundle = work / 'probe.jar'
    with zipfile.ZipFile(bundle, 'w') as archive:
        archive.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\nBundle-ManifestVersion: 2\nBundle-SymbolicName: probe.commitmentyears;singleton:=true\nBundle-Version: 1.0.0\nFragment-Host: name.abuchen.portfolio.ui\nRequire-Bundle: org.eclipse.equinox.app\n\n')
        archive.writestr('fragment.xml', '<fragment><extension point="org.eclipse.core.runtime.applications" id="check"><application thread="main"><run class="name.abuchen.portfolio.ui.commitments.CommitmentYearsCheck"/></application></extension></fragment>')
        entry = 'name/abuchen/portfolio/ui/commitments/CommitmentYearsCheck.class'
        archive.write(work / entry, entry)
    base = args.eclipse.resolve()
    config = work / 'configuration'
    shutil.copytree(base / 'configuration', config)
    with (config / 'org.eclipse.equinox.simpleconfigurator/bundles.info').open('a') as info:
        info.write(f'\nprobe.commitmentyears,1.0.0,{bundle.as_uri()},4,false\n')
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

    if "COMMITMENT_YEARS_UI_PASS" not in result.stdout:
        raise SystemExit(1)
