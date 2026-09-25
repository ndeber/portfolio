"""Exercise final SWTChart slice colors with synthetic data; opens no portfolio."""
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
with tempfile.TemporaryDirectory(prefix='allocation-colors-') as directory:
    work = Path(directory)
    source = work / 'AllocationChartCheck.java'
    source.write_text('''package name.abuchen.portfolio.ui.views.dashboard;
import java.util.List;
import org.eclipse.swt.widgets.*;
import org.eclipse.swtchart.*;
import org.eclipse.swtchart.ISeries.SeriesType;
import name.abuchen.portfolio.money.Money;
import name.abuchen.portfolio.ui.util.Colors;
import name.abuchen.portfolio.ui.util.chart.CircularChart;
import name.abuchen.portfolio.util.ColorConversion;
public class AllocationChartCheck implements org.eclipse.equinox.app.IApplication {
 public Object start(org.eclipse.equinox.app.IApplicationContext context) {
  var display = new Display();
  try {
   var shell = new Shell(display);
   var chart = new CircularChart(shell, SeriesType.PIE);
   var slices = List.of(
    new TaxonomySublevelWidget.Slice("cash", "Cash", "#006D77", Money.of("EUR",20000), .1, List.of()),
    new TaxonomySublevelWidget.Slice("bonds", "Bonds", "#123ABC", Money.of("EUR",30000), .3, List.of()),
    new TaxonomySublevelWidget.Slice("stocks", "Stocks", "#E89231", Money.of("EUR",50000), .6, List.of()));
   var previous = (ICircularSeries<?>)chart.getSeriesSet().createSeries(SeriesType.PIE, "before");
   for (var slice : slices) {
    previous.getRootNode().addChild(slice.id(), slice.actual().getAmount());
    previous.setColor(slice.id(), Colors.getColor(ColorConversion.hex2RGB(slice.color())));
   }
   if(previous.getRootNode().getChildren().getFirst().getSliceColor().getRGB().equals(ColorConversion.hex2RGB(slices.getFirst().color())))
    throw new AssertionError("Expected to reproduce SWTChart color reset");
   chart.getSeriesSet().deleteSeries("before");
   for (boolean target : new boolean[] {false, true, false}) {
    var series = (ICircularSeries<?>)chart.getSeriesSet().createSeries(SeriesType.PIE, "allocation");
    TaxonomySublevelWidget.populateSeries(series, slices, target);
    chart.updateAngleBounds();
    for (var node : series.getRootNode().getChildren()) {
     var slice = (TaxonomySublevelWidget.Slice)node.getData();
     if(!node.getSliceColor().getRGB().equals(ColorConversion.hex2RGB(slice.color())))
      throw new AssertionError("Incorrect rendered color for " + slice.name() + " target=" + target);
    }
    chart.getSeriesSet().deleteSeries("allocation");
   }
   shell.dispose();
   System.out.println("ALLOCATION_CHART_COLORS_PASS: old failure reproduced; actual, target and refresh preserve every taxonomy RGB");
  } finally { display.dispose(); }
  return EXIT_OK;
 }
 public void stop() {}
}''')
    subprocess.run([str(java/'javac'), '-cp', classpath, '-d', str(work), str(source)], check=True)
    bundle = work / 'probe.jar'
    with zipfile.ZipFile(bundle, 'w') as archive:
        archive.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\nBundle-ManifestVersion: 2\nBundle-SymbolicName: probe.allocation;singleton:=true\nBundle-Version: 1.0.0\nFragment-Host: name.abuchen.portfolio.ui\nRequire-Bundle: org.eclipse.equinox.app\n\n')
        archive.writestr('fragment.xml', '<fragment><extension point="org.eclipse.core.runtime.applications" id="check"><application thread="main"><run class="name.abuchen.portfolio.ui.views.dashboard.AllocationChartCheck"/></application></extension></fragment>')
        entry = 'name/abuchen/portfolio/ui/views/dashboard/AllocationChartCheck.class'
        archive.write(work / entry, entry)
    base = args.eclipse.resolve()
    config = work / 'configuration'
    shutil.copytree(base / 'configuration', config)
    with (config / 'org.eclipse.equinox.simpleconfigurator/bundles.info').open('a') as info:
        info.write(f'\nprobe.allocation,1.0.0,{bundle.as_uri()},4,false\n')
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
