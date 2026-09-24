# CI/CD supply chain review, 2026-09-24

Scope: every GitHub Actions workflow in the Droidtop organisation's repositories,
on the branches that build (`main` for Enginehost and droidtop; `plugin-core`,
`plugin/**` and `main` in the plugin repositories; the default branch
elsewhere). Looked at: where secrets reach, `pull_request_target` and other
privileged triggers, third-party action pinning, how release and plugin
signing keys are handled, what artifacts carry, and token permissions.
Upstream workflows that forks inherited on branches that are not ours to build
(Godot `3.x`/`4.x`, Ruffle `master`, EasyRPG `master`, ...) were read only for
privileged triggers.

No secret value was found committed in any workflow or published in any log
line read for this review.

Severity is about what an attacker who controls the named thing gets, not how
likely that is.

## Findings

### H1 — Plugin signing ran code from a mutable branch of another repository (High)

Every plugin repository's build checked out `Droidtop/enginehost` at
`ref: codex/engine-bundles` and ran `scripts/build-engine-bundle.py` from it
with that repository's `ENGINEHOST_SIGNING_KEY_PEM`. Whoever can push to that
branch (it is retired, so nobody watches it) chooses the code that holds the
signing key of every plugin repository at once, and a signed bundle is exactly
what Enginehost trusts to run engine code. It also meant the signing tool was
whatever that stale branch said, not what `main` says.

Fix: signing moved into one reusable workflow,
`.github/workflows/sign-engine-bundle.yml`, that plugin repositories call pinned
to a full Enginehost commit SHA, and which checks out the signing scripts at a
commit SHA it validates (`tooling-sha`). Moving the pin is a deliberate commit
in each plugin repository.

### H2 — Signing keys shared a job with unreviewed build code (High)

The key was only in the signing step's environment, but every earlier step of
the same job ran as the same runner user in the same workspace: engine SDK
downloads with no checksum (renpy.org SDK/RAPT, Kirikiroid2 dependency
tarballs, ...), Gradle and its plugins, and third-party actions pulled by
moving tag (`android-actions/setup-android@v3`, `gradle/actions/setup-gradle@v4`,
`dtolnay/rust-toolchain@stable`, `Swatinem/rust-cache@v2`,
`sigoden/install-binary@v1`, `dsaltares/fetch-gh-release-asset@1.1.2`). Any of
them could rewrite the signing script on disk or leave a process behind that
reads the signing step's environment from `/proc`.

Fix: the build job no longer sees the key. It tars `payload/` and
`bundle-metadata.json` (tar, because upload-artifact drops the file modes the
manifest signs) and uploads that. The reusable workflow's job runs only
GitHub's own actions pinned by commit, the pinned signing script, and the
repository's committed key document; the payload is data it hashes and packs,
never runs. The key file is written with mode 600 to `$RUNNER_TEMP` and removed
on exit.

### H3 — Write tokens and signing material in jobs that run the build (Medium)

Enginehost's and droidtop's Android builds declared `contents: write` for the
whole workflow, and `actions/checkout` persists the token into `.git/config`
by default, so every Gradle plugin and build script could read a token able to
rewrite releases (including the `latest` APK the in-app updater downloads) and
push to the repository.

Fix (Enginehost): workflow default is `contents: read`; the build checks out
with `persist-credentials: false`; publishing is its own job with
`contents: write` that downloads the APK artifact and runs no build code. The
release step now takes every value through the environment instead of
`${{ }}` interpolation into shell and Python. The APK signing keystore still
has to be in the build job, because Gradle signs the APK; that is accepted.

### M1 — Actions pinned by moving tag (Medium)

All workflows used tags (`@v4`, `@v1`, `@stable`). A retagged or compromised
action runs with whatever the job holds. Fix: every action in a job that holds
a secret or a write token is pinned to a full commit SHA with the tag in a
comment. Bumping one is a reviewed commit.

### M2 — Missing `permissions:` blocks (Medium)

Workflows without a `permissions:` block get the repository's default token
scope, which for older repositories is read/write on everything. Fix: every
workflow we own states its permissions, `contents: read` unless it publishes.

### M3 — `${{ github.ref_name }}` interpolated into shell (Low)

Plugin publish jobs and `enginehost-nscripter-plugin`'s legacy
`build_android.yml` put the branch or tag name straight into a shell script.
Git ref names may contain `$(...)`, so a writer could run commands in a job
holding a release-writing token. Only writers can create refs, so this does
not widen who can do what today, but it is the pattern that turns into an
injection the day a trigger changes. Fix: passed through `env:`.

### L1 — Upstream sync workflows merge unreviewed upstream code (Accepted)

`enginehost-renpy-plugin` (`master`), `gamenative-tux` and `proton-wine-tux`
merge their upstream nightly and push. A compromise upstream arrives
unreviewed. For the Ren'Py fork this lands on `master`, which does not build or
sign; `plugin/**` lines are merged by hand. For the two forks whose default
branch builds, this is the fork's purpose; GitHub refuses a `GITHUB_TOKEN` push
that changes workflow files, so an upstream change cannot rewrite CI by this
path. Accepted, recorded.

### L2 — `pull_request_target` (no finding)

Only `enginehost-rpgmaker-easyrpg-plugin`'s inherited `pr_labels.yml` uses it,
and it checks out upstream `EasyRPG/Player` configuration, never the pull
request's code, with `pull-requests: write` and `contents: read`. Safe as
written. No workflow uses `workflow_run` or `issue_comment`.

### L3 — `PLATFORMS_DISPATCH_TOKEN` and `PLUGINS_INDEX_TOKEN` scope (Info)

A repository dispatch needs `contents: write` on the target, so the dispatch
token can also push to `droidtop-platforms`. What it could change there is
`plugins/index.json`, which Enginehost uses only to find releases; every
bundle is still verified against the key pinned for its origin, so the worst
case is hiding or delaying releases, not running code. The token is only in
publish jobs, which run after the build and run no build code. Keep it a
fine-grained token scoped to that one repository.

## Where things stand

Per-repository status, with the commit that fixed each, is kept in the table
below; a row without a commit is not fixed yet.

| Repository | Branches | Findings | Fixed in |
| --- | --- | --- | --- |
| enginehost | main | H3, M1, M2; hosts the H1/H2 fix | this commit |
