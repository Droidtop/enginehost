# Core lines: upstream commit + plugin-core

## The rule

The owner's rule, verbatim, and the first rule of every core
repository:

> "The highest priority task, always, in plugin creation for enginehost, is
> making sure the plugin wrapper is authoritative. If we were to magically lose
> a plugin branch, we need to be able to plop that wrapper code into a new
> branch from the same commit and have it working."

So a line branch is never authored. It is the output of one mechanical step,
and CI fails any line that is not exactly that output.

## Branches

- **The upstream-facing branch** (`main`, `master`, `dev`, `yuri`, ...) follows
  the engine project. Where we change the engine itself (fixes that upstream
  could take), the changes live on an engine branch of the fork (`main` of a
  vendored engine such as NScripter's, or `engine/<line>` such as Buriko's
  `engine/0.0.1`) and are proposed upstream. A line's upstream commit may be
  a commit of that branch.
- **`plugin-core`** holds the wrapper and nothing else: workflows, bundle
  metadata, the core class, Enginehost's API copy, native glue and the file
  layer (`plugin-native/`), licences and docs, plus:
  - `enginehost/lines/<id>/line.json`: each supported line, with its upstream
    repository, tag and commit and its dotted upstream version;
  - `enginehost/lines/<id>/root/**`: files that legitimately differ per line
    (a line's `bundle-metadata.json`, Ren'Py's `runtime.json`), written over
    the repository root for that line only;
  - `enginehost/patches/series` and its patches: engine-side changes the
    wrapper needs, one per line as `<version range> <patch>` (`*`,
    `>=4.0,<4.3`), applied in order. A patch that does not apply fails the
    composition and names itself.
- **`plugin/<id>`** is `compose(upstream commit, plugin-core commit, id)`.

## compose

Droidtop/droidtop-platforms `tools/plugin_line.py` is the one implementation;
it works on git objects only (no checkout):

1. the tree of the upstream commit;
2. plugin-core's whole tree written over it;
3. `enginehost/lines/<id>/root/**` written over the root;
4. the patch series for the line's version;
5. `enginehost/line.json`, the pin: line id, upstream commit and version,
   plugin-core commit.

`plugin_line.py compose <id> --branch plugin/<id>` writes the line as a merge
of (previous line tip, upstream commit, plugin-core commit). Push plugin-core
first, then the line.

## Checks

- `.github/workflows/plugin-line.yml` (on plugin-core, so on every line) calls
  droidtop-platforms' `plugin-line-check.yml`, pinned by commit. On a line it
  recomposes from `enginehost/line.json` and fails on any difference; on
  plugin-core it composes every declared line at the new tip and reports each
  line's pin.
- droidtop-platforms' `plugin-lines.yml` reports every line of every engine
  core repository daily.
- The version check (`scripts/check-declared-version.py`) does not count
  `enginehost/line.json` or `enginehost/lines/` as a revision of the core.

## What CI builds

Every core repository triggers the same way (checked 2026-10-28 on all fourteen):

- **`plugin/<id>`** is the only branch that compiles. A push builds the line, signs it and
  publishes it on the unstable channel, replacing the previous unstable build. A manual
  dispatch with `publish` promotes a build to testing or stable. The build workflow's name
  differs per repository (`android-plugin.yml`, `android.yml`, `enginehost-android.yml`);
  its signing job runs only on `refs/heads/plugin/`.
- **`plugin-core`** runs `plugin-line.yml` only: it composes every declared line and
  compiles nothing. A wrapper change is first compiled when a line composed from it is
  pushed.
- **The upstream-facing branch**, the default branch of most core repositories, carries no
  Enginehost build. Where it is a mirror, `sync-upstream.yml` merges upstream into it daily.
  A merge that touches upstream's own `.github/workflows` files can be pushed only with a
  token that has the workflow scope, so the sync uses the `SYNC_UPSTREAM_PAT` secret when one
  is set and `GITHUB_TOKEN` otherwise. A sync that cannot push fails its run every day and
  keeps one issue open; it never reports green.
- **Any other branch** (`ci/<topic>` and the like) builds nothing. To compile a change, push
  plugin-core and compose it into `plugin/<id>`.

## Changing a line

- **Wrapper change:** commit it to plugin-core, then recompose each line it
  applies to. Never commit to a line.
- **Engine fix:** on the engine branch (and upstream); then point the line's
  declaration in plugin-core at the new commit and recompose.
- **Engine hook the wrapper needs:** a patch in plugin-core's series, keyed by
  the version range it applies to.
- **New upstream version:** declare `enginehost/lines/<id>/` on plugin-core
  with the new tag's commit, compose, build. If it fails, the fix goes into
  plugin-core (a patch range or a version shim chosen from the line's
  version), and every existing line is rebuilt from the same plugin-core.
- **An old line that no longer builds on plugin-core's tip:**
  `plugin_line.py walk <id> --workflow <file>` tries plugin-core commits from
  newest to oldest in CI until one builds and pins that. A pin below the tip
  is compat debt: bring the version forward on the tip, then re-pin.
- **Dropping a line:** delete `enginehost/lines/<id>` from plugin-core, tag the
  branch `archive/plugin/<id>` and delete it.

The plan behind this, with Ren'Py and Godot as worked examples, is on
Droidtop/tracker#324.
