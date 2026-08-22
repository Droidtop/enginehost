# enginehost

A straightforward multi-engine host for VN/RPG-Maker-family games on
Android (KiriKiri, RPG Maker XP/VX/VX Ace via mkxp-z, Ren'Py — more as
they're wired up).

This is **not** a JoiPlay replacement or competitor — JoiPlay is a good
project doing the user-friendly, polished version of this well. enginehost
exists for a different purpose: it's built to be driven *programmatically*,
by another app that already knows what game it wants run and where it
lives on disk, not by a person browsing a catalog.

## The contract

Fire an Intent:

```
action: dev.enginehost.LAUNCH
extra "path": absolute path to the game's folder
```

That's the whole interface. No catalog, no import step, no metadata to
pass beyond the path — enginehost reads a small `enginehost.json` file at
the root of that folder to figure out everything else:

```json
{
  "engine": "kirikiri2",
  "engineVersion": "2.32",
  "pluginVersion": "1.0.0,1.2.0-1.4.0",
  "execFile": "startup.tjs"
}
```

`engine` and `engineVersion` are required. Plugins are identified by three
things: which `engine` they implement, which real `engineVersion` of that
engine they wrap, and their own `pluginVersion` (enginehost's own build
number for that plugin — independent of the engine version, since
enginehost's own plugin code can regress or fix things across its own
revisions without the underlying engine changing at all). Resolution
tries an exact `engineVersion` match first, then falls back to the
nearest installed one.

`pluginVersion` in a game's config is optional and different from a
plugin's own `pluginVersion`: it's a comma-separated list of exact
versions and/or `lo-hi` ranges of *plugin builds* this specific game
trusts, letting a game protect itself from a known-bad plugin revision
(the motivating real case: JoiPlay's own RPG Maker plugin has reportedly
regressed specific games in newer builds). `execFile` is optional — the
specific file to run within the folder, for engines that need one.

Nothing about this file's contents, or the folder it lives in, is ever
copied or moved by enginehost. It reads the folder in place and runs the
game from there.

## The basic UI

There's also a plain pick-a-folder-and-launch screen, and a (currently
unimplemented) controller config screen, for when there's no caller —
just someone holding the device. The Intent contract above is the
primary, intended way in.

## Plugins

Plugins are separate apps, each its own repo, manually installed
(a RetroArch-cores-style installer is a possible future addition, not
built yet). enginehost never bundles engine code itself — it discovers
whatever's actually installed on the device via `PackageManager`, the
same real mechanism JoiPlay's own plugin system uses under the hood.

A plugin declares:
- The `dev.enginehost.plugin.RUN` intent-filter on an exported activity.
- `<meta-data>` for `dev.enginehost.plugin.engine`, `.engineVersion`, and
  `.pluginVersion`.

When invoked, it receives extras `path` (the game folder) and, if the
game's config had one, `execFile`.

Each plugin repo keeps its full commit history — no shallow/squashed
clones. For an engine with real distinct incompatible versions (Ren'Py's
Python 2 vs. Python 3 builds, for instance), those versions live on
separate branches of the same plugin repo rather than separate repos, so
they can be built and maintained side by side.

## Status

Early scaffold. The Intent contract, plugin discovery/resolution, config
format, and basic UI shell are real and build. No plugins exist yet — the
first is the KiriKiri one, in progress.
