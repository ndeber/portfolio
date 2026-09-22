"""Validate the public OAuth client configuration required by the upstream code.

This module never reads user profiles, refresh tokens or credential stores.
"""
import hashlib
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET
from zipfile import ZipFile

RESOURCE = "name/abuchen/portfolio/oauth/impl/config.json"
FIELDS = {"clientId", "baseUrl", "authEndpoint", "tokenEndpoint",
          "revocationEndpoint", "authScope", "apiResource"}


def upstream_checksum(repository: Path) -> str:
    """Use the release checksum maintained in the upstream Maven configuration."""
    pom = ET.parse(repository / "name.abuchen.portfolio/pom.xml")
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    condition = pom.find(".//m:condition[@property='checksum.ok']/m:equals", ns)
    checksum = condition.get("arg2", "") if condition is not None else ""
    if not re.fullmatch(r"[0-9a-f]{64}", checksum):
        raise ValueError("Upstream OAuth checksum is missing; review the upstream build configuration")
    return checksum


def validate_configuration(data: bytes, expected_checksum: str) -> None:
    if hashlib.sha256(data).hexdigest() != expected_checksum:
        raise ValueError("OAuth configuration does not match the checksum required by this upstream version")
    config = json.loads(data)
    if not isinstance(config, dict) or set(config) != FIELDS:
        raise ValueError("Expected only the seven public OAuth configuration fields; no credentials")
    if any(not isinstance(value, str) or not value.strip() for value in config.values()):
        raise ValueError("OAuth configuration contains an empty or invalid field")


def read_bundled_configuration(app: Path) -> bytes:
    jars = list((app / "Contents/Eclipse/plugins").glob("name.abuchen.portfolio_*.jar"))
    if len(jars) != 1:
        raise ValueError("Expected exactly one core Portfolio Performance bundle in the application")
    with ZipFile(jars[0]) as bundle:
        try:
            return bundle.read(RESOURCE)
        except KeyError as exc:
            raise ValueError("OAuth configuration is missing from the application; "
                             "run prepare-oauth.py before a clean Maven build") from exc


def validate_application(app: Path, repository: Path) -> None:
    validate_configuration(read_bundled_configuration(app), upstream_checksum(repository))
