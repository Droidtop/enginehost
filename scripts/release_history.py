"""What a plugin repository has already published: the one reader of its releases.

Shared by build-engine-bundle.py (numbers the next build) and
check-declared-version.py (refuses a revision published under an old declared
version), so there is one definition of "published version" and of version order.
"""

import base64
import json
import re
import subprocess
import sys
import time
from typing import NamedTuple


class Published(NamedTuple):
    version: str      # pluginVersion of the release's signed manifest
    commit: str       # the commit the release was built from ("" when unknown)
    tag: str


def _published_parts(version: str) -> tuple:
    """(dotted parts, build) of a published pluginVersion, read as
    Version.parsePlugin reads it: a legacy `X.Y.<run>` (no -N, non-zero third
    place) is X.Y.0 build <run>."""
    core, dash, build = version.partition("-")
    parts = [int(part) for part in core.split(".")]
    parts += [0] * (3 - len(parts))
    if not dash and len(parts) == 3 and parts[2] != 0:
        return (parts[0], parts[1], 0), parts[2]
    return tuple(parts), int(build or 0)


def version_key(version: str) -> tuple:
    """Order of dev.enginehost.Version for a published version: X.Y.Z, then the
    -N build (bare = 0), legacy `X.Y.<run>` read as X.Y.0-<run>."""
    return _published_parts(version)


def declared_core(version: str) -> tuple:
    """The declared X.Y.Z a published version belongs to (legacy runs: X.Y.0)."""
    return _published_parts(version)[0]


def next_build(declared: str, published: list) -> int:
    highest = 0
    for version in published:
        core, dash, build = version.partition("-")
        if dash and core == declared and build.isdigit():
            highest = max(highest, int(build))
    return highest + 1


# The releases API now and then answers a push build with an empty or cut-off
# body (gh: "unexpected end of JSON input"); the same call a minute later is
# fine (Droidtop/tracker#398). So a call is tried again after a growing pause
# before the build is failed.
RETRY_PAUSES = (5, 15, 45)


def _run_gh(arguments: tuple) -> subprocess.CompletedProcess:
    return subprocess.run(["gh", "api", *arguments], check=False,
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE)


def gh_api(*arguments: str, parse=None, run=_run_gh, sleep=time.sleep):
    """The body of a `gh api` call, or parse(body) when parse is given. A failed
    call, or a body parse refuses, is tried again after each of RETRY_PAUSES;
    the last failure ends the build with gh's message (it names the HTTP status
    of an error answer) or the size of the body that could not be read."""
    reason = ""
    for attempt, pause in enumerate((0, *RETRY_PAUSES)):
        if pause:
            print(f"gh api {arguments[0]}: {reason}; trying again in {pause} s "
                  f"(attempt {attempt + 1} of {len(RETRY_PAUSES) + 1})", file=sys.stderr)
            sleep(pause)
        process = run(arguments)
        if process.returncode:
            reason = process.stderr.decode(errors="replace").strip() or f"exit {process.returncode}"
            continue
        if parse is None:
            return process.stdout
        try:
            return parse(process.stdout)
        except ValueError as error:
            reason = f"unreadable {len(process.stdout)}-byte body ({error})"
    raise SystemExit(f"gh api failed: {reason}")


def published_builds(repository: str, bundle_id: str) -> list:
    """Every release envelope of this bundle id in the repository.

    Rolling channels delete and recreate their release, but the newest build
    of a line always sits in its unstable release, so the highest build is
    never absent from this listing.
    """
    pages = gh_api(f"repos/{repository}/releases?per_page=100", "--paginate", "--slurp", parse=json.loads)
    builds = []
    for release in (item for page in pages for item in page):
        commit = release.get("target_commitish") or ""
        if not re.fullmatch(r"[0-9a-f]{40}", commit):
            commit = ""
        for asset in release.get("assets", []):
            if asset["name"] != "enginehost-release.json":
                continue
            envelope = gh_api("-H", "Accept: application/octet-stream",
                              f"repos/{repository}/releases/assets/{asset['id']}", parse=json.loads)
            for entry in envelope.get("bundles", []):
                manifest = json.loads(base64.b64decode(entry["manifestBase64"]))
                if manifest.get("bundleId") == bundle_id:
                    builds.append(Published(str(manifest["pluginVersion"]), commit, release.get("tag_name", "")))
    return builds


def published_versions(repository: str, bundle_id: str) -> list:
    return [build.version for build in published_builds(repository, bundle_id)]
