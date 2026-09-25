from pathlib import Path
import argparse, subprocess, shutil, tempfile, zipfile
parser=argparse.ArgumentParser(description="Check PDF initialization in the packaged OSGi runtime without opening a portfolio.")
parser.add_argument("eclipse", type=Path, help="Built app Contents/Eclipse directory")
parser.add_argument("--java-home", required=True, type=Path)
parser.add_argument("--live-msci", "--live-amundi", dest="live_amundi", action="store_true", help="Also fetch public Amundi and WPEA compositions")
parser.add_argument("--live-bonds", action="store_true", help="Check only hard-coded public VAGF, M&G and Bund examples; never opens a portfolio")
parser.add_argument("--commitments", action="store_true", help="Check commitment calculations and UI loading on synthetic data, without network or portfolio files")
parser.add_argument("--inflation", action="store_true", help="Check the AFT inflation command and a synthetic update")
args=parser.parse_args()
base=args.eclipse.resolve()
workspace=tempfile.TemporaryDirectory(prefix="portfolio-pdf-check-")
probe=Path(workspace.name)
java=args.java_home.resolve()/"bin"
# A minimal local PDF exercises the same PDFBox initialization as the downloaded MSCI document.
pdf=probe/'fixture.pdf'
stream=b'BT /F1 12 Tf 20 80 Td (PDF startup verified) Tj ET'
objects=[b'<< /Type /Catalog /Pages 2 0 R >>',b'<< /Type /Pages /Kids [3 0 R] /Count 1 >>',b'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 100] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>',b'<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',b'<< /Length '+str(len(stream)).encode()+b' >>\nstream\n'+stream+b'\nendstream']
data=b'%PDF-1.4\n'; offsets=[0]
for i,obj in enumerate(objects,1):
    offsets.append(len(data)); data+=f'{i} 0 obj\n'.encode()+obj+b'\nendobj\n'
xref=len(data); data+=b'xref\n0 6\n0000000000 65535 f \n'
for offset in offsets[1:]: data+=f'{offset:010d} 00000 n \n'.encode()
data+=f'trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n'.encode();pdf.write_bytes(data)
source=probe/'Check.java'
source.write_text('''package probe;
import org.eclipse.equinox.app.*;
import org.osgi.framework.*;
import name.abuchen.portfolio.pdfbox3.PDFBox3Adapter;
public class Check implements IApplication {
 public Object start(IApplicationContext context) throws Exception {
  Bundle provider=null;
  for(Bundle b:FrameworkUtil.getBundle(Check.class).getBundleContext().getBundles())
   if(b.getSymbolicName().equals("org.apache.logging.log4j.to.slf4j")) provider=b;
  if(provider==null || provider.getState()!=Bundle.ACTIVE) throw new IllegalStateException("Provider not active at startup");
  String result=new PDFBox3Adapter().convertToText(new java.io.File(System.getProperty("probe.pdf")));
  if(!result.contains("PDF startup verified")) throw new IllegalStateException(result);
  System.out.println("PACKAGED_OSGI_PDF_PASS: provider active; PDF parsed successfully");
  if(Boolean.getBoolean("probe.live")) {
   for(String isin : new String[] {"FR0013412020", "IE0002XZSHO1"}) {
   var security=new name.abuchen.portfolio.model.Security();
   security.setIsin(isin); security.setName(isin);
   var outcomes=new name.abuchen.portfolio.updates.equity.EquitySources().fetch(security, () -> false);
   for(var outcome:outcomes) {
    if(outcome.error()!=null) throw new IllegalStateException(outcome.family()+": "+outcome.error());
    System.out.println("MSCI_SOURCE_PASS: "+isin+" / "+outcome.family()+" / "+outcome.slice().items().size()+" items");
    if(outcome.family()==name.abuchen.portfolio.updates.equity.EquityComposition.Family.HOLDINGS) {
     for(var item:outcome.slice().items()) {
      if(!item.name().equals(name.abuchen.portfolio.updates.equity.EquitySources.msciHoldingName(item.name())))
       throw new IllegalStateException("Statistics in company name: "+item.name());
      System.out.println("CONSTITUENT: "+item.name()+" / "+item.percent());
     }
    }
   }
  }
  }
  if(Boolean.getBoolean("probe.bonds")) {
   var sources=new name.abuchen.portfolio.updates.bondallocation.BondAllocationSources();
   for(String isin : new String[] {"IE00BG47KH54", "LU1041599405", "DE000BU2Z072"}) {
    var security=new name.abuchen.portfolio.model.Security(isin,"EUR"); security.setIsin(isin);
    var outcomes=sources.fetch(security,java.time.LocalDate.now(),()->false,f->{});
    for(var outcome:outcomes) {
     if(outcome.error()!=null) throw new IllegalStateException(isin+" / "+outcome.family()+": "+outcome.error());
     if(outcome.slice().weights().values().stream().mapToInt(Integer::intValue).sum()!=10000) throw new IllegalStateException("Invalid allocation");
     System.out.println("BOND_SOURCE_PASS: "+isin+" / "+outcome.family()+" / "+outcome.slice().weights());
    }
   }
   for(Bundle b:FrameworkUtil.getBundle(Check.class).getBundleContext().getBundles()) {
    if(b.getSymbolicName().equals("name.abuchen.portfolio.ui")) {
     b.loadClass("name.abuchen.portfolio.ui.updateactions.bondallocation.RefreshBondAllocationHandler").getDeclaredMethods();
     System.out.println("BOND_HANDLER_PASS");
    }
   }
  }
  if(Boolean.getBoolean("probe.commitments")) {
   var client=new name.abuchen.portfolio.model.Client(); client.setBaseCurrency("EUR");
   var security=new name.abuchen.portfolio.model.Security("Synthetic PE fund","EUR");client.addSecurity(security);
   var account=new name.abuchen.portfolio.model.Account("Synthetic account");account.setCurrencyCode("EUR");client.addAccount(account);
   account.addTransaction(new name.abuchen.portfolio.model.AccountTransaction(java.time.LocalDate.now().atStartOfDay(),"EUR",20000,security,name.abuchen.portfolio.model.AccountTransaction.Type.CAPITAL_CALL));
   name.abuchen.portfolio.commitments.Commitments.save(client,security,100000L,java.util.List.of(20000L,20000L,20000L,20000L));
   var converter=new name.abuchen.portfolio.money.CurrencyConverterImpl(new name.abuchen.portfolio.money.ExchangeRateProviderFactory(client),"EUR");
   var summary=name.abuchen.portfolio.commitments.Commitments.summary(client,converter,java.time.LocalDate.now());
   if(summary.paid()!=20000 || summary.remaining()!=80000 || !summary.complete()) throw new IllegalStateException("Incorrect commitment totals");
   account.setName("Xapa - Synthetic");
   var tax=new name.abuchen.portfolio.model.Taxonomy(name.abuchen.portfolio.commitments.Availability.NAME);
   var root=new name.abuchen.portfolio.model.Classification("root",tax.getName());tax.setRootNode(root);client.addTaxonomy(tax);
   var immediate=new name.abuchen.portfolio.model.Classification(root,"now","Immédiate");root.addChild(immediate);
   immediate.addAssignment(new name.abuchen.portfolio.model.Classification.Assignment(account));
   name.abuchen.portfolio.commitments.AvailabilityPlan.apply(client,name.abuchen.portfolio.commitments.AvailabilityPlan.load(client));
   if(client.getProperty(name.abuchen.portfolio.commitments.AvailabilityPlan.PROPERTY)==null)throw new IllegalStateException("Missing authoritative availability table");
   var availability=name.abuchen.portfolio.commitments.Availability.calculate(client,tax,converter,java.time.LocalDate.now());
   if(availability.amount(0)!=-20000)throw new IllegalStateException("Incorrect availability balance");
   for(Bundle b:FrameworkUtil.getBundle(Check.class).getBundleContext().getBundles()) {
    if(b.getSymbolicName().equals("name.abuchen.portfolio.ui")) {
     b.loadClass("name.abuchen.portfolio.ui.commitments.CommitmentHandler").getDeclaredMethods();
     b.loadClass("name.abuchen.portfolio.ui.views.dashboard.CommitmentWidget").getDeclaredMethods();
     b.loadClass("name.abuchen.portfolio.ui.views.dashboard.AvailabilityWidget").getDeclaredMethods();
     b.loadClass("name.abuchen.portfolio.ui.commitments.AvailabilityHandler").getDeclaredMethods();
     b.loadClass("name.abuchen.portfolio.ui.commitments.AvailabilityDialog").getDeclaredMethods();
     b.loadClass("name.abuchen.portfolio.ui.views.dashboard.TaxonomySublevelWidget").getDeclaredMethods();
     if(b.getEntry("model/commitments.e4xmi")==null) throw new IllegalStateException("Missing menu fragment");
     System.out.println("COMMITMENTS_PACKAGED_PASS: calculated balances, handler, widget, menu");
    }
   }
  }
  if(Boolean.getBoolean("probe.inflation")) {
   var client = new name.abuchen.portfolio.model.Client();
   var index = new name.abuchen.portfolio.model.Security(); index.setName("EU (IPCH)"); index.setFeed("MANUAL"); client.addSecurity(index);
   var today = java.time.LocalDate.now();
   var download = new name.abuchen.portfolio.updates.inflation.AftInflation.Download("https://www.aft.gouv.fr/files/test.xlsx", java.util.List.of(
    new name.abuchen.portfolio.updates.inflation.AftInflation.Point(today, 10362000000L),
    new name.abuchen.portfolio.updates.inflation.AftInflation.Point(today.plusDays(1), 10363000000L)));
   name.abuchen.portfolio.updates.inflation.AftInflation.apply(client, name.abuchen.portfolio.updates.inflation.AftInflation.prepare(index, download));
   if(index.getPrices().size()!=2)throw new IllegalStateException("Missing published future reference");
   for(Bundle b:FrameworkUtil.getBundle(Check.class).getBundleContext().getBundles())
    if(b.getSymbolicName().equals("name.abuchen.portfolio.ui")) {
     b.loadClass("name.abuchen.portfolio.ui.updateactions.inflation.UpdateInflationHandler").getDeclaredMethods();
     b.loadClass("name.abuchen.portfolio.ui.updateactions.inflation.InflationPreviewDialog").getDeclaredMethods();
     if(b.getEntry("model/aft-inflation.e4xmi")==null)throw new IllegalStateException("Missing inflation command");
    }
   System.out.println("INFLATION_PACKAGED_PASS: published daily references, command and preview loaded");
  }
  return EXIT_OK;
 }
 public void stop() {}
}''')
classes=probe/'classes';classes.mkdir(exist_ok=True)
classpath=':'.join(str(p) for p in (base/'plugins').glob('*.jar'))
subprocess.run([str(java/'javac'),'-cp',classpath,'-d',str(classes),str(source)],check=True)
bundle=probe/'probe.jar'
with zipfile.ZipFile(bundle,'w') as z:
 z.writestr('META-INF/MANIFEST.MF','Manifest-Version: 1.0\nBundle-ManifestVersion: 2\nBundle-SymbolicName: probe;singleton:=true\nBundle-Version: 1.0.0\nRequire-Bundle: org.eclipse.equinox.app,name.abuchen.portfolio.pdfbox3,name.abuchen.portfolio\nImport-Package: org.osgi.framework\n\n')
 z.writestr('plugin.xml','<plugin><extension point="org.eclipse.core.runtime.applications" id="check"><application><run class="probe.Check"/></application></extension></plugin>')
 z.write(classes/'probe/Check.class','probe/Check.class')
config=probe/'configuration';shutil.copytree(base/'configuration',config,dirs_exist_ok=True)
info=config/'org.eclipse.equinox.simpleconfigurator/bundles.info'
with info.open('a') as out:out.write(f'\nprobe,1.0.0,{bundle.as_uri()},4,true\n')
launcher=next((base/'plugins').glob('org.eclipse.equinox.launcher_*.jar'))
cmd=[str(java/'java'),f'-Dprobe.pdf={pdf}',f'-Dprobe.live={str(args.live_amundi).lower()}',f'-Dprobe.bonds={str(args.live_bonds).lower()}',f'-Dprobe.commitments={str(args.commitments).lower()}',f'-Dprobe.inflation={str(args.inflation).lower()}','-jar',str(launcher),'-nosplash','-install',str(base),'-configuration',str(config),'-data',str(probe/'workspace'),'-application','probe.check','-consoleLog']
r=subprocess.run(cmd,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,timeout=300 if (args.live_amundi or args.live_bonds) else 45)
print(r.stdout)
if r.returncode or 'PACKAGED_OSGI_PDF_PASS' not in r.stdout:raise SystemExit(1)
if args.live_amundi and r.stdout.count('MSCI_SOURCE_PASS:') != 6:raise SystemExit(1)

if args.live_bonds and (r.stdout.count("BOND_SOURCE_PASS:") != 12 or "BOND_HANDLER_PASS" not in r.stdout):raise SystemExit(1)

if args.commitments and "COMMITMENTS_PACKAGED_PASS:" not in r.stdout:raise SystemExit(1)

if args.inflation and "INFLATION_PACKAGED_PASS:" not in r.stdout:raise SystemExit(1)
