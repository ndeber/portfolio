#!/usr/bin/env python3
"""Prepare the ignored public OAuth resource from an installed official Mac app."""
import argparse
from pathlib import Path

from oauth_config import RESOURCE, read_bundled_configuration, upstream_checksum, validate_configuration


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--from-app", required=True, type=Path,
                        help="official PortfolioPerformance.app, not a user profile or portfolio")
    args = parser.parse_args()
    repository = Path(__file__).resolve().parent.parent
    try:
        data = read_bundled_configuration(args.from_app)
        validate_configuration(data, upstream_checksum(repository))
    except (OSError, ValueError) as exc:
        parser.error(str(exc))
    destination = repository / "name.abuchen.portfolio/src" / RESOURCE
    destination.write_bytes(data)
    print("Public OAuth configuration verified and prepared. No user credentials were copied.")
    print("Run a clean Maven build before packaging. This resource remains excluded from Git.")


if __name__ == "__main__":
    main()
