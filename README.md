# enginehost

A straightforward multi-engine host for VN/RPG-Maker-family games on
Android (KiriKiri, RPG Maker XP/VX/VX Ace via mkxp-z, Ren'Py — more as
they're wired up).

enginehost is a centralized interpreter host built to be driven
*programmatically* by another app that already knows what game it wants
to run and where it lives on disk. It is not a self-contained catalog or
game-library application.

## The contract

Fire an Intent:

```
action: dev.enginehost.LAUNCH
extra "path": absolute path to the game's folder
extra "config" (optional): a raw enginehost.json-shaped JSON string
```

That's the whole interface. In the normal case there's no catalog, no
import step, no metadata to pass beyond the path — enginehost reads a
small `enginehost.json` file at the root of that folder to figure out
everything else:

```json
{
  "engine": "rpgmaker",
  "engineContext": "vxace",
  "engineVersion": "1.4.0",
  "pluginVersion": "1.0.0,1.2.0-1.4.0",
  "execFile": "Game.exe",
  "options": {
    "rubyVersion": "1.9"
  }
}
```

`engine` and `engineVersion` are required. `engine` names the plugin
family (`renpy`, `rpgmaker`, `godot`, ...). `engineContext` is an optional,
plugin-defined compatibility line within that family (`mv`, `mz`,
`python3`, ...); omitting it means `default`. `engineVersion` is the
game's dotted-numeric runtime target inside that context.

`pluginVersion` in a game's config is optional and different from a
plugin's own `pluginVersion`: it's a comma-separated allowlist of exact
versions and/or `lo-hi` ranges of *plugin builds* this specific game
permits, letting a game exclude plugin revisions with known bugs.
`execFile` is optional — the
specific file to run within the folder, for engines that need one.

`options` is a generic, opaque-to-enginehost bag of engine-specific
settings, passed straight through to the resolved plugin without being
inspected. The real motivating case: an RGSS game (RPG Maker XP/VX/VX
Ace) can need a specific Ruby/Marshal version to correctly deserialize
its own scripts, or a decryption key, or RTP info — none of which
enginehost has any business understanding. Each plugin defines its own
real option keys.

Nothing about this file's contents, or the folder it lives in, is ever
copied or moved by enginehost. It reads the folder in place and runs the
game from there.

A caller can pass the same JSON shape inline via `LAUNCH`'s `config`
extra. The folder's own `enginehost.json` is authoritative: inline JSON
may append fields the file omitted, including missing keys inside
`options`, but can never override a value already present in the file.
When the folder has no config file, the inline config is used by itself.

## The basic UI

There's also a plain pick-a-folder-and-launch screen, and a (currently
unimplemented) controller config screen, for when there's no caller —
just someone holding the device. The Intent contract above is the
primary, intended way in.

## Plugins

Plugins are separately installed apps. First-party plugin source may live
under this repository's `plugins/` directory or in a dedicated upstream fork;
source layout does not change the Android boundary. A RetroArch-cores-style
installer is a possible future addition. enginehost never links engine code
into the host APK — it discovers whatever plugin APKs are installed through
Android's `PackageManager`.

An enginehost plugin must contain or embed an actual portable implementation
of its engine/runtime. Delegating a Windows executable to Wine, Box64, or a
generic PC compatibility app does not implement an enginehost plugin. Engines
without a viable native interpreter remain explicitly unsupported until that
interpreter is started. An interpreter does not need perfect compatibility to
ship: early plugin versions may implement only a useful subset, as long as the
limitations and supported engine versions are declared honestly. Later
co-installable plugin builds can extend that implementation and compatibility.

A plugin declares:
- The `dev.enginehost.plugin.RUN` intent-filter on an exported activity.
- `<meta-data>` for `dev.enginehost.plugin.engine` and `.pluginVersion`.
- `dev.enginehost.plugin.capabilities`, normally an `@raw` JSON resource,
  listing every bundled runtime and the exact versions/ranges it supports.
  The old single `.engineVersion` metadata remains supported as a legacy
  exact-version capability in the `default` context.

Capability schema version 1:

```json
{
  "schemaVersion": 1,
  "capabilities": [{
    "id": "mz-1.8.0",
    "engineContext": "mz",
    "runtimeVersion": "1.8.0",
    "supportedVersions": ["1.7.0"],
    "supportedRanges": [{ "min": "1.7.1", "max": "1.8.0" }]
  }]
}
```

Compatibility is never inferred from numerical proximity. A capability
supports its own `runtimeVersion` plus only the versions/ranges it
explicitly declares. Resolution prefers an exact bundled runtime, then
the narrowest declared compatibility span, then the newest plugin build
allowed by the game's optional `pluginVersion` allowlist.

When invoked, it receives `path`, `engineContext`, the requested
`engineVersion`, the selected `runtimeVersion`, and `capabilityId`. If the
game config supplied them it also receives `execFile` and `options` (the
raw JSON string — each plugin parses its own keys).

Dedicated engine plugin forks keep their upstream history and never live in
this host repository. Their `plugin-core` branch contains the portable Android
wrapper changeset; release branches start at relevant engine releases and
apply that changeset. Incompatible runtime lines use co-installable package
slots and explicit capabilities so builds can be installed side by side.

Current plugin repositories:

- [Ren'Py](https://github.com/bi0shacker001/enginehost-renpy-plugin): versioned
  8.5.3, 8.3.2, and 8.2.1 Android branches.
- RPG Maker family:
  [mkxp-z (XP/VX/VX Ace)](https://github.com/bi0shacker001/enginehost-rpgmaker-mkxp-z-plugin),
  [EasyRPG (2000/2003)](https://github.com/bi0shacker001/enginehost-rpgmaker-easyrpg-plugin), and
  [MV/MZ](https://github.com/bi0shacker001/enginehost-rpgmaker-mv-mz-plugin).
- [KiriKiri](https://github.com/bi0shacker001/enginehost-kirikiri-plugin)

## Status

The host contract, authoritative config merge, capability resolver, and APK
dispatch are implemented and CI-built. Engine implementations and Android
plugin releases are developed in their engine-specific forks. Legacy staged
plugin sources under `plugins/` are being migrated out and are not part of the
host's final repository boundary. Ren'Py 8.5.3, 8.3.2, and 8.2.1 branches
currently produce APKs in the Ren'Py plugin repository.
