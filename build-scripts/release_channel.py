"""The one release mechanism behind Enginehost's build history (Droidtop/tracker#122).

Ported from droidtop's build-scripts/release_channel.py (Droidtop/tracker#119):
same job, one script, two things it publishes from the same OUTPUTS/release-info.json.

  publish-build OUTPUTS
      Publishes the APKs and release-info.json under OUTPUTS to a brand new,
      permanent release tagged by this build's version
      (v<versionName>-dev.<run>). Never overwritten: a rebuild of the exact
      same commit reuses (replaces) only that one tag, never any other
      version's release. Notes are generated from the commit subjects since
      the previous versioned release, grouped Added / Changed / Fixed
      (categorize_commits / render_sections -- the same grouping
      changelog-entry uses, one mechanism for both jobs).

  publish-latest OUTPUTS
      Publishes the APKs and release-info.json under OUTPUTS to the rolling
      "latest" release: the moving pointer AppUpdate.kt reads. This is a
      channel pointer, not history -- it is fine for it to move, as long as
      every version it ever pointed at also has a permanent release from
      publish-build above.

  changelog-entry FROM_COMMIT TO_COMMIT
      Prints a Keep a Changelog section body (### Added / ### Changed /
      ### Fixed) for the commits between FROM_COMMIT and TO_COMMIT. Not run
      by CI: cutting a real version is a deliberate, curated edit to
      CHANGELOG.md, so a person reads this output and rewrites it into plain
      language before it lands -- but the *grouping* is the one mechanism
      behind both documents.
"""

import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile

REPO = os.environ.get("GITHUB_REPOSITORY", "Droidtop/enginehost")

# Published asset name -> path under OUTPUTS. Same layout the build job's
# artifact and the rolling "latest" release have always used.
ASSETS = [
    ("enginehost-latest.apk", "apk/release/app-release.apk"),
    ("enginehost-latest-debug.apk", "apk/debug/app-debug.apk"),
    ("release-info.json", "release-info.json"),
]
STAGED_PREFIX = "next."

# Every per-build history release's tag: v<enginehost.version>-dev.<run>.
# Never "latest": that is the one moving pointer and is excluded by not
# matching this pattern.
BUILD_TAG_RE = re.compile(r"^v(.+)-dev\.(\d+)$")

ADD_RE = re.compile(r"^(add|adds|added)\b", re.IGNORECASE)
FIX_RE = re.compile(r"^(fix|fixed|fixes)\b", re.IGNORECASE)
SECTION_ORDER = ("Added", "Changed", "Fixed")


def gh(*args, check=True):
    result = subprocess.run(["gh", *args], capture_output=True, text=True)
    if check and result.returncode != 0:
        sys.exit(f"gh {' '.join(args)} failed:\n{result.stderr}")
    return result


def gh_json(*args):
    return json.loads(gh("api", *args).stdout)


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest().upper()


def release_or_none(tag):
    result = gh("api", f"repos/{REPO}/releases/tags/{tag}", check=False)
    if result.returncode == 0:
        return json.loads(result.stdout)
    if "HTTP 404" in result.stderr:
        return None
    sys.exit(f"Could not read the {tag} release:\n{result.stderr}")


def clean_commit_subjects(raw_subjects):
    subjects = []
    for subject in raw_subjects:
        subject = subject.strip()
        if not subject or subject.lower().startswith("merge "):
            continue
        subjects.append(subject)
    return subjects


def categorize_commits(subjects):
    sections = {name: [] for name in SECTION_ORDER}
    for subject in subjects:
        if ADD_RE.match(subject):
            sections["Added"].append(subject)
        elif FIX_RE.match(subject):
            sections["Fixed"].append(subject)
        else:
            sections["Changed"].append(subject)
    return sections


def render_sections(sections):
    parts = []
    for name in SECTION_ORDER:
        items = sections.get(name) or []
        if items:
            parts.append(f"### {name}\n" + "\n".join(f"- {s}" for s in items))
    return "\n\n".join(parts)


def commits_between(previous_commit, commit):
    result = gh("api", f"repos/{REPO}/compare/{previous_commit}...{commit}", check=False)
    if result.returncode != 0:
        return None, result.stderr.strip()
    subjects = [
        entry["commit"]["message"].splitlines()[0]
        for entry in json.loads(result.stdout).get("commits", [])
    ]
    return clean_commit_subjects(subjects), None


def previous_build_release(exclude_tag):
    for release in gh_json(f"repos/{REPO}/releases?per_page=100"):
        tag = release["tag_name"]
        if tag != exclude_tag and BUILD_TAG_RE.match(tag):
            return tag, release["target_commitish"]
    return None, None


def build_notes(commit, previous_tag, previous_commit):
    if previous_commit is None:
        return "First release under per-build release history (Droidtop/tracker#122)."
    subjects, error = commits_between(previous_commit, commit)
    if error is not None:
        return f"Changes since {previous_tag}. (Commit list unavailable: {error})"
    if not subjects:
        return f"No user-visible changes since {previous_tag}."
    return render_sections(categorize_commits(subjects))


def changelog_entry(from_commit, to_commit):
    subjects, error = commits_between(from_commit, to_commit)
    if error is not None:
        sys.exit(f"Could not list commits between {from_commit} and {to_commit}: {error}")
    if not subjects:
        print("(no commits in this range)")
        return
    print(render_sections(categorize_commits(subjects)))


def _check_assets(outputs, info):
    if sha256(os.path.join(outputs, ASSETS[0][1])) != info["apkSha256"] or \
            sha256(os.path.join(outputs, ASSETS[1][1])) != info["debugApkSha256"]:
        sys.exit("The APKs do not match their release-info.json; refusing to publish")


def publish_build(outputs):
    with open(os.path.join(outputs, "release-info.json")) as f:
        info = json.load(f)
    _check_assets(outputs, info)
    commit = info["commit"]

    tag = f"v{info['versionName']}"
    previous_tag, previous_commit = previous_build_release(exclude_tag=tag)
    notes = build_notes(commit, previous_tag, previous_commit)

    # A rebuild of the same commit (or a retry after a cancelled run) reuses
    # this exact tag: replace it rather than leaving a half-uploaded release
    # or failing outright. Any OTHER version's release is never touched.
    if release_or_none(tag) is not None:
        gh("release", "delete", tag, "--yes", "--cleanup-tag")

    stage = tempfile.mkdtemp()
    files = []
    for name, source in ASSETS:
        shutil.copyfile(os.path.join(outputs, source), os.path.join(stage, name))
        files.append(os.path.join(stage, name))

    gh("release", "create", tag, *files, "--target", commit, "--prerelease",
       "--title", f"Enginehost {tag}", "--notes", notes)
    print(f"Published {tag} at {commit}")


def publish_latest(outputs):
    with open(os.path.join(outputs, "release-info.json")) as f:
        info = json.load(f)
    _check_assets(outputs, info)
    commit = info["commit"]

    title = "Enginehost — latest build"
    notes = (
        f"Automatically built from commit {commit} (build {info['versionCode']}). "
        f"{ASSETS[0][0]} is the release build; {ASSETS[1][0]} is the debug build for test "
        "rigs. Both are signed with the persistent Enginehost CI key, so either installs over "
        "a previous one without uninstalling. release-info.json is what the in-app update "
        "check reads. This release is only a pointer to the newest build on main; the "
        f"permanent history lives in the versioned releases (v{info['versionName'].split('-dev.')[0]}"
        "-dev.<build>)."
    )

    gh("release", "delete", "latest", "--yes", "--cleanup-tag", check=False)
    stage = tempfile.mkdtemp()
    files = []
    for name, source in ASSETS:
        shutil.copyfile(os.path.join(outputs, source), os.path.join(stage, name))
        files.append(os.path.join(stage, name))
    gh("release", "create", "latest", *files, "--target", commit,
       "--title", title, "--notes", notes)
    print(f"Updated the latest release to {commit}")


def main(argv):
    if len(argv) == 2 and argv[0] == "publish-build":
        publish_build(argv[1])
    elif len(argv) == 2 and argv[0] == "publish-latest":
        publish_latest(argv[1])
    elif len(argv) == 3 and argv[0] == "changelog-entry":
        changelog_entry(argv[1], argv[2])
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main(sys.argv[1:])
