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
import urllib.error
import urllib.request
from typing import NamedTuple


RELEASE_ENVELOPE = "enginehost-release.json"
# A runtime-component bundle's envelope (docs/engine-bundle-format.md,
# "Runtime components"): same format, its own name, so older Enginehost
# builds skip it.
COMPONENT_ENVELOPE = "enginehost-component.json"
ENVELOPES = (RELEASE_ENVELOPE, COMPONENT_ENVELOPE)


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


class Gone(Exception):
    """A `gh api` call answered 404 where the caller asked to hear of it (gone_is_final)."""


def gh_api(*arguments: str, parse=None, run=_run_gh, sleep=time.sleep, gone_is_final=False):
    """The body of a `gh api` call, or parse(body) when parse is given. A failed
    call, or a body parse refuses, is tried again after each of RETRY_PAUSES;
    the last failure ends the build with gh's message (it names the HTTP status
    of an error answer) or the size of the body that could not be read. With
    [gone_is_final], a 404 raises Gone at once: asking again for a thing that
    was deleted cannot succeed, and the caller knows what to look up instead."""
    reason = ""
    for attempt, pause in enumerate((0, *RETRY_PAUSES)):
        if pause:
            print(f"gh api {arguments[0]}: {reason}; trying again in {pause} s "
                  f"(attempt {attempt + 1} of {len(RETRY_PAUSES) + 1})", file=sys.stderr)
            sleep(pause)
        process = run(arguments)
        if process.returncode:
            reason = process.stderr.decode(errors="replace").strip() or f"exit {process.returncode}"
            if gone_is_final and "HTTP 404" in reason:
                raise Gone(reason)
            continue
        if parse is None:
            return process.stdout
        try:
            return parse(process.stdout)
        except ValueError as error:
            reason = f"unreadable {len(process.stdout)}-byte body ({error})"
    raise SystemExit(f"gh api failed: {reason}")


# A rolling channel's release is deleted and recreated by every publish, so
# an envelope listed a moment ago can be gone by the time it is fetched (HTTP
# 404) when several lines publish at once (Droidtop/tracker#551). The listing
# is then taken again, after these pauses, and the build fails only when an
# envelope is still missing: the version check needs the whole history, so a
# missing envelope is never skipped.
RELIST_PAUSES = (5, 15, 45)


def _download(url: str) -> bytes:
    with urllib.request.urlopen(url, timeout=60) as response:
        return response.read()


def envelope_of(asset: dict, repository: str, api=gh_api, download=_download) -> dict:
    """A release envelope's JSON. A public repository's asset is downloaded from
    its release URL, which costs nothing of the REST API's hourly allowance:
    reading every envelope through the API (two reads per build, one per
    release) emptied the installation's allowance when twelve Ren'Py lines
    built at once, and every signing job after failed with "API rate limit
    exceeded" (2026-10-23, Droidtop/tracker#604). A 404 there is Gone, as from
    the API; any other failure falls back to the API, with its retries."""
    url = asset.get("browser_download_url")
    if url:
        try:
            return json.loads(download(url))
        except urllib.error.HTTPError as error:
            if error.code == 404:
                raise Gone(f"{url}: HTTP 404") from error
        except (urllib.error.URLError, OSError, ValueError):
            pass
    return api("-H", "Accept: application/octet-stream",
               f"repos/{repository}/releases/assets/{asset['id']}", parse=json.loads,
               gone_is_final=True)


def published_builds(repository: str, bundle_id: str, api=gh_api, sleep=time.sleep, download=_download) -> list:
    """Every release envelope of this bundle id in the repository.

    Rolling channels delete and recreate their release, but the newest build
    of a line always sits in its unstable release, so the highest build is
    never absent from this listing.
    """
    reason = ""
    for attempt, pause in enumerate((0, *RELIST_PAUSES)):
        if pause:
            print(f"release envelope gone while reading ({reason}); listing the releases again in {pause} s "
                  f"(attempt {attempt + 1} of {len(RELIST_PAUSES) + 1})", file=sys.stderr)
            sleep(pause)
        try:
            return _builds_from_listing(repository, bundle_id, api, download)
        except Gone as gone:
            reason = str(gone)
    raise SystemExit(f"a release envelope of {repository} is still missing after listing the releases again: {reason}")


def _builds_from_listing(repository: str, bundle_id: str, api, download=_download) -> list:
    pages = api(f"repos/{repository}/releases?per_page=100", "--paginate", "--slurp", parse=json.loads)
    builds = []
    for release in (item for page in pages for item in page):
        commit = release.get("target_commitish") or ""
        if not re.fullmatch(r"[0-9a-f]{40}", commit):
            commit = ""
        for asset in release.get("assets", []):
            if asset["name"] not in ENVELOPES:
                continue
            envelope = envelope_of(asset, repository, api, download)
            for entry in envelope.get("bundles", []):
                manifest = json.loads(base64.b64decode(entry["manifestBase64"]))
                if manifest.get("bundleId") == bundle_id:
                    builds.append(Published(str(manifest["pluginVersion"]), commit, release.get("tag_name", "")))
    return builds


def published_versions(repository: str, bundle_id: str) -> list:
    return [build.version for build in published_builds(repository, bundle_id)]
