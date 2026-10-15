"""Check Calculs and exception rendering using a temporary copy of a supplied PE portfolio."""
from pathlib import Path
import argparse,shutil,subprocess,sys,tempfile,zipfile
p=argparse.ArgumentParser();p.add_argument('eclipse',type=Path);p.add_argument('--java-home',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--portfolio',type=Path,required=True);p.add_argument('--fx-cache',type=Path);args=p.parse_args()
args.live=args.refresh_dbi=args.refresh_invesco=args.currency_proposals=False
args.duration_pdf=args.prepare_portfolio=None
args.output.mkdir(parents=True,exist_ok=True)
base=args.eclipse.resolve();java=args.java_home.resolve()/'bin';classpath=':'.join(str(x) for x in (base/'plugins').glob('*.jar'))
with tempfile.TemporaryDirectory(prefix='calculations-ui-') as directory:
    work=Path(directory);source=work/'CalculationsCheck.java';source.write_text('package name.abuchen.portfolio.ui.views;\nimport java.time.*;\nimport java.nio.file.*;\nimport org.eclipse.swt.*;\nimport org.eclipse.swt.widgets.*;\nimport org.eclipse.swt.layout.*;\nimport org.eclipse.swt.graphics.*;\nimport org.eclipse.e4.core.contexts.*;\nimport org.eclipse.e4.ui.services.IStylingEngine;\nimport org.eclipse.jface.preference.IPreferenceStore;\nimport name.abuchen.portfolio.model.*;\nimport name.abuchen.portfolio.money.*;\nimport name.abuchen.portfolio.snapshot.*;\nimport name.abuchen.portfolio.ui.editor.*;\nimport name.abuchen.portfolio.ui.selection.*;\npublic class CalculationsCheck implements org.eclipse.equinox.app.IApplication {\n static void set(Object o,Class<?> c,String n,Object v)throws Exception{var f=c.getDeclaredField(n);f.setAccessible(true);f.set(o,v);}\n static Object get(Object o,Class<?> c,String n)throws Exception{var f=c.getDeclaredField(n);f.setAccessible(true);return f.get(o);}\n static void capture(Shell shell,String name)throws Exception{\n  shell.layout(true,true);shell.update();var d=shell.getDisplay();long until=System.nanoTime()+300_000_000L;\n  while(System.nanoTime()<until){if(!d.readAndDispatch())Thread.sleep(5);}\n  var r=shell.getClientArea();var image=new Image(d,r.width,r.height);var gc=new GC(shell);gc.copyArea(image,0,0);gc.dispose();\n  var loader=new ImageLoader();loader.data=new ImageData[]{image.getImageData()};loader.save(System.getProperty("probe.output")+"/"+name+".png",SWT.IMAGE_PNG);image.dispose();\n }\n public Object start(org.eclipse.equinox.app.IApplicationContext app)throws Exception{\n  var display=new Display();\n  try{\n   var client=ClientFactory.load(java.nio.file.Path.of(System.getProperty("probe.portfolio")).toFile(),null,new org.eclipse.core.runtime.NullProgressMonitor());\n   long flows=client.getAccounts().stream().flatMap(a->a.getTransactions().stream()).filter(t->t.getType().isCapitalFlow()).count();\n   if(flows==0)throw new AssertionError("No PE transactions in reproduction");\n   var ctor=ClientInput.class.getDeclaredConstructor(String.class,java.io.File.class);ctor.setAccessible(true);var input=ctor.newInstance("Calculation check",null);set(input,ClientInput.class,"client",client);\n   var part=new PortfolioPart();set(part,PortfolioPart.class,"clientInput",input);set(part,PortfolioPart.class,"selectedPeriod",new ReportingPeriod.FromXtoY(LocalDate.of(2025,12,31),LocalDate.now()));\n   var context=EclipseContextFactory.getServiceContext(org.osgi.framework.FrameworkUtil.getBundle(CalculationsCheck.class).getBundleContext()).createChild("calculation-test");\n   var styling=(IStylingEngine)java.lang.reflect.Proxy.newProxyInstance(IStylingEngine.class.getClassLoader(),new Class[]{IStylingEngine.class},(p,m,a)->null);\n   var factory=new ExchangeRateProviderFactory(client);set(input,ClientInput.class,"exchangeRateProviderFacory",factory);\n   context.set(Client.class,client);context.set(name.abuchen.portfolio.ui.UIConstants.Context.ACTIVE_CLIENT,client);context.set(PortfolioPart.class,part);context.set(IPreferenceStore.class,input.getPreferenceStore());context.set(IStylingEngine.class,styling);context.set(ExchangeRateProviderFactory.class,factory);context.set(SelectionService.class,new SelectionService());\n   var view=ContextInjectionFactory.make(PerformanceView.class,context);context.set(AbstractFinanceView.class,view);\n   var shell=new Shell(display);shell.setText("Calculs — vérification V14");shell.setSize(1180,800);shell.setLayout(new FillLayout());\n   view.createViewControl(shell,true);shell.open();capture(shell,"calculations");\n   var tree=(org.eclipse.jface.viewers.TreeViewer)get(view,PerformanceView.class,"calculation");\n   if(tree.getTree().getItemCount()==0)throw new AssertionError("Empty calculations");\n   var accounts=(org.eclipse.jface.viewers.TableViewer)get(view,PerformanceView.class,"earningsByAccount");\n   if(accounts.getInput()==null)throw new AssertionError("Account earnings missing");\n   for(boolean fifo:new boolean[]{false,true})for(boolean preTax:new boolean[]{true,false}){\n    set(view,PerformanceView.class,"useFifo",fifo);set(view,PerformanceView.class,"preTax",preTax);view.reportingPeriodUpdated();\n   }\n   System.out.println("CALCULATIONS_UI_PASS: "+flows+" PE flows; calculations, account earnings, FIFO/average, before/after tax");\n   shell.dispose();context.dispose();\n   for(Exception error:new Exception[]{new UnsupportedOperationException(),new IllegalArgumentException(""),new IllegalArgumentException("Erreur explicite")}){\n    var exception=new ExceptionView();exception.init(error);\n    if(exception.getDefaultTitle()==null||exception.getDefaultTitle().isBlank())throw new AssertionError("Missing fallback title");\n    if(error.getMessage()!=null&&!error.getMessage().isBlank()&&!error.getMessage().equals(exception.getDefaultTitle()))throw new AssertionError("Message changed");\n    var errorShell=new Shell(display);errorShell.setLayout(new FillLayout());errorShell.setSize(800,500);\n    var header=AbstractFinanceView.class.getDeclaredMethod("createHeader",Composite.class);header.setAccessible(true);header.invoke(exception,errorShell);\n    exception.createBody(errorShell);errorShell.open();capture(errorShell,"exception-fallback");errorShell.dispose();\n   }\n   System.out.println("EXCEPTION_UI_PASS: missing, empty and explicit messages render without a second error");\n  }finally{display.dispose();}\n  return EXIT_OK;\n }\n public void stop(){}\n}\n')
    compilation = subprocess.run([str(java / 'javac'), '-cp', classpath, '-d', str(work), str(source)])
    if compilation.returncode:
        raise SystemExit(compilation.returncode)
    bundle = work / 'probe.jar'
    with zipfile.ZipFile(bundle, 'w') as archive:
        archive.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\nBundle-ManifestVersion: 2\nBundle-SymbolicName: probe.calculations;singleton:=true\nBundle-Version: 1.0.0\nFragment-Host: name.abuchen.portfolio.ui\nRequire-Bundle: org.eclipse.equinox.app,name.abuchen.portfolio.pdfbox3\n\n')
        archive.writestr('fragment.xml', '<fragment><extension point="org.eclipse.core.runtime.applications" id="check"><application thread="main"><run class="name.abuchen.portfolio.ui.views.CalculationsCheck"/></application></extension></fragment>')
        for item in work.rglob('*.class'):
            archive.write(item, str(item.relative_to(work)))
    config = work / 'configuration'
    shutil.copytree(base / 'configuration', config)
    with (config / 'org.eclipse.equinox.simpleconfigurator/bundles.info').open('a') as info:
        info.write(f'\nprobe.calculations,1.0.0,{bundle.as_uri()},4,false\n')
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
    if result.returncode or 'EXCEPTION_UI_PASS' not in result.stdout:
        raise SystemExit(result.returncode or 1)
