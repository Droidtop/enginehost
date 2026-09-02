#!/usr/bin/env python3
"""Promote one CI-built, hardware-verified engine bundle to a GitHub Release.

Plugin CI already builds and signs `*.enginehost.tar.xz` bundles on every
push, but a workflow artifact is not a release: Enginehost's catalog reads
published GitHub Releases only, so a bundle that is never promoted does not
exist as far as any user is concerned. This script is the promotion step,
and it deliberately is not automatic: a green build proves the bundle
compiles and packages, not that it runs. The project's bar for publishing
is "installs and boots a real game on hardware", so the one thing this
script cannot fetch for you -- the --evidence text stating what was actually
verified on a device -- is a required argument, and it is written verbatim
into the release notes where it can be checked later. Do not invent it.

What the script does, in order:

1. Confirms the given workflow run belongs to the given repository and
   concluded successfully, and reads its branch and commit.
2. Downloads the run's artifacts and locates the bundle set: one or more
   `*.enginehost.tar.xz`, the `enginehost-release.json` browsing envelope,
   and the `enginehost-public-key.json` key document.
3. Requires the artifact's key document to be byte-identical to the one
   committed in the repository at that run's own commit -- the same pinned
   key Enginehost verifies against.
4. Verifies every bundle offline with scripts/verify-engine-bundle.py
   against that key document.
5. Assembles the release as a draft with all assets and publishes it only
   once they are all up (the retention policy in docs/plugin-catalog.md:
   drafts are invisible to Enginehost, so a half-uploaded release is never
   served). --draft stops before publishing for a manual look.

Releases are permanent: docs/plugin-catalog.md promises never to delete a
published engine-bundle release, so run this only for bundles that met the
bar.

Example:

    scripts/promote-plugin-release.py Droidtop/enginehost-renpy-plugin \
        --run 33589981163 --tag renpy-8.1-v1 --title "Ren'Py 8.1 runtime" \
        --evidence "Boots <game> to its main menu and into gameplay on the
                    Retroid Pocket 5 (build installed from this exact run)."
"""

import argparse
import json
import re
import subprocess
import sys
import tempfile
from pathlib import Path


def run(*argv: str, capture: bool = True) -> str:
    result = subprocess.run(list(argv), capture_output=capture, text=True)
    if result.returncode:
        raise SystemExit(
            f"command failed ({' '.join(argv)}):\n{(result.stderr or result.stdout or '').strip()}"
        )
    return result.stdout if capture else ""


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("repo", help="GitHub repository, e.g. Droidtop/enginehost-renpy-plugin")
    parser.add_argument("--run", required=True, help="workflow run id whose artifacts to promote")
    parser.add_argument("--tag", required=True, help="release tag, e.g. renpy-8.2-v1")
    parser.add_argument("--title", required=True, help="release title, e.g. \"Ren'Py 8.2 runtime\"")
    parser.add_argument(
        "--evidence",
        required=True,
        help="what was actually verified on hardware, written into the notes; never invent this",
    )
    parser.add_argument("--notes", default="", help="extra release-note text, appended verbatim")
    parser.add_argument("--prerelease", action="store_true", help="mark the release as a prerelease")
    parser.add_argument("--draft", action="store_true", help="stop after assembling the draft")
    args = parser.parse_args()

    if not re.fullmatch(r"[\w.-]+/[\w.-]+", args.repo):
        raise SystemExit("repo must look like owner/name")
    if len(args.evidence.split()) < 5:
        raise SystemExit(
            "--evidence must actually describe the on-hardware verification "
            "(game, device, what was seen), not a token phrase"
        )

    run_info = json.loads(
        run("gh", "api", f"repos/{args.repo}/actions/runs/{args.run}")
    )
    if run_info.get("status") != "completed" or run_info.get("conclusion") != "success":
        raise SystemExit(
            f"run {args.run} is {run_info.get('status')}/{run_info.get('conclusion')}; "
            "only a fully successful run can be promoted"
        )
    branch = run_info["head_branch"]
    commit = run_info["head_sha"]
    run_url = run_info["html_url"]

    existing = subprocess.run(
        ["gh", "release", "view", args.tag, "-R", args.repo],
        capture_output=True,
        text=True,
    )
    if existing.returncode == 0:
        raise SystemExit(
            f"release {args.tag} already exists in {args.repo}; releases are never "
            "replaced -- pick the next tag (bump the trailing -vN)"
        )

    scripts = Path(__file__).resolve().parent
    with tempfile.TemporaryDirectory() as temporary:
        workdir = Path(temporary)
        run("gh", "run", "download", args.run, "-R", args.repo, "-D", str(workdir))
        bundles = sorted(workdir.rglob("*.enginehost.tar.xz"))
        envelopes = sorted(workdir.rglob("enginehost-release.json"))
        key_documents = sorted(workdir.rglob("enginehost-public-key.json"))
        if not bundles or len(envelopes) != 1 or len(key_documents) != 1:
            raise SystemExit(
                f"run {args.run} artifacts do not form one bundle set "
                f"(bundles={len(bundles)}, envelopes={len(envelopes)}, keys={len(key_documents)}); "
                "is this the plugin build workflow?"
            )

        # The key document the release will carry must be the one committed in
        # the repository at this very commit -- that is what Enginehost pins.
        committed = run(
            "gh", "api", f"repos/{args.repo}/contents/enginehost-public-key.json?ref={commit}",
            "-H", "Accept: application/vnd.github.raw",
        )
        if committed.strip() != key_documents[0].read_text().strip():
            raise SystemExit(
                "artifact enginehost-public-key.json differs from the one committed "
                f"at {commit[:12]}; refusing to publish a key users cannot pin"
            )

        for bundle in bundles:
            run(
                sys.executable, str(scripts / "verify-engine-bundle.py"),
                str(bundle), "--repository-key", str(key_documents[0]),
            )
            print(f"verified: {bundle.name}")

        notes = "\n\n".join(
            part
            for part in (
                f"{args.title} for Enginehost.",
                "Signed with this repository's official signing key and verifiable "
                "against the key document published in this repository. Install it "
                "through Enginehost rather than unpacking it by hand: the bundle is "
                "verified file by file at install time, and approval is bound to the "
                "exact archive digest and signer.",
                f"**Verified on hardware:** {args.evidence.strip()}",
                f"Built from branch `{branch}` at {commit} by [this CI run]({run_url}).",
                args.notes.strip(),
            )
            if part
        )
        create = [
            "gh", "release", "create", args.tag, "-R", args.repo,
            "--target", commit, "--title", args.title, "--notes", notes, "--draft",
        ]
        if args.prerelease:
            create.append("--prerelease")
        create += [str(path) for path in bundles + envelopes + key_documents]
        run(*create)
        if args.draft:
            print(f"draft assembled: {args.tag} in {args.repo} (publish it from the web UI, or rerun without --draft)")
            return
        run("gh", "release", "edit", args.tag, "-R", args.repo, "--draft=false")
        print(f"published: {args.tag} in {args.repo}")


if __name__ == "__main__":
    main()
