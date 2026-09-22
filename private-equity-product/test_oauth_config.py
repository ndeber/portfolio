"""Offline packaging regressions using synthetic public configuration only."""
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from zipfile import ZipFile

from oauth_config import (RESOURCE, read_bundled_configuration, upstream_checksum,
                          validate_application, validate_configuration)


class OAuthPackagingTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.app = self.root / "Test.app"
        self.plugins = self.app / "Contents/Eclipse/plugins"
        self.plugins.mkdir(parents=True)
        self.config = dict(clientId="test-client", baseUrl="https://example.invalid/oidc",
                           authEndpoint="/auth", tokenEndpoint="/token",
                           revocationEndpoint="/revoke", authScope="openid offline_access",
                           apiResource="https://api.example.invalid")
        self.data = json.dumps(self.config).encode()
        self.digest = hashlib.sha256(self.data).hexdigest()
        module = self.root / "name.abuchen.portfolio"
        module.mkdir()
        (module / "pom.xml").write_text(
            '<project xmlns="http://maven.apache.org/POM/4.0.0">'
            '<condition property="checksum.ok"><equals arg1="${computed.sha256}" '
            f'arg2="{self.digest}"/></condition></project>')

    def bundle(self, data):
        with ZipFile(self.plugins / "name.abuchen.portfolio_1.0.jar", "w") as jar:
            if data is not None:
                jar.writestr(RESOURCE, data)
            jar.writestr("unrelated.txt", "not copied")

    def test_valid_built_configuration_matches_upstream(self):
        self.bundle(self.data)
        validate_application(self.app, self.root)
        self.assertEqual(self.digest, upstream_checksum(self.root))
        self.assertEqual(self.data, read_bundled_configuration(self.app))

    def test_missing_configuration_in_built_bundle_is_rejected(self):
        self.bundle(None)
        with self.assertRaisesRegex(ValueError, "missing from the application"):
            validate_application(self.app, self.root)

    def test_incompatible_or_modified_configuration_is_rejected(self):
        self.bundle(self.data + b" ")
        with self.assertRaisesRegex(ValueError, "checksum required"):
            validate_application(self.app, self.root)

    def test_missing_or_ambiguous_core_bundle_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "exactly one"):
            read_bundled_configuration(self.app)
        self.bundle(self.data)
        (self.plugins / "name.abuchen.portfolio_2.0.jar").touch()
        with self.assertRaisesRegex(ValueError, "exactly one"):
            read_bundled_configuration(self.app)

    def test_credentials_and_empty_fields_are_rejected(self):
        for config in [dict(self.config, refresh_token="test-value"), dict(self.config, clientId="")]:
            data = json.dumps(config).encode()
            with self.assertRaises(ValueError):
                validate_configuration(data, hashlib.sha256(data).hexdigest())

    def test_missing_upstream_checksum_requires_review(self):
        (self.root / "name.abuchen.portfolio/pom.xml").write_text("<project/>")
        with self.assertRaisesRegex(ValueError, "review the upstream"):
            upstream_checksum(self.root)


if __name__ == "__main__":
    unittest.main()
