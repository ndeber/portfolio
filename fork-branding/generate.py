#!/usr/bin/env python3
"""Recolor the upstream vector logo and render reproducible app/UI icons."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--java-home", type=Path, default=os.environ.get("JAVA_HOME"), required=False)
parser.add_argument("--jsvg", type=Path, required=True, help="JSVG 2.1.0 JAR from the Maven target platform")
args = parser.parse_args()
if not args.java_home:
    parser.error("Set JAVA_HOME to JDK 21")
here = Path(__file__).resolve().parent
repo = here.parent
source = (repo / "portfolio-product/icons/logo.svg").read_text()
palette = {
    "rgb(163,197,71)": "rgb(124,58,237)",
    "rgb(168,202,95)": "rgb(155,81,255)",
    "rgb(245,160,0)": "rgb(255,45,130)",
    "rgb(0,129,161)": "rgb(0,221,255)",
    "rgb(0,127,158)": "rgb(0,208,255)",
    "rgb(0,122,151)": "rgb(0,189,250)",
    "rgb(0,117,143)": "rgb(0,164,240)",
}
for old, new in palette.items():
    if source.count(old) != 1:
        raise ValueError(f"Upstream logo changed; review the palette mapping for {old}")
    source = source.replace(old, new)
(here / "logo.svg").write_text(source)
java = args.java_home.resolve() / "bin"
with tempfile.TemporaryDirectory(prefix="portfolio-branding-") as tmp:
    work = Path(tmp)
    subprocess.run([str(java / "javac"), "-cp", str(args.jsvg.resolve()), "-d", str(work),
                    str(here / "RenderLogo.java")], check=True)
    def render(destination, size):
        subprocess.run([str(java / "java"), "-Djava.awt.headless=true", "-cp",
                        str(work) + os.pathsep + str(args.jsvg.resolve()), "RenderLogo",
                        str(here / "logo.svg"), str(destination), str(size)], check=True)
    icons = repo / "name.abuchen.portfolio.ui/icons/fork-vivid"
    for size in (16, 32, 48, 64, 128, 256, 512):
        render(icons / f"pp_{size}.png", size)
        render(icons / f"pp_{size}@2x.png", size * 2)
    render(here / "preview.png", 512)
    iconset = work / "VividPE.iconset"
    iconset.mkdir()
    for size in (16, 32, 128, 256, 512):
        for suffix in ("", "@2x"):
            shutil.copy2(icons / f"pp_{size}{suffix}.png", iconset / f"icon_{size}x{size}{suffix}.png")
    subprocess.run(["iconutil", "-c", "icns", "-o", str(here / "app.icns"), str(iconset)], check=True)
