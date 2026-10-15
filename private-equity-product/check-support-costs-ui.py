"""Check the packaged native widget on an isolated workspace and portfolio copy."""
from pathlib import Path
import argparse,shutil,subprocess,tempfile,zipfile
p=argparse.ArgumentParser();p.add_argument('eclipse',type=Path);p.add_argument('--java-home',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--portfolio',type=Path,required=True);p.add_argument('--fx-cache',type=Path,required=True);args=p.parse_args()
base=args.eclipse.resolve();java=args.java_home.resolve()/'bin';args.output.mkdir(parents=True,exist_ok=True)
cp=':'.join(str(x) for x in (base/'plugins').glob('*.jar'))
with tempfile.TemporaryDirectory(prefix='support-costs-ui-') as directory:
    work=Path(directory);source=Path(__file__).with_name('SupportCostsUiCheck.java')
    compiled=subprocess.run([str(java/'javac'),'-cp',cp,'-d',str(work),str(source)])
    if compiled.returncode:raise SystemExit(compiled.returncode)
    bundle=work/'probe.jar'
    with zipfile.ZipFile(bundle,'w') as z:
        z.writestr('META-INF/MANIFEST.MF','Manifest-Version: 1.0\nBundle-ManifestVersion: 2\nBundle-SymbolicName: probe.supportcosts;singleton:=true\nBundle-Version: 1.0.0\nFragment-Host: name.abuchen.portfolio.ui\nRequire-Bundle: org.eclipse.equinox.app\n\n')
        z.writestr('fragment.xml','<fragment><extension point="org.eclipse.core.runtime.applications" id="check"><application thread="main"><run class="name.abuchen.portfolio.ui.views.dashboard.SupportCostsUiCheck"/></application></extension></fragment>')
        for item in work.rglob('*.class'):z.write(item,str(item.relative_to(work)))
    config=work/'configuration';shutil.copytree(base/'configuration',config)
    with (config/'org.eclipse.equinox.simpleconfigurator/bundles.info').open('a') as f:f.write(f'\nprobe.supportcosts,1.0.0,{bundle.as_uri()},4,false\n')
    workspace=work/'workspace';dest=workspace/'.metadata/.plugins/name.abuchen.portfolio/ecb_exchange_rates.pb';dest.parent.mkdir(parents=True);shutil.copy2(args.fx_cache,dest)
    custom_css=workspace/".metadata/.plugins/name.abuchen.portfolio.ui/custom.css";custom_css.parent.mkdir(parents=True,exist_ok=True);custom_css.write_text("")
    copied=work/args.portfolio.name;shutil.copy2(args.portfolio,copied)
    launcher=next((base/'plugins').glob('org.eclipse.equinox.launcher_*.jar'))
    cmd=[str(java/'java'),'-XstartOnFirstThread',f'-Dprobe.output={args.output.resolve()}',f'-Dprobe.portfolio={copied}','-jar',str(launcher),'-nosplash','-install',str(base),'-configuration',str(config),'-data',str(workspace),'-application','name.abuchen.portfolio.ui.check','-consoleLog']
    r=subprocess.run(cmd,capture_output=True,text=True,timeout=60)
    print(r.stdout+r.stderr)
    if r.returncode or 'SUPPORT_COSTS_UI_PASS' not in r.stdout:raise SystemExit(r.returncode or 1)
