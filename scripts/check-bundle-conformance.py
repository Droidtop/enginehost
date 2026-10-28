#!/usr/bin/env python3
"""Conformance layer 1a: check a core's unsigned bundle inputs offline.

Run by the `conformance` job of .github/workflows/sign-engine-bundle.yml on
the payload/ and bundle-metadata.json a core repository's build uploads,
before anything is signed (docs/conformance-test-games.md, layer 1a;
Droidtop/tracker#27). It reads bytes and never runs them, so it is safe to
point at a payload nobody reviewed.

What it refuses, each a way a core has installed cleanly and then failed on
a device, or shown a person a raw id:

- native code for any ABI but arm64-v8a and x86_64, or for only one of them
  (docs/engine-bundle-format.md: "A bundle carries both 64-bit ABIs, in one
  archive"), at the payload's top level or inside a payload APK;
- the two ABIs carrying different sets of libraries, or a library whose ELF
  header is not a 64-bit library for the directory it sits in;
- dexFiles or resourceApks that the payload does not carry, and a resource
  APK whose name does not end in .apk;
- an engine id, or a capability's engine, the app has no name for
  (EngineNames.family), which the Cores screen would show as the raw id;
- a capability that names no engine context or runtime version, or accepts
  no engine version at all;
- payload files that are setuid, setgid or writable by group or others.

The signer (scripts/build-engine-bundle.py) already refuses missing
required metadata fields; this does not repeat that.
"""

import argparse
import json
import re
import stat
import sys
import zipfile
from pathlib import Path, PurePosixPath

REQUIRED_ABIS = ("arm64-v8a", "x86_64")
# ELF e_machine for each ABI directory (ELF64 only).
ELF_MACHINE = {"arm64-v8a": 183, "x86_64": 62}
# Present in old builds and refused outright since 2026-10-24 (Droidtop/tracker#646).
KNOWN_32_BIT = ("armeabi-v7a", "armeabi", "x86")


def known_engines(engine_names_kt: Path) -> set:
    """The engine ids EngineNames.family names, read from the app's source so
    the app and this check cannot disagree."""
    text = engine_names_kt.read_text(encoding="utf-8")
    start = text.index("fun family(")
    end = text.index("else ->", start)
    ids = set()
    for line in text[start:end].splitlines():
        if "->" in line:
            ids.update(re.findall(r'"([^"]+)"', line.split("->", 1)[0]))
    if not ids:
        raise SystemExit(f"no engine ids found in {engine_names_kt}")
    return ids


def elf_problem(data: bytes, abi: str):
    if data[:4] != b"\x7fELF":
        return "is not an ELF file"
    if data[4] != 2:
        return "is not a 64-bit ELF library"
    if data[5] != 1:
        return "is not little-endian"
    machine = int.from_bytes(data[18:20], "little")
    if machine != ELF_MACHINE[abi]:
        return f"is built for ELF machine {machine}, not {abi} ({ELF_MACHINE[abi]})"
    return None


def check_libraries(where: str, entries, problems: list) -> None:
    """entries: (relative path, bytes reader) for every file in one tree."""
    by_abi = {}
    for path, read in entries:
        parts = PurePosixPath(path).parts
        if len(parts) < 3 or parts[0] != "lib":
            continue
        abi = parts[1]
        if abi not in REQUIRED_ABIS:
            reason = "32-bit ABIs are not shipped" if abi in KNOWN_32_BIT else "only arm64-v8a and x86_64 are shipped"
            problems.append(f"{where}lib/{abi}/: {reason} (docs/engine-bundle-format.md)")
            continue
        name = "/".join(parts[2:])
        by_abi.setdefault(abi, set()).add(name)
        if name.endswith(".so"):
            issue = elf_problem(read(), abi)
            if issue:
                problems.append(f"{where}{path} {issue}")
    if not by_abi:
        return
    for abi in REQUIRED_ABIS:
        if abi not in by_abi:
            problems.append(f"{where}lib/ carries no {abi}: every core with native code ships both "
                            "arm64-v8a and x86_64 (docs/engine-bundle-format.md)")
    if all(abi in by_abi for abi in REQUIRED_ABIS):
        first, second = (by_abi[abi] for abi in REQUIRED_ABIS)
        for name in sorted(first - second):
            problems.append(f"{where}lib/{REQUIRED_ABIS[0]}/{name} has no x86_64 counterpart")
        for name in sorted(second - first):
            problems.append(f"{where}lib/{REQUIRED_ABIS[1]}/{name} has no arm64-v8a counterpart")


def payload_entries(payload: Path):
    for path in sorted(payload.rglob("*"), key=lambda item: item.as_posix()):
        if path.is_file() and not path.is_symlink():
            yield path.relative_to(payload).as_posix(), path


def check(metadata: dict, payload: Path, engines: set) -> list:
    problems = []
    files = dict(payload_entries(payload))

    check_libraries("", ((name, path.read_bytes) for name, path in files.items()), problems)
    for name, path in files.items():
        mode = stat.S_IMODE(path.stat().st_mode)
        if mode & (stat.S_ISUID | stat.S_ISGID):
            problems.append(f"{name} is setuid or setgid")
        if mode & (stat.S_IWGRP | stat.S_IWOTH):
            problems.append(f"{name} is writable by group or others (mode {mode:o})")
        if name.endswith(".apk"):
            try:
                with zipfile.ZipFile(path) as apk:
                    entries = [(info.filename, (lambda info=info: apk.read(info)))
                               for info in apk.infolist() if not info.is_dir()]
                    check_libraries(name + "!/", entries, problems)
            except zipfile.BadZipFile:
                problems.append(f"{name} is not a valid APK (zip)")

    kind = metadata.get("kind", "engine")
    for field in ("dexFiles", "resourceApks"):
        for name in metadata.get(field) or []:
            if name not in files:
                problems.append(f"{field} names {name}, which the payload does not carry")
            elif field == "resourceApks" and not name.endswith(".apk"):
                problems.append(f"resource APK {name} must end in .apk (docs/engine-bundle-format.md)")

    bundle_engine = metadata.get("engine", "")
    declared = metadata.get("engines") or [bundle_engine]
    for engine in sorted(set(declared) | {bundle_engine}):
        if engine and engine not in engines:
            problems.append(f"engine {engine!r} has no name in EngineNames.family; the Cores screen would "
                            "show the raw id. Use an existing engine id, or add its name to the app first")
    if kind == "engine":
        for index, capability in enumerate(metadata.get("capabilities") or []):
            label = f"capability {capability.get('id') or index}"
            engine = capability.get("engine") or bundle_engine
            if capability.get("engine") and engine not in declared:
                problems.append(f"{label} serves {engine!r}, which the bundle's engines do not list")
            for field in ("id", "engineContext", "runtimeVersion"):
                if not str(capability.get(field) or "").strip():
                    problems.append(f"{label} has no {field}")
            if not (capability.get("supportedSeries") or capability.get("supportedRanges")
                    or capability.get("supportedVersions") or capability.get("acceptsAnyEngineVersion")):
                problems.append(f"{label} accepts no engine version: declare supportedSeries, "
                                "supportedRanges, supportedVersions or acceptsAnyEngineVersion")
    return problems


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--metadata", required=True, type=Path)
    parser.add_argument("--payload", required=True, type=Path)
    parser.add_argument("--engine-names", required=True, type=Path,
                        help="Enginehost's app/src/main/kotlin/dev/enginehost/EngineNames.kt")
    args = parser.parse_args()
    metadata = json.loads(args.metadata.read_text(encoding="utf-8"))
    if not args.payload.is_dir():
        raise SystemExit(f"{args.payload} is not a directory")
    problems = check(metadata, args.payload, known_engines(args.engine_names))
    for problem in problems:
        print(f"::error::{problem}", file=sys.stderr)
    if problems:
        raise SystemExit(f"{len(problems)} conformance problem(s) in {metadata.get('bundleId', 'the bundle')}")
    print(f"{metadata.get('bundleId')}: conformance layer 1a passed")


if __name__ == "__main__":
    main()
