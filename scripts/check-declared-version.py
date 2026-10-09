#!/usr/bin/env python3
"""Refuse a plugin revision published under a declared version that is already out.

Owner rule (2026-10-01, Droidtop/tracker#126): every revision of a plugin's
code bumps its declared version, so a published `<declared>-<N>` always names
one revision of the code. This compares the build being made with the newest
release already published for its bundle id:

- declared version above everything published: fine, a new version starts at -1;
- declared version below something published: refused, it would never be
  offered as an update;
- declared version equal to the newest published one: fine only when the
  commit being built differs from the release's commit in documentation and CI
  files alone (see the ignored paths); any other changed file is a revision
  published under an old version string and fails.

Runs without the signing key, in the calling repository's checkout (a
treeless clone: commits and trees, no file contents). A published release whose
commit cannot be found (history rewritten) is reported and not held against
the build.
"""

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

from release_history import declared_core, published_builds, version_key

# Paths whose change is not a revision of the plugin's code: documentation,
# changelogs, licences, CI wiring (a re-pin of the signing job), the key document.
# build-scripts/publish-history-release.sh is the copy of the release script that
# plugin repositories carried before publish-engine-bundle.yml replaced it
# (Droidtop/tracker#196): deleting it is CI wiring, not a revision of the code.
IGNORED_PREFIXES = (".github/", "docs/")
IGNORED_NAMES = {"LICENSE", "enginehost-public-key.json", "enginehost-origin.json",
                 "build-scripts/publish-history-release.sh"}
IGNORED_SUFFIXES = (".md",)


def ignored(path: str) -> bool:
    return path.startswith(IGNORED_PREFIXES) or path in IGNORED_NAMES or path.endswith(IGNORED_SUFFIXES)


def judge(declared: str, builds: list, head: str, changed_since) -> tuple:
    """Returns (ok, message). `changed_since(commit)` lists the files that differ
    between `commit` and `head`, or None when the commit is unknown here."""
    if not re.fullmatch(r"\d+(\.\d+){2,}", declared):
        return False, f"declared pluginVersion must be X.Y.Z, got {declared}"
    if not builds:
        return True, f"{declared}: nothing published for this bundle yet"
    want = tuple(int(part) for part in declared.split("."))
    newest_core = max(declared_core(b.version) for b in builds)
    if want > newest_core:
        return True, f"{declared} is above every published version; the build will be {declared}-1"
    if want < newest_core:
        shown = ".".join(str(p) for p in newest_core)
        return False, (f"declared version {declared} is below the published {shown}; "
                       f"raise it above {shown} so the build is offered as an update")
    last = max((b for b in builds if declared_core(b.version) == want), key=lambda b: version_key(b.version))
    if not last.commit:
        return True, f"published {last.version} names no commit; cannot compare, not holding the build back"
    if last.commit == head:
        return True, f"{head[:9]} is the commit published as {last.version}"
    files = changed_since(last.commit)
    if files is None:
        return True, f"published {last.version} was built from {last.commit[:9]}, not in this history; cannot compare"
    changed = sorted(f for f in files if not ignored(f))
    if not changed:
        return True, f"only documentation and CI files differ from {last.version} ({last.commit[:9]})"
    listing = "\n".join(f"  {f}" for f in changed[:30]) + ("\n  ..." if len(changed) > 30 else "")
    return False, (f"the sources changed since {last.version} was published ({last.commit[:9]}) but the declared "
                   f"version is still {declared}. Every revision of a plugin's code bumps its declared version "
                   f"(a patch at minimum; Droidtop/tracker#126). Changed files:\n{listing}")


def git_changed_since(repo: Path):
    def changed(commit: str):
        probe = subprocess.run(["git", "-C", str(repo), "cat-file", "-e", commit + "^{commit}"],
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if probe.returncode:
            return None
        out = subprocess.run(["git", "-C", str(repo), "diff", "--name-only", "--no-renames", commit, "HEAD"],
                             check=True, stdout=subprocess.PIPE).stdout.decode()
        return out.splitlines()
    return changed


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--metadata", required=True, type=Path, help="the unsigned bundle-metadata.json")
    parser.add_argument("--repository", required=True, metavar="OWNER/REPO")
    parser.add_argument("--checkout", required=True, type=Path, help="a git checkout of the build commit")
    args = parser.parse_args()
    metadata = json.loads(args.metadata.read_text(encoding="utf-8"))
    declared = str(metadata["pluginVersion"])
    head = subprocess.run(["git", "-C", str(args.checkout), "rev-parse", "HEAD"], check=True,
                          stdout=subprocess.PIPE).stdout.decode().strip()
    builds = published_builds(args.repository, metadata["bundleId"])
    ok, message = judge(declared, builds, head, git_changed_since(args.checkout))
    if ok:
        print(message)
    else:
        print(f"::error::{message}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
