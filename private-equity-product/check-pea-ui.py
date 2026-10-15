"""Verify native fiscal columns, dashboard widgets and dialogs on an isolated copy."""
from pathlib import Path
import argparse,shutil,subprocess,sys,tempfile,zipfile
p=argparse.ArgumentParser();p.add_argument('eclipse',type=Path);p.add_argument('--java-home',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--portfolio',type=Path,required=True);p.add_argument('--fx-cache',type=Path);args=p.parse_args()
args.live=args.refresh_dbi=args.refresh_invesco=args.currency_proposals=False
args.duration_pdf=args.prepare_portfolio=None
args.output.mkdir(parents=True,exist_ok=True)
base=args.eclipse.resolve();java=args.java_home.resolve()/'bin';classpath=':'.join(str(x) for x in (base/'plugins').glob('*.jar'))
with tempfile.TemporaryDirectory(prefix='pea-ui-') as directory:
    work=Path(directory);source=work/'PeaUiCheck.java';source.write_text(Path(__file__).with_name('PeaUiCheck.java').read_text())
    compilation = subprocess.run([str(java / 'javac'), '-cp', classpath, '-d', str(work), str(source)])
    if compilation.returncode:
        raise SystemExit(compilation.returncode)
    bundle = work / 'probe.jar'
    with zipfile.ZipFile(bundle, 'w') as archive:
        archive.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\nBundle-ManifestVersion: 2\nBundle-SymbolicName: probe.taxui;singleton:=true\nBundle-Version: 1.0.0\nFragment-Host: name.abuchen.portfolio.ui\nRequire-Bundle: org.eclipse.equinox.app,name.abuchen.portfolio.pdfbox3\n\n')
        archive.writestr('fragment.xml', '<fragment><extension point="org.eclipse.core.runtime.applications" id="check"><application thread="main"><run class="name.abuchen.portfolio.ui.views.PeaUiCheck"/></application></extension></fragment>')
        for item in work.rglob('*.class'):
            archive.write(item, str(item.relative_to(work)))
    config = work / 'configuration'
    shutil.copytree(base / 'configuration', config)
    with (config / 'org.eclipse.equinox.simpleconfigurator/bundles.info').open('a') as info:
        info.write(f'\nprobe.taxui,1.0.0,{bundle.as_uri()},4,false\n')
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
    result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=55)
    print(result.stdout)
    if result.returncode or 'PEA_UI_PASS' not in result.stdout:
        raise SystemExit(result.returncode or 1)
