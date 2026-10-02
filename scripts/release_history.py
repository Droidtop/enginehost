"""What a plugin repository has already published: the one reader of its releases.

Shared by build-engine-bundle.py (numbers the next build) and
check-declared-version.py (refuses a revision published under an old declared
version), so there is one definition of "published version" and of version order.
"""

import base64
import json
import re
import subprocess
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


def gh_api(*arguments: str) -> bytes:
    process = subprocess.run(["gh", "api", *arguments], check=False,
                             stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if process.returncode:
        raise SystemExit("gh api failed: " + process.stderr.decode(errors="replace").strip())
    return process.stdout


def published_builds(repository: str, bundle_id: str) -> list:
    """Every release envelope of this bundle id in the repository.

    Rolling channels delete and recreate their release, but the newest build
    of a line always sits in its unstable release, so the highest build is
    never absent from this listing.
    """
    pages = json.loads(gh_api(f"repos/{repository}/releases?per_page=100", "--paginate", "--slurp"))
    builds = []
    for release in (item for page in pages for item in page):
        commit = release.get("target_commitish") or ""
        if not re.fullmatch(r"[0-9a-f]{40}", commit):
            commit = ""
        for asset in release.get("assets", []):
            if asset["name"] != "enginehost-release.json":
                continue
            envelope = json.loads(gh_api("-H", "Accept: application/octet-stream",
                                         f"repos/{repository}/releases/assets/{asset['id']}"))
            for entry in envelope.get("bundles", []):
                manifest = json.loads(base64.b64decode(entry["manifestBase64"]))
                if manifest.get("bundleId") == bundle_id:
                    builds.append(Published(str(manifest["pluginVersion"]), commit, release.get("tag_name", "")))
    return builds


def published_versions(repository: str, bundle_id: str) -> list:
    return [build.version for build in published_builds(repository, bundle_id)]
