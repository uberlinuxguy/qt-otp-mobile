"""Open vaults written by the Android code using qt-otp's own Python.

This is the other half of the compatibility story: the unit tests prove the app
can read a desktop vault, and this proves the desktop app can read what the app
writes. Run it after the unit tests have produced app/build/interop/*.otpv.

Usage:
    # once, so the desktop implementation is importable
    pip install cryptography
    git clone https://github.com/uberlinuxguy/qt-otp /tmp/qt-otp

    python tools/verify_interop.py --qt-otp /tmp/qt-otp
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

REWRITTEN_PASSWORD = "correct horse battery staple"
CREATED_PASSWORD = "a phone-side password"


def check(vault_module, path: Path, password: str, expect: list[tuple[str, str]]) -> list[str]:
    """Open `path` with the desktop implementation and compare its entries."""
    problems: list[str] = []
    vault = vault_module.Vault(path)
    try:
        vault.unlock(password)
    except Exception as exc:  # noqa: BLE001 - report, do not crash
        return [f"{path.name}: could not unlock: {type(exc).__name__}: {exc}"]

    got = [(e.issuer, e.account) for e in vault.entries]
    if got != expect:
        problems.append(f"{path.name}: entries differ\n  want {expect}\n  got  {got}")

    for entry in vault.entries:
        try:
            code = entry.code()
        except Exception as exc:  # noqa: BLE001
            problems.append(f"{path.name}: {entry.label}: code failed: {exc}")
            continue
        if len(code) != entry.digits or not code.isdigit():
            problems.append(f"{path.name}: {entry.label}: implausible code {code!r}")
        else:
            print(f"    {entry.label}: {code} ({entry.algorithm}, {entry.digits} digits, {entry.period}s)")

    # A save through the desktop app must not corrupt the file for either side.
    try:
        vault.save()
        vault.lock()
        vault.unlock(password)
    except Exception as exc:  # noqa: BLE001
        problems.append(f"{path.name}: desktop save/reopen failed: {type(exc).__name__}: {exc}")

    vault.lock()
    return problems


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--qt-otp",
        required=True,
        type=Path,
        help="path to a checkout of https://github.com/uberlinuxguy/qt-otp",
    )
    parser.add_argument(
        "--interop-dir",
        type=Path,
        default=Path(__file__).resolve().parent.parent / "app" / "build" / "interop",
        help="where the unit tests wrote the vault files",
    )
    args = parser.parse_args()

    sys.path.insert(0, str(args.qt_otp.resolve()))
    try:
        from otpvault import vault as vault_module
    except ImportError as exc:
        print(f"cannot import otpvault from {args.qt_otp}: {exc}", file=sys.stderr)
        return 2

    cases = [
        (
            args.interop_dir / "rewritten.otpv",
            REWRITTEN_PASSWORD,
            [
                ("GitHub", "octocat"),
                ("AWS", "root@example.com"),
                ("Bank", "jason"),
                ("Zürich Bahn ☕", "pendler"),
            ],
        ),
        (
            args.interop_dir / "created.otpv",
            CREATED_PASSWORD,
            [
                ("Made On Android", "someone@example.com"),
                ("Ålesund Kraft ⚡", "meter-42"),
            ],
        ),
    ]

    problems: list[str] = []
    for path, password, expect in cases:
        if not path.is_file():
            problems.append(f"{path} is missing; run the unit tests first")
            continue
        print(f"  {path.name}")
        problems.extend(check(vault_module, path, password, expect))

    print()
    if problems:
        print("FAILED")
        for problem in problems:
            print(f"  - {problem}")
        return 1
    print("OK: the desktop implementation opened every vault written by the app")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
