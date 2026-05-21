#!/usr/bin/env python3
"""Compare JUnit test failures against an allowlist file.

Exits 0 when the set of failing tests exactly matches the allowlist —
no unexpected new failures, no allowlist entries that have since been
fixed. Exits 1 otherwise with a GitHub-Actions-friendly error message
describing the diff.
"""
from __future__ import annotations

import argparse
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def load_allowlist(path: Path) -> set[str]:
    entries = set()
    for raw in path.read_text().splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        entries.add(line)
    return entries


def load_failures(xml_path: Path) -> set[str]:
    try:
        tree = ET.parse(xml_path)
    except ET.ParseError as exc:
        print(f"::error ::Failed to parse test results XML: {exc}")
        sys.exit(1)
    failures: set[str] = set()
    for tc in tree.iter("testcase"):
        if tc.find("failure") is None and tc.find("error") is None:
            continue
        classname = tc.attrib.get("classname", "")
        name = tc.attrib.get("name", "")
        failures.add(f"{classname} :: {name}")
    return failures


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--xml", required=True, type=Path,
                        help="Path to the JUnit results XML")
    parser.add_argument("--allowlist", required=True, type=Path,
                        help="Path to the known-failing-tests file")
    args = parser.parse_args()

    if not args.xml.exists():
        print(f"::error ::Test results file not found: {args.xml}")
        return 1
    if not args.allowlist.exists():
        print(f"::error ::Allowlist file not found: {args.allowlist}")
        return 1

    allowlist = load_allowlist(args.allowlist)
    failures = load_failures(args.xml)

    unexpected = sorted(failures - allowlist)
    fixed = sorted(allowlist - failures)

    if unexpected:
        print(f"::error ::{len(unexpected)} new test failure(s) not in allowlist:")
        for entry in unexpected:
            print(f"  - {entry}")

    if fixed:
        print(f"::error ::{len(fixed)} allowlist entry/entries now pass — remove these lines from {args.allowlist}:")
        for entry in fixed:
            print(f"  - {entry}")

    if unexpected or fixed:
        return 1

    print(f"OK: {len(failures)} failing test(s) all match the allowlist.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
