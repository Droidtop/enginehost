# enginehost

A straightforward multi-engine host for VN/RPG-Maker-family games on
Android. Runtime families are delivered as independently versioned plugin
APKs, including KiriKiri, Ren'Py, RPG Maker, Buriko/Ethornell, CatSystem2,
CMVS, Flash/AIR, Twine, and Godot.

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
  "engineVersion": "3.0",
  "runtimeRequirements": { "ruby": "1.9.2" },
  "pluginVersion": "1.0.0,1.2.0-1.4.0",
  "execFile": "Game.exe",
  "options": {
    "rtpPaths": ["/storage/emulated/0/RTP/RPGVXAce"]
  }
}
```

`engine` and `engineVersion` are required. `engine` names the plugin
family (`renpy`, `rpgmaker`, `godot`, ...). `engineContext` is an optional,
plugin-defined compatibility line within that family (`mv`, `mz`,
`python3`, ...); omitting it means `default`. `engineVersion` is the
game's dotted-numeric runtime target inside that context.

`runtimeRequirements` optionally selects exact embedded dependencies that are
independent of the engine version. Its keys are plugin-defined and its values
are dotted numeric versions. For example, VX Ace/RGSS3 games normally require
Ruby 1.9.2, while a compatibility-oriented plugin may also offer Ruby 3.1.3.
The host only considers capabilities advertising every requested component.
This field participates in resolution; `options` does not.

`pluginVersion` in a game's config is optional and different from a
plugin's own `pluginVersion`: it's a comma-separated allowlist of exact
versions and/or `lo-hi` ranges of *plugin builds* this specific game
permits, letting a game exclude plugin revisions with known bugs.
`execFile` is optional — the
specific file to run within the folder, for engines that need one.

`options` is a generic, opaque-to-enginehost bag of post-resolution
engine-specific settings, passed straight through to the resolved plugin
without being inspected. Examples include a decryption key, RTP paths,
renderer switches, or engine compatibility toggles. Each plugin defines its
own real option keys.

Nothing about this file's contents, or the folder it lives in, is ever
copied or moved by enginehost. It reads the folder in place and runs the
game from there.

A caller can pass the same JSON shape inline via `LAUNCH`'s `config`
extra. The folder's own `enginehost.json` is authoritative: inline JSON
may append fields the file omitted, including missing keys inside
`options`, but can never override a value already present in the file.
When the folder has no config file, the inline config is used by itself.

## On-device tools

The first-class UI is a config creator built around Android's system folder
picker. It opens or creates the authoritative `enginehost.json` in a chosen
game folder, exposes the complete host contract, validates before writing, and
preserves unknown top-level fields so newer plugin settings are not destroyed.
It can also test the configuration immediately when the selected provider maps
to a native primary-storage path.

The home screen also has a deliberately minimal “pick folder and run” action
for plugin testing. It is not a library or catalog; programmatic launch through
`dev.enginehost.LAUNCH` remains the intended runtime interface.

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
    "runtimeComponents": { "javascript": "1.8.0" },
    "supportedVersions": ["1.7.0"],
    "supportedRanges": [{ "min": "1.7.1", "max": "1.8.0" }]
  }]
}
```

Compatibility is never inferred from numerical proximity. A capability
supports its own `runtimeVersion` plus only the versions/ranges it
explicitly declares. Resolution prefers an exact bundled runtime, then
the narrowest declared compatibility span, then the newest plugin build
allowed by the game's optional `pluginVersion` allowlist. A capability is
eligible only when its optional `runtimeComponents` exactly satisfy every
entry in the game's `runtimeRequirements` map.

When invoked, it receives `path`, `engineContext`, the requested
`engineVersion`, the selected `runtimeVersion`, and `capabilityId`. If the
game config supplied them it also receives `runtimeRequirements`, `execFile`,
and `options` (raw JSON strings — each plugin parses its own keys).

### Downloadable runtime bundles

A runtime bundle is a signed plugin APK release with a unique Android package
slot and an explicit capability document. Native interpreters and embedded
language ABIs are compiled into that APK; they are not loose executable files
downloaded and loaded from game storage. An engine-family bundle can contain
several internally namespaced runtimes—for example both Ruby 1.9.2 and Ruby
3.1.3 in one RPG Maker plugin APK—and the host selects the matching capability
deterministically from the game config. This avoids installing one plugin per
embedded language version. A caller may provide its own catalog/download UI;
Android installation still follows the device's normal package-install
authorization flow.

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
- [Buriko/Ethornell (OpenBGI)](https://github.com/bi0shacker001/enginehost-buriko-plugin).
  AUGUST is a game studio whose applicable Windows titles use this engine
  family; configure those games as `engine: buriko`, rather than inventing a
  duplicate `august` runtime family.
- [CatSystem2](https://github.com/bi0shacker001/enginehost-catsystem2-plugin)
- [CMVS](https://github.com/bi0shacker001/enginehost-cmvs-plugin)
- [Flash/AIR (Ruffle)](https://github.com/bi0shacker001/enginehost-flash-air-plugin)
- [Twine](https://github.com/bi0shacker001/enginehost-twine-plugin)
- [Godot](https://github.com/bi0shacker001/enginehost-godot-plugin)

## Status

The host contract, authoritative config merge, capability resolver, and APK
dispatch are implemented and CI-built. Engine implementations and Android
plugin releases are developed in their engine-specific forks. Legacy staged
plugin sources under `plugins/` are being migrated out and are not part of the
host's final repository boundary. See each plugin repository's capability
resource for the exact engine contexts and versions that a particular APK
claims to support.
