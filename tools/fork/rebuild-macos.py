#!/usr/bin/env python3
"""Rebuild the tagged Apple Silicon fork from its archived, verified build inputs."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import sys
import tarfile
import tempfile
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

REPOSITORY = Path(__file__).resolve().parents[2]
RELEASE_FILE = Path(__file__).with_name("build-2026-10-15.json")


def digest(path):
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def verify_sources(release):
    manifest = REPOSITORY / release["source_manifest"]
    if digest(manifest) != release["source_manifest_sha256"]:
        raise ValueError("Source manifest checksum mismatch")
    expected = json.loads(manifest.read_text())
    expected.update(release["build_metadata_overrides"])
    failures = [name for name, checksum in expected.items()
                if not (REPOSITORY / name).is_file()
                or digest(REPOSITORY / name) != checksum]
    if failures:
        raise ValueError("Source snapshot mismatch: " + ", ".join(failures[:15]))
    return len(expected)


def extract_environment(archive, destination):
    destination = destination.resolve()
    with tarfile.open(archive, "r:gz") as bundle:
        for member in bundle.getmembers():
            target = (destination / member.name).resolve()
            if target != destination and destination not in target.parents:
                raise ValueError("Archive path escapes the build directory")
            if member.issym() or member.islnk():
                base = target.parent if member.issym() else destination
                linked = (base / member.linkname).resolve()
                if linked != destination and destination not in linked.parents:
                    raise ValueError("Archive link escapes the build directory")
            if member.isdev() or member.isfifo():
                raise ValueError("Unsupported special file in build archive")
        bundle.extractall(destination)


def prepare_environment(release, cache, supplied_archive):
    cache.mkdir(parents=True, exist_ok=True)
    environment = cache / "environment"
    marker = environment / "archive.sha256"
    expected = release["environment_sha256"]
    if marker.is_file() and marker.read_text().strip() == expected:
        return environment
    if environment.exists():
        raise ValueError("Use a new cache directory: an unverified environment already exists")
    archive = supplied_archive or cache / release["environment_asset"]
    if not archive.is_file():
        if supplied_archive:
            raise FileNotFoundError(archive)
        url = ("https://github.com/" + release["github_repository"]
               + "/releases/download/" + release.get("environment_tag", release["tag"]) + "/" + release["environment_asset"])
        print("Downloading the archived build environment (about 610 MB)...", flush=True)
        request = urllib.request.Request(url, headers={"User-Agent": "PortfolioPerformanceForkRebuild"})
        temporary = archive.with_suffix(archive.suffix + ".part")
        with urllib.request.urlopen(request, timeout=120) as response, temporary.open("wb") as output:
            shutil.copyfileobj(response, output)
        temporary.replace(archive)
    if digest(archive) != expected:
        raise ValueError("Build environment checksum mismatch")
    print("Extracting the verified JDK, Maven and dependency cache...", flush=True)
    with tempfile.TemporaryDirectory(prefix="unpack-", dir=cache) as directory:
        staged = Path(directory) / "environment"
        staged.mkdir()
        extract_environment(archive, staged)
        (staged / "archive.sha256").write_text(expected + "\n")
        staged.rename(environment)
    return environment


def run_logged(command, log, environment):
    log.parent.mkdir(parents=True, exist_ok=True)
    with log.open("w") as output:
        result = subprocess.run([str(value) for value in command], cwd=REPOSITORY,
                                env=environment, stdout=output, stderr=subprocess.STDOUT)
    if result.returncode:
        print("\n".join(log.read_text(errors="replace").splitlines()[-65:]), file=sys.stderr)
        raise RuntimeError("Build step failed; details: " + str(log))


def source_commit():
    # GitHub's source archives have no .git directory; their content is already
    # authenticated by the release tag and the complete source manifest above.
    if not (REPOSITORY / ".git").exists():
        return None
    return subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=REPOSITORY, text=True).strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path, help="new delivery directory")
    parser.add_argument("--cache-dir", type=Path, default=REPOSITORY / ".rebuild")
    parser.add_argument("--release-file", type=Path, default=RELEASE_FILE,
                        help="build metadata for a published source snapshot (defaults to the original release)")
    parser.add_argument("--environment-archive", type=Path,
                        help="optional already-downloaded build environment archive")
    args = parser.parse_args()
    if sys.platform != "darwin" or platform.machine() != "arm64":
        parser.error("Run with native Python 3 on a Mac with Apple Silicon")
    output = args.output.resolve()
    if output.exists():
        parser.error("Use a new output directory; refusing to overwrite an existing delivery")
    release = json.loads(args.release_file.read_text())
    count = verify_sources(release)
    print("Verified source snapshot:", count, "files", flush=True)
    cache = args.cache_dir.resolve()
    archive = args.environment_archive.resolve() if args.environment_archive else None
    build = prepare_environment(release, cache, archive)
    jdk = build / release["jdk_directory"] / "Contents/Home"
    maven = build / release["maven_directory"] / "bin/mvn"
    environment = dict(os.environ)
    environment["JAVA_HOME"] = str(jdk)
    environment["PATH"] = str(jdk / "bin") + os.pathsep + environment.get("PATH", "")

    helper_path = REPOSITORY / "private-equity-product/oauth_config.py"
    spec = importlib.util.spec_from_file_location("fork_oauth_config", helper_path)
    helper = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(helper)
    public_config = (build / "resources/quote-provider-config.json").read_bytes()
    helper.validate_configuration(public_config, helper.upstream_checksum(REPOSITORY))
    (REPOSITORY / "name.abuchen.portfolio/src" / helper.RESOURCE).write_bytes(public_config)

    logs = cache / "logs"
    print("Compiling offline and running the fork regression tests...", flush=True)
    run_logged([maven, "-B", "-ntp", "-f", "portfolio-app/pom.xml",
                "-Dmaven.repo.local=" + str(build / "m2"), "-o", "clean", "verify",
                "-Pprivate-equity,local-dev", "-DforceContextQualifier=" + release["qualifier"],
                "-Dtest=" + ",".join(release["test_classes"]), "-DfailIfNoTests=false",
                "-Dsurefire.failIfNoSpecifiedTests=false"], logs / "Compilation.log", environment)
    totals = {key: 0 for key in ("tests", "failures", "errors", "skipped")}
    for report in REPOSITORY.glob("*/target/surefire-reports/TEST-*.xml"):
        suite = ET.parse(report).getroot()
        for key in totals:
            totals[key] += int(suite.attrib.get(key, 0))
    if totals != {"tests": release["expected_tests"], "failures": 0, "errors": 0, "skipped": 0}:
        raise ValueError("Unexpected regression test results: " + repr(totals))
    run_logged([sys.executable, "-m", "unittest", "discover", "-s", "private-equity-product",
                "-p", "test_*.py", "-v"], logs / "Packaging-tests.log", environment)
    print("Packaging the standalone application...", flush=True)
    run_logged([sys.executable, "private-equity-product/package-macos.py", output,
                "--java-home", jdk, "--branding", "fork-branding"], logs / "Packaging.log", environment)
    app = output / "PortfolioPerformancePE.app"
    run_logged([sys.executable, "private-equity-product/check-packaged-pdf.py",
                app / "Contents/Eclipse", "--java-home", app / "Contents/runtime",
                *release.get("packaged_checks", ["--commitments", "--inflation"])], logs / "Packaged-runtime.log", environment)
    run_logged(["codesign", "--verify", "--deep", "--strict", app], logs / "Signature.log", environment)
    delivered_zip = output / "PortfolioPerformancePE-mac-arm64.zip"
    with zipfile.ZipFile(delivered_zip) as bundle:
        if bundle.testzip() is not None:
            raise ValueError("Application ZIP integrity check failed")
    verify_sources(release)
    validation = output / "Validation"
    validation.mkdir()
    for log in logs.glob("*.log"):
        shutil.copy2(log, validation / log.name)
    report = {"tag": release["tag"], "commit": source_commit(), "source_files_verified": count,
              "tests": totals, "packaging_tests": 6, "offline_build": True,
              "environment_sha256": release["environment_sha256"],
              "application_zip_sha256": digest(delivered_zip), "signature_verified": True,
              "packaged_runtime_verified": True}
    (validation / "Rebuild.json").write_text(json.dumps(report, indent=2) + "\n")
    print("Rebuild verified:", delivered_zip)


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, RuntimeError) as error:
        raise SystemExit(str(error))
