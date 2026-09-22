#!/usr/bin/env python3
"""Bundle the built Apple Silicon product and a Java 21 runtime into a ZIP."""
import argparse
import json
import os
from pathlib import Path
import plistlib
import shutil
import subprocess

from oauth_config import validate_application

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("output", type=Path, help="new output directory")
parser.add_argument("--java-home", type=Path, default=os.environ.get("JAVA_HOME"))
parser.add_argument("--branding", type=Path, help="optional branding directory containing manifest.json")
args = parser.parse_args()
if not args.java_home or not (args.java_home / "bin/jlink").exists():
    parser.error("Set JAVA_HOME to a macOS Apple Silicon JDK 21")
module = Path(__file__).resolve().parent
products = list((module / "target/products").glob("**/Contents/Info.plist"))
if len(products) != 1:
    parser.error("Build the private-equity Maven profile first (one macOS product expected)")
# Check the built artifact, not just the source resource: an incremental build
# may still contain an old bundle. Fail before creating any delivery files.
try:
    validate_application(products[0].parent.parent, module.parent)
except (OSError, ValueError) as exc:
    parser.error(str(exc))
args.output.mkdir(parents=True, exist_ok=True)
app = args.output.resolve() / "PortfolioPerformancePE.app"
if app.exists():
    parser.error(f"Refusing to overwrite {app}; use a new output directory")
shutil.copytree(products[0].parent.parent, app, symlinks=True)
runtime = app / "Contents/runtime"
subprocess.run([str(args.java_home / "bin/jlink"), "--add-modules", "ALL-MODULE-PATH",
                "--strip-debug", "--no-header-files", "--no-man-pages", "--compress=zip-6",
                "--output", str(runtime)], check=True)
# Keep the JDK distribution's license and notices with its linked runtime.
for name in ("NOTICE", "LICENSE"):
    if (args.java_home / name).exists():
        shutil.copy2(args.java_home / name, runtime / name)
ini = app / "Contents/Eclipse/PortfolioPerformancePE.ini"
text = ini.read_text()
if "\n-vm\n" in text:
    parser.error("Product already contains a VM selection; inspect it before packaging")
ini.write_text(text.replace("-vmargs\n", "-vm\n../runtime/lib/libjli.dylib\n-vmargs\n"))
plist_path = app / "Contents/Info.plist"
with plist_path.open("rb") as stream:
    plist = plistlib.load(stream)
plist["CFBundleIdentifier"] = "name.abuchen.portfolio.pe"
plist["CFBundleName"] = "Portfolio Performance PE"
plist["CFBundleDisplayName"] = "Portfolio Performance PE"
if args.branding:
    branding = json.loads((args.branding / "manifest.json").read_text())
    icon = app / "Contents/Resources" / plist["CFBundleIconFile"]
    shutil.copy2(args.branding / branding["icon"], icon)
    if branding.get("splash"):
        # Resolve from the installation root, so Finder moves and ZIP extraction
        # do not leave an absolute build-machine path in the shipped application.
        splash_dir = app / "Contents/Eclipse/branding"
        splash_dir.mkdir()
        shutil.copy2(args.branding / branding["splash"], splash_dir / "splash.bmp")
        config = app / "Contents/Eclipse/configuration/config.ini"
        lines = [line for line in config.read_text().splitlines()
                 if not line.startswith(("osgi.splashPath=", "osgi.splashLocation="))]
        lines.append("osgi.splashPath=platform:/base/branding")
        config.write_text("\n".join(lines) + "\n")
    with ini.open("a") as stream:
        for key, value in branding.get("javaProperties", {}).items():
            stream.write(f"-D{key}={value}\n")
with plist_path.open("wb") as stream:
    plistlib.dump(plist, stream)
# Ad-hoc signing allows local execution; it is not Apple Developer signing or notarization.
subprocess.run(["codesign", "--force", "--deep", "--sign", "-", str(app)], check=True)
subprocess.run(["codesign", "--verify", "--deep", "--strict", str(app)], check=True)
archive = args.output.resolve() / "PortfolioPerformancePE-mac-arm64.zip"
subprocess.run(["ditto", "-c", "-k", "--sequesterRsrc", "--keepParent", str(app), str(archive)], check=True)
# Examples and the combined guide belong to the integration branch. Packaging
# the platform by itself must also work without any optional feature installed.
for source, name in [(module / "examples/PE-Demo.xml", "PE-Demo.xml"),
                     (module / "examples/PE-Demo.portfolio", "PE-Demo.portfolio"),
                     (module.parent / "PRIVATE_EQUITY.md", "Guide.md")]:
    if source.exists():
        shutil.copy2(source, args.output / name)
documentation = module.parent / "docs/fork"
if documentation.exists():
    shutil.copytree(documentation, args.output / "docs/fork", dirs_exist_ok=True)
print(archive)
