# enginehost

A straightforward multi-engine host for VN/RPG-Maker-family games on
Android. Runtime families are delivered as independently versioned engine
bundles, including KiriKiri, Ren'Py, RPG Maker, Buriko/Ethornell, CatSystem2,
CMVS, Flash/AIR, Twine, and Godot.

enginehost is a centralized interpreter host built to be driven
*programmatically* by another app that already knows what game it wants
to run and where it lives on disk. It is not a self-contained catalog or
game-library application.

Callers can open the first-class config creator with action
`dev.enginehost.CONFIGURE`, the same `path` extra, and an optional `config`
starting point. They can query installed support through the read-only URI
`content://dev.enginehost.capabilities/installed`; bundle selection remains
Enginehost's responsibility.

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

`title` is optional: the name the launch screen shows, when the game's
files state one (a Twine story's name). `saveFolder` is optional and only
meaningful for engines whose runtime has no save naming of its own (HTML
games, RPG Maker MV/MZ, Flash/AIR): the single folder name their saves go
under beneath the engine's save root. Enginehost derives it from what the
engine itself would use, a story's title or the game's folder name, so it is
the same on every device that has the game.

A folder with no `enginehost.json` is not a dead end. Detection reads the
engine, version, entry file, title and save folder from the folder's own
files; when all of that is evident the config is written and the launch
continues, and only a folder that leaves a question open is shown in the
config editor with detection prefilled.

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
game folder, scans the complete granted tree for conservative engine/version
evidence, exposes the complete host contract, validates before writing, and
preserves unknown top-level fields so newer plugin settings are not destroyed.
Detection only prefills empty fields; it never replaces an existing game-owned
setting. The system folder grant is sufficient for editing and detection.

Testing additionally requires a provider that maps to a native primary-storage
path. Because native interpreters traverse the live tree through filesystem
paths, Android 11 and newer also require the per-app native-file-access grant.

The home screen also has a deliberately minimal “pick folder and run” action
for plugin testing. It is not a library or catalog; programmatic launch through
`dev.enginehost.LAUNCH` remains the intended runtime interface.

Controller settings provide global remapping for D-pad, face and shoulder
buttons, sticks, triggers, VN actions such as skip/auto/history and quick
save/load, multiple connected controllers, hot-plug, and controller rumble.
Engine bundles receive normalized actions while unconsumed raw Android input
continues to their render view.

## Plugins

Plugins are separately installed, versioned `*.enginehost.tar.xz` bundles, not
Android packages or separate apps. Enginehost verifies and extracts them into
private storage, then loads an approved entrypoint inside its own `:runtime`
process, under Enginehost's UID, so the
host owns the one game-folder permission and all configuration. The separate
process contains native crashes and is terminated after a session; it is not a
security sandbox from an approved plugin, which is why Plugin Trust is a hard
gate.

An enginehost plugin must contain or embed an actual portable implementation
of its engine/runtime. Delegating a Windows executable to Wine, Box64, or a
generic PC compatibility app does not implement an enginehost plugin. Engines
without a viable native interpreter remain explicitly unsupported until that
interpreter is started. An interpreter does not need perfect compatibility to
ship: early plugin versions may implement only a useful subset, as long as the
limitations and supported engine versions are declared honestly. Later
co-installable plugin builds can extend that implementation and compatibility.

Each bundle's internally signed manifest declares its API version, Java
entrypoint, engine family, plugin build version, canonical GitHub origin,
payload hashes, dex files, and every runtime capability it supports. The
repository's P-256 public key is pinned before catalog data is accepted.

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
    "supportedSeries": ["1.8"],
    "supportedRanges": [{ "min": "1.7.1", "max": "1.8.0" }]
  }]
}
```

Compatibility is never inferred from numerical proximity. A capability
supports its own `runtimeVersion` plus only the exact versions, ranges, or
dotted series it explicitly declares. A Ren'Py `8.2` series, for example,
matches every precisely recorded `8.2.*` game version but not `8.3`. Resolution prefers an exact bundled runtime, then
the narrowest declared compatibility span, then the newest plugin build
allowed by the game's optional `pluginVersion` allowlist. A capability is
eligible only when its optional `runtimeComponents` exactly satisfy every
entry in the game's `runtimeRequirements` map.

The entrypoint implements the Java interface in the publishable `plugin-api`
module (plugins depend on it as `compileOnly`). It receives a host-owned render
container, lifecycle callbacks, the game path, engine/context/requested and
selected runtime versions, capability ID, runtime requirements, executable,
raw options JSON, save/cache directories, logging/finish services, and an
optional path-confined filesystem facade. Native engines may use the canonical
game path directly because code executes under Enginehost's UID.

### Downloadable runtime bundles

A runtime bundle is a self-verifying XZ-compressed tar archive with a unique
bundle ID and explicit capability document. Native interpreters and embedded
language ABIs live inside that bundle. An engine-family bundle can contain
several internally namespaced runtimes—for example both Ruby 1.9.2 and Ruby
3.1.3—and the host selects the matching capability deterministically. Different
plugin/runtime releases remain co-installable without Android package installs.

Each plugin repository's complete GitHub Releases history is its catalog. Every
published release carries an `enginehost-release.json` browsing envelope plus
the independently signed engine-bundle assets, and the project retains old releases. Enginehost
can enumerate, filter, download, verify, and offer any compatible release rather
than treating only “latest” as useful. See
[`docs/plugin-catalog.md`](docs/plugin-catalog.md) and the release-manifest schema.

Dedicated engine plugin forks keep their upstream history and never live in
this host repository. Their `plugin-core` branch contains the portable Android
wrapper changeset; release branches start at relevant engine releases and
apply that changeset. Incompatible runtime lines use co-installable package
slots and explicit capabilities so builds can be installed side by side.

Current plugin repositories:

- [Ren'Py](https://github.com/Droidtop/enginehost-renpy-plugin): versioned
  8.5.3, 8.3.2, and 8.2.1 Android branches.
- RPG Maker family:
  [mkxp-z (XP/VX/VX Ace)](https://github.com/Droidtop/enginehost-rpgmaker-mkxp-z-plugin),
  [EasyRPG (2000/2003)](https://github.com/Droidtop/enginehost-rpgmaker-easyrpg-plugin), and
  [MV/MZ](https://github.com/Droidtop/enginehost-rpgmaker-mv-mz-plugin).
- [KiriKiri](https://github.com/Droidtop/enginehost-kirikiri-plugin)
- [Buriko/Ethornell (OpenBGI)](https://github.com/Droidtop/enginehost-buriko-plugin).
  AUGUST is a game studio whose applicable Windows titles use this engine
  family; configure those games as `engine: buriko`, rather than inventing a
  duplicate `august` runtime family.
- [CatSystem2](https://github.com/Droidtop/enginehost-catsystem2-plugin)
- [CMVS](https://github.com/Droidtop/enginehost-cmvs-plugin)
- [Flash/AIR (Ruffle)](https://github.com/Droidtop/enginehost-flash-air-plugin)
- [Twine](https://github.com/Droidtop/enginehost-twine-plugin)
- [Godot](https://github.com/Droidtop/enginehost-godot-plugin)

## Status

The host contract, authoritative config merge, capability resolver, signed
bundle installer, trust gate, and in-process runtime loader are implemented.
Engine implementations and Android
plugin releases are developed in their engine-specific forks. Legacy staged
plugin sources under `plugins/` are being migrated out and are not part of the
host's final repository boundary. See each plugin repository's capability
manifest for the exact engine contexts and versions that a particular bundle
claims to support.

## Third-party assets

- The launch screen sets the game title in Gantari (Lafontype), licensed
  under the SIL Open Font License 1.1; see `third_party/gantari/OFL.txt`.
