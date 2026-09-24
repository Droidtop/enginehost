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

All plugin rows below adopt `sign-engine-bundle.yml` pinned at 74162ce0 (H1,
H2), take write tokens and ref names out of build jobs (H3, M3), pin actions
(M1) and state permissions (M2). "No run" means that branch's workflow only
builds on `plugin/**` pushes, so the commit was checked with actionlint only.

| Repository | Branch | Fixed in | CI on the fix |
| --- | --- | --- | --- |
| enginehost | main | 74162ce0 | build and publish green on fb46ba04 (run 36034856631) |
| enginehost-html-plugin | plugin/stable | 1215a7a6, ce874f5 | green (36034232252, 36035541831); signed bundle verified offline against the pinned key |
| enginehost-html-plugin | plugin-core | c9373df, a58fd2d | green (36035586893) |
| enginehost-godot-plugin | plugin-core | 2a3d296, dc34c41 | green (36035549583) |
| enginehost-godot-plugin | plugin/4.0 to 4.7.1, 4.5-embedded-pack | 0b45276, 27d1a51, 614eddd, c86c32f, 6566e0f, ca745ba, 85e204c, d7d29c7, 3d9b264 | all green; 4.3 bundle verified offline, file modes identical to the pre-change 4.2 bundle |
| enginehost-renpy-plugin | plugin/7.3 to 8.5 | fd2b1b67 d4b05c36 ca0f1462 bf308ab8 a2fb5ec1 fc065ca7 b2f9b7a6 82f45be2 8d64236b 0fde7d6e b63e72ca 16ef3d42 | all green; 8.5 bundle verified offline |
| enginehost-renpy-plugin | plugin-core; master (sync, pins only) | a591e68b; d1aee9e7 | no run |
| enginehost-kirikiri-plugin | plugin/stable; plugin-core; yuri (legacy APK) | 7f70a2b; 69343b2; bfad18b | green (36035243488); no run; no run (tag-triggered) |
| enginehost-nscripter-plugin | main | 72021b4, f790f09 | android-plugin green (36035469516); desktop and web builds are tag-triggered, not run |
| enginehost-rpgmaker-mv-mz-plugin | main | 6245496 | green (36035513762) |
| enginehost-buriko-plugin | plugin/0.0.1; plugin-core | c161e8e; 324bc70 | green (36035172895); no run |
| enginehost-catsystem2-plugin | plugin/0.1; plugin-core; plugin/2.0; main | 5c14e8c; 0848d14; 135d899, 30054e2; 54ec425 | green (36035309047); no run; green (36036956404); green (36035338624) |
| enginehost-cmvs-plugin | plugin/2.0-3.0; plugin-core | b5522f4; f68040b | green (36035428638); no run |
| enginehost-flash-air-plugin | plugin/ruffle-0.4.1; plugin-core | c3e63979; 2d37aa29, bdef062f | green (36035221394); no run |
| enginehost-rpgmaker-easyrpg-plugin | plugin/0.8.1.1; plugin-core | dfe5e911; c98eccfd | green (36035375653); no run |
| enginehost-rpgmaker-mkxp-z-plugin | plugin/rgss-v1; plugin-core | 4ef23c37; c8679d7c | red (36035487567) at Build APK on 1633950's `Resources.getLoaders()`, which is not public SDK API; sign and publish not yet exercised on this line; no run |
| droidtop | main | 84ad8ca | publish split not yet exercised: main's builds since fail in Gradle on app code (36036606334) |
| droidtop-platforms | main | cbbbd10 | green (36035155818, 36035155815) |
| windowcast | main | d87518b | green (36035128585) |
| gamenative-tux | master | ae68e133 | green (36035181900) |
| proton-wine-tux | proton_11.0-2 | dffedc86 | run 36035290764 past setup, still building at time of writing |
| enginehost-kirikiri-wrapper-legacy | main | fc96eaf | green (36035225664) |
| enginehost-reports | master | none needed | - |

Not fixed, deliberately:

- Engine SDK and dependency downloads without checksums (renpy.org SDK/RAPT,
  Kirikiroid2 and OnscripterYuri dependency tarballs, proton-wine-tux's
  ntsync-android at a branch head, NDK and llvm-mingw archives). They now run
  in jobs holding no key and no write token, which is what H2 needed; pinning
  checksums changes build inputs and is its own piece of work.
- Upstream workflows on fork branches we do not build (Godot, Ruffle, EasyRPG,
  mkxp-z `autobuild.yml`) and the actions inside Godot's composite actions:
  they hold none of our secrets and use no privileged trigger.
- Pinned `actions/*@v3/v4` still run on the deprecated Node 20 runtime; moving
  them is a version bump, not a security fix.
