# Enginehost: the launch contract and how it works

This page holds the full reference that used to be the README: the launch Intent, the
`enginehost.json` file, the on-device tools and the core system. For what Enginehost is
and how to install it, see the [README](../README.md).

A straightforward multi-engine host for VN/RPG-Maker-family games on
Android. Runtime families ship as independently versioned engine bundles,
including KiriKiri, Ren'Py, RPG Maker, Buriko/Ethornell, CatSystem2, CMVS,
Flash/AIR, Twine, and Godot.

enginehost is a centralized interpreter host with two equally supported
front doors, and neither is a second-class citizen. Used directly, it has
its own game library, config creator, core catalog and trust screens,
controller mapping, and settings — someone who only ever installs
enginehost has a complete, usable application. Requiring a second app in
order to use this one would be elitist, and the earlier framing of the
built-in picker as "minimal, just for testing" is retired (directed
2026-09-01). Driven programmatically, every one of those capabilities is
also reachable over the Intent contract below, so a launcher such as
droidtop can do the whole job without a hand-off: name the game, supply its
config inline or open the config creator for it, resolve a runtime, and
install a bundle.

The design rule that follows: any flow that exists in the UI must have a
programmatic equivalent, and vice versa. A caller that already knows what
it wants should never be forced through a screen, and a person without a
caller should never be missing a feature.

Callers can open the first-class config creator with action
`dev.enginehost.CONFIGURE`, the same `path` extra, and an optional `config`
starting point. They can query installed support through the read-only URI
`content://dev.enginehost.capabilities/installed`; bundle selection remains
Enginehost's responsibility.

## The contract

The names below (the actions, the extras, the `enginehost.json` keys) are held once, in `Droidtop/droidtop-shared`'s
`:launch-contract` (pinned as `vendor/droidtop-shared`, compiled in as source), and used by droidtop and by this app's code;
`LaunchContractTest` checks the manifest and this page against them, so a rename fails a test instead of a launch.

Fire an Intent:

```
action: dev.enginehost.LAUNCH
extra "path": absolute path to the game's folder
extra "config" (optional): a raw enginehost.json-shaped JSON string
```

### App launch access

Every app's `LAUNCH` (and its `dev.enginehost.SCAN`) goes through the person's
decision for that app, in Settings > App launch access: allowed, blocked, or
asked on its first launch. Browsers and remote-control or automation apps start
blocked; everything else starts asked. **droidtop is no exception** (owner,
2026-10-17: "Any forced approval violates the user's right to choice"): its
first launch asks, and it is listed, allowed and blocked like any app. Enginehost
still verifies droidtop's signing certificate, but only to name it truthfully,
never to let it past a decision. Two calls answer droidtop alone by that
signature, because they identify rather than launch: `dev.enginehost.END_GAME`
(ending the game it started) and the read-only running-state query of the
capabilities provider. Launches from inside Enginehost (Play, Test, a launch
resuming after its core was installed) are the person's own and are not asked
about.

One game runs at a time. Launching the game that is already running brings it
back exactly as it was; launching any other game ends the running one first,
the way leaving it does, and starts the new one. Address the action, not a
component: the activity that answers it is not part of the contract.

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

`engine` and `engineVersion` are required. `engine` names the core
family (`renpy`, `rpgmaker`, `godot`, ...). `engineContext` is an optional,
plugin-defined compatibility line within that family (`mv`, `mz`,
`python3`, ...); omitting it means `default`, or, when no core offers
`default` for the engine and its cores offer one line only, that line (owner,
2026-10-22: every core so far declares `standard`, and a config without a
context matched none of them). An engine with several lines (RPG Maker's `mv`,
`mz`, `xp`, ...) still needs it named. `engineVersion` is the
game's dotted-numeric runtime target inside that context.

`runtimeRequirements` optionally selects exact embedded dependencies that are
independent of the engine version. Its keys are plugin-defined and its values
are dotted numeric versions. For example, VX Ace/RGSS3 games normally require
Ruby 1.9.2, while a compatibility-oriented core may also offer Ruby 3.1.3.
A capability is considered when it carries every requested component at that
version, or asks for it by name while the game runs (`loadsComponents`), whatever its version
(docs/engine-bundle-format.md, "Which capabilities load which components").
This field participates in resolution; `options` does not.

`pluginVersion` in a game's config is optional and different from a
core's own `pluginVersion`: it's a comma-separated allowlist of exact
versions and/or `lo-hi` ranges of *core builds* this specific game
permits, letting a game exclude core revisions with known bugs.
`execFile` is optional — the
specific file to run within the folder, for engines that need one.

`root` is optional: the folder inside the game folder that holds the game, for a
game folder that wraps it beside other folders (MGQ Paradox's "MGQ Paradox" holds
"MGQ Paradox 3.06" and "Pictures"; Droidtop/tracker#636). Detection looks in the
folder itself and then one level down, and writes `root` when it found the game
there. Only one game may be found one level down: two make the folder a collection,
which is no game. The engine starts in that folder, for every core: the runtime's path extra
(`dev.enginehost.runtime.PATH`, which cores read) names it, and `execFile` is
handed to the core relative to it. The game folder stays the game's identity:
its library entry, Game setup, overrides, controller settings, crash watch and
save folders are keyed to it, and it travels to host code as
`dev.enginehost.runtime.GAME`. `execFile` stays relative to the game folder and
must lie inside `root`; `root` must be a relative path with no `.` or `..` parts.

`title` is optional: the name the launch screen shows, when the game's
files state one (a Twine story's name). `saveFolder` is optional and only
meaningful for engines whose SYSTEM save location has no per-game name of its
own (HTML, Flash/AIR and RPG Maker MV/MZ, whose browser runtime has one
localStorage, and CatSystem2, which saves under the user profile): the single
folder name their saves go under beneath the engine's save root, so that two
games never share one store. Enginehost derives it from what the engine itself
would use, a story's title or the game's folder name, so it is stable across
devices.

Enginehost does not change where an engine saves. Its save folder is only what
an engine's system locations mean (a user profile, AppData, `~/.renpy`, Godot's
`user://`, a browser's storage). An engine that saves beside the game on its
desktop original keeps doing so here: KiriKiri, Buriko, CMVS, NScripter, and RPG
Maker 2000/2003 and XP/VX/VX Ace save in the game folder, and a `saveFolder` an
older config names for them is not used. Before this was settled (2026-09-17)
some of them were given a host-named folder instead; when a launch finds save
files there that the game folder does not have, it offers to copy them into the
game folder, never deleting or overwriting anything, or to leave them.

The in-game menu's Save location says where a game's saves really are: the
game's own folder for an engine that saves beside the game, the per-game
folder for one Enginehost names, and the save root for an engine that names
its own folder inside it (Ren'Py, Godot).

**Where a game's config lives (owner, 2026-10-23).** "Unless explicitly tagged
to be added to the game folder, it should go in enginehost's data. Users should
be allowed to save it there later, but the point is, the creation UI for configs
is a HELPER." And on files already in game folders: "Import them and leave them
in place. The game folder one takes precedence, and saves made in the UI will
not overwrite the one in the game folder unless the user explicitly flags to do
so, and with a warning". So a game's config is, in order (`GameConfigs`):

1. the game folder's own `enginehost.json`, when there is one;
2. else the setup the person made in Game setup, kept in Enginehost's own
   storage keyed by the game folder (`GameConfigStore`), never in the game folder;
3. else a caller's inline config and detection, as below.

The person's per-game overrides (library database) sit above all of them. Save in
Game setup keeps the setup in Enginehost. It writes the game folder's
`enginehost.json` only when the person turns on "Also save to the game folder",
and when one is already there it asks before replacing it. A folder's
`enginehost.json` is copied into Enginehost's storage the first time it is read,
and left in place. Nothing else writes or edits a game folder's `enginehost.json`:
not Play, not adding games, not Keep, not a caller's launch (this replaces the
2026-09-25 rule that Save always wrote it). A game with no config of its own is still not
a dead end. Detection reads the engine, version, entry file, title and save
folder from the folder's own files; when all of that is evident the launch runs
on that detected config in memory, with a caller's inline config filling what
detection left open, and only a folder that leaves a question open is shown in
the config editor with detection prefilled. The game's own screen reads the
same detected config (`GameRunner.launchConfig`), so such a game shows the core
it would run on, whether that core is approved, and its sections, exactly as a
game with an `enginehost.json` does.

Game setup's Test never touches that file. It stores what is on screen as the
game's testing configuration, in Enginehost's own storage keyed by the game
folder, and runs the game with it (the user, 2026-09-25: a test that does not
work must not replace a working config). Game setup and the game's own screen
then say a testing configuration is pending, one press from Keep (it becomes
the game's setup in Enginehost; the folder is not written) or Discard (the working config, never touched,
runs again). A new Test replaces the pending one. Every other launch, Play or
another app's `dev.enginehost.LAUNCH`, runs the working config.

`options` is a generic, opaque-to-enginehost bag of post-resolution
engine-specific settings, passed straight through to the resolved core
without being inspected. Examples include a decryption key, RTP paths,
renderer switches, or engine compatibility toggles. Each core defines its
own real option keys.

Nothing about this file's contents, or the folder it lives in, is ever
copied or moved by enginehost. It reads the folder in place and runs the
game from there.

A caller can pass the same JSON shape inline via `LAUNCH`'s `config`
extra. The folder's own `enginehost.json` is authoritative: inline JSON
may append fields the file omitted, including missing keys inside
`options`, but can never override a value already present in the file.
When the folder has no config file, the inline config is used by itself.

### A caller's accent: `accent` (2026-10-28)

`LAUNCH` may carry a string extra `accent`, `"#RRGGBB"`: the caller's own accent
colour (droidtop sends the one its ES-DE theme resolved). Enginehost draws the screens of
the session that launch starts in it, through the same contrast guard as Settings >
Appearance (`Accent`, `AccentPalette`), so the two apps look like one while droidtop
drives. It lasts until Enginehost's process ends and is never stored. A launch without it
is drawn in the person's own choice. Anything but six hex digits is ignored.

### A caller's save folder: `saveRoot` (2026-10-17)

Owner, Droidtop/tracker#580: "we can pass data back and forth using the API,
so we'll pass the save folder to the game using the normal config syntax, so
the game can load from and save to it." For a launcher that keeps a game's
saves itself (droidtop's Steam Cloud sync), the inline config may carry one more
key:

```json
{ "saveRoot": "/storage/emulated/0/Droidtop/cloud/123456" }
```

- **What it is.** The game's own save folder, exactly as the caller keeps it:
  the folder the game's save files are in, laid out the way the cloud or other
  source that provides them lays them out. Enginehost is the abstraction; the
  caller needs to know nothing about any engine (owner, 2026-10-18: "What we
  provide is a link to the saves, as they are provided to us. This SHOULD be an
  exact mapping of whatever the save root for a game is."). For that launch the
  engine uses it as its save directory itself and adds no folder of its own:
  Ren'Py's savedir is the folder (no `save_directory` beneath it), Godot's
  `user://` is the folder (no `godot/app_userdata/<project>`), and an engine
  whose per-game folder Enginehost names (HTML, Twine, RPG Maker MV/MZ,
  Flash/AIR, CatSystem2) gets the folder as that per-game folder. Cores receive
  it as `EngineHost.gameSaveDirectory()` (plugin transport) or the
  `dev.enginehost.runtime.GAME_SAVE_PATH` extra (activity transport); a core
  too old to read it puts its own folder inside, as it does under a save root.
- **Inline only, one launch only.** It is never read from a folder's
  `enginehost.json`, never written anywhere, and never changes Settings > Saves.
- **Who may pass it.** Any caller the person has allowed in App launch access;
  droidtop has no standing of its own there (see "App launch access"). A launch
  from inside Enginehost has no caller and so no `saveRoot`.
- **What is refused.** A relative path, a path outside shared storage
  (`/storage/emulated/<user>` or a removable volume), or one inside an app's
  `Android/data` or `Android/obb`: the launch runs on the person's own save
  location, as if the key were absent (a `savesSynced` launch gets its own
  folder there, below). A folder that does not exist is made; one that cannot be
  made or written is treated the same way.
- **Saves synced by the caller: `savesSynced` (2026-10-22).** A caller that
  keeps the game's saves synced sends `"savesSynced": true` beside `saveRoot`.
  Owner, Droidtop/tracker#580: the flag says the game "is connected to a system
  that automatically syncs saves", "meant to ensure it doesn't just use the user
  default", and "enginehost should write saves to the user folder, and tell
  droidtop when it doesn't work. That's not the user's problem". On a flagged
  launch whose `saveRoot` is refused or missing, the person is asked nothing and
  the game saves in a folder of its own in the person's location,
  `<save root>/synced-saves/<game folder name>-<8 hex digits of its path>`
  (`<save root>` is the engine's root under Settings > Saves, not its `saves`
  folder). That folder is used exactly as a taken `saveRoot` would be, so it
  directly holds the game's save files, in the same shape (owner's choice,
  2026-10-22). The same game launched without the flag uses the person's
  ordinary layout, so its saves there and in `synced-saves` are separate. The
  flag changes nothing for an engine that saves beside the game.
- **Engines that save beside the game** (KiriKiri, Buriko, CMVS, NScripter, RPG
  Maker 2000/2003 and XP/VX/VX Ace) ignore it, as they ignore the save root:
  Enginehost never changes where an engine saves. Their `GAME_ENDED` names the
  game folder, which is where their saves are.
- **Game end.** When a game launched by an identified caller ends, Enginehost
  sends that caller's package, and no other, the broadcast
  `dev.enginehost.GAME_ENDED` once, with the game folder (`path`), where the
  game's saves are (`saveFolder`: the caller's folder when it was taken, else the
  person's own save folder, or the game folder for an engine that saves beside
  the game; for an engine that names its own folder inside the save folder,
  Godot's `user://` such as `godot/app_userdata/<project>` or Ren'Py's save
  directory, that folder, as the engine reported it through
  `EngineHost.reportSaveDirectory` (`EngineSaveFolders`); a core that does not
  report it leaves the save folder itself) and how it ended (`reason`: `exited` when the
  game closed, by its own Quit, the in-game menu or `END_GAME`; `crashed` when it
  crashed or failed, or Android stopped it: low memory, a system service it used
  stopping, too much use of the device). On a launch flagged `savesSynced` whose `saveRoot` was not
  taken, it also carries `saveRootRefused`, one of `missing`,
  `outside-shared-storage`, `android-data`, `volume-root` or `not-writable`, and
  `saveFolder` is then the `synced-saves` folder above; `saveRootRefused` is
  absent when `saveRoot` was taken. A launch from inside Enginehost, or from a caller Android
  does not identify, reports to nobody. A launch that first went to the catalog
  or trust screen for its core carries no caller once it resumes, and reports to
  nobody either (`LaunchActivity.reportGameEnded`).

## droidtop's plugin interface (Droidtop/tracker#547)

The owner, 2026-10-23: "The two applications should detect and work with each
other over the plugin API so no matter what, saves go where they're needed".
Enginehost is one of droidtop's `app_interface` plugins (droidtop
`docs/plugin-api.md` 1.3 and A7a). Enginehost never assumes droidtop exists:
without it, none of this runs and nothing waits for it.

- **The manifest** is the asset `assets/droidtop-plugin.json`: kind
  `app_interface`, the app `dev.enginehost` and its service
  `dev.enginehost.DroidtopInterfaceService`. It provides `saves.roots@1` and
  `engines.cores@1`, and asks for `saves.exchange` and `library.read`, each with its reason.
  Its `version` moves whenever the asset changes.
- **The service** answers the action `dev.droidtop.plugin.APP_INTERFACE`
  with droidtop's `IPluginRuntime` (its AIDL copied unchanged under
  `app/src/main/aidl/dev/droidtop/pluginhost`). Binder numbers each method by its
  place in the file, so the copy has to follow every change droidtop makes to it,
  stubs included: one method inserted on droidtop's side (droidtop 716ee6d5 added
  `inputToken` on 2026-10-29) moved every later call onto the wrong method here,
  droidtop's `handle` arriving as `startJob`. It answers **only droidtop**:
  the calling uid must be `dev.droidtop.app`, signed by a certificate
  pinned in `DroidtopLink.DROIDTOP_CERTS` (droidtop's release key; a debug build
  of droidtop is signed differently and is not answered). Every other caller gets
  `PERMISSION_DENIED`, or `false` from `loadContained`. `loadContained` keeps the
  broker droidtop hands over (`DroidtopLink`); `unloadPlugin` drops it. No
  screens, jobs, relays or `invoke`.
- **`saves.roots` `roots`** (droidtop's #546 design, section 8; the shape is on
  Droidtop/tracker#547). It replaced `saves.locations@1` on 2026-10-23: droidtop asks
  one thing, under one mechanism per job. `{games: [{entryId, folder, engine, context?}]}`
  is answered with `{default, families, games: [{entryId, folder?}]}`:
  - `default`: Enginehost's save root, `<root>/saves` (`SaveLocationStore`), where it is
    whether or not it exists yet. Nothing is created to answer.
  - `families`: the save root of each engine the person gave its own folder in
    Settings > Saves, keyed by Enginehost's engine name, only where it differs from
    `default`.
  - `games[].folder`: the game's own folder inside its root, only where Enginehost names
    it: an engine whose folder the host names (`SaveFolders.Place.NAMED_BY_THE_HOST`), or
    the game's `synced-saves` folder when a synced launch made one. Left out where the
    engine names its own folder or saves beside the game; droidtop's save rules find
    those.

  Roots only, never files. droidtop checks every path (C10, D12) before using it and
  writes nothing because of this answer alone.
- **`engines.cores` `list_cores {}`** (Droidtop/tracker#752: an engine game's own runner
  comes before Wine, and droidtop reads Enginehost's cores instead of guessing). It answers
  `{cores, catalogCheckedAtMs}`, one entry per core line, installed or installable
  (`EngineCores`):
  - `id`, the bundle id, stable across versions;
  - `engine` and `contexts`, Enginehost's names, as in `saves.roots` and the launch config,
    with `engines` listed too for a bundle that runs several (the HTML runtime);
  - `label`, the line as the Cores screen names it ("Ren'Py 8.3.x");
  - `engineVersions {series, any}`, the game versions it runs, `any` for a core that takes
    every version;
  - `rows`, the detection hints: the ids of the engines-database rows (droidtop-platforms
    `engines-database.json`, which droidtop detects with too) whose `enginehost` mapping
    the core serves;
  - `installed`, `installedVersion` (installed only), `availableVersion` (when the official
    catalog lists it) and `updateAvailable`.
  It reads the installed bundles and the cached catalog only, and never fetches;
  `catalogCheckedAtMs` says how old the catalog part is.
- **When the list changes** (a core installed or removed, a catalog stored), Enginehost tells
  droidtop with `engines report_changed {}` while droidtop is connected, and droidtop asks
  `list_cores` again; nothing polls. A droidtop that does not yet answer the call refuses it,
  and Enginehost logs that and carries on.
- **Before a launch** droidtop did not make, whose caller names no `saveRoot`
  (a launch from inside Enginehost, or from any other app), Enginehost asks
  droidtop's `saves where {path}` (`DroidtopSaves`). When droidtop keeps the
  game's saves, its `saveRoot` and `synced` are used exactly as a caller's own
  `saveRoot` and `savesSynced` would be (see "A caller's save folder"), so saves
  land in one place whichever app started the game. droidtop may sync the
  saves down first, and may ask the person about a conflict, so the launch
  screen waits up to two minutes. With no answer, or `known: false`, the game
  saves in Enginehost's own layout, as without droidtop.
- **After any game ends**, Enginehost tells droidtop with `saves report_changed
  {path, saveFolder, reason, endedAtMs}`, `saveFolder` being what `GAME_ENDED`
  would name. A game droidtop launched gets both: droidtop treats the report as
  idempotent, and a broadcast can be lost (plugin lane, #547).
- **droidtop's engine games** are registered on Enginehost's Home
  (`DroidtopLibrary`, `library.read list_engine_games`) when droidtop connects
  and when Home comes back, at most every ten minutes. Only the game's folder
  is taken. Its config stays the person's, made in Game setup, and nothing is
  written into its folder. A game Enginehost registered this way is dropped
  only when droidtop's complete list no longer has it **and** its folder is
  gone, because dropping its row also drops the person's own choices for it.
  A game the person added themselves is never dropped.
- **Without a broker** (droidtop has not bound Enginehost since it started),
  Enginehost knocks on droidtop's door (`IAppInterfaceDoor.knock`, action
  `dev.droidtop.plugin.APP_INTERFACE_DOOR`) and waits up to five seconds for
  droidtop to bind it and hand one over.
- **When Enginehost starts** (its default process), it knocks once in the same way, so apps with an
  application-interface layer announce themselves (owner, Droidtop/tracker#547). The answer is not
  waited on: droidtop either binds Enginehost, or says itself that Enginehost is not added yet.

## On-device tools

The first-class UI is a config creator built around Android's system folder
picker. It opens a chosen game folder's setup (its own `enginehost.json`, else the
setup kept in Enginehost, see "Where a game's config lives"), scans the complete granted tree for conservative engine/version
evidence, exposes the complete host contract, validates before writing, and
preserves unknown top-level fields so newer core settings are not destroyed.
Detection only prefills empty fields; it never replaces an existing game-owned
setting. The system folder grant is sufficient for editing and detection.

Testing additionally requires a provider that maps to a native primary-storage
path. Because native interpreters traverse the live tree through filesystem
paths, Android 11 and newer also require the per-app native-file-access grant.

The home screen is the library: every game added there, found by a scan, or
started through either front door, filterable by engine, platform, status,
folder and text and sortable by name, date added, recent play and size (most
recently played first by default). Home has one way to add games, **Add
games**, which offers one game (by its folder; it starts once added) or a
folder of games (a scan that also finds Windows and Linux builds and archives,
tags them by platform, and adds the ones chosen; see docs/enginehost.md, "The
library, the scanner and its filters"). A game's
card opens that game's own screen, and that screen opens with the thing: a
cover plate fills the freed space between the header and the actions. The
plate holds the game's own art when it has any, and the same plate with its
name and engine line when it does not. The actions are Play, Game setup (the
config creator for that folder), Report a problem, and Remove from list.
Game setup is reached
only from a game, because it is always about one; `dev.enginehost.CONFIGURE`
remains its programmatic door. Y on a card plays it without opening it; X opens the filters.

Controller settings provide global remapping for D-pad, face and shoulder
buttons, sticks, triggers, VN actions such as skip/auto/history and quick
save/load, multiple connected controllers, hot-plug, and controller rumble.
Engine bundles receive normalized actions while unconsumed raw Android input
continues to their render view.

### How the screens behave

Every screen is usable from a pad alone: D-pad to move, A to act, B to go
back, and a hint row under the screen naming the buttons it answers to,
which is also the touch route to them.

Focus has one look everywhere, the accent ring over a raised fill
(`eh_focus_ring`, `eh_focus_fill`): rows, cards, buttons, switches and text
fields alike. A filled button draws the ring outside its fill, with a gap, so
it still shows. No focusable view gets a focus colour of its own.

Every choice and every question is a sheet in the app's own look (`Sheet`),
never a stock dialog: on the bottom edge while the device is held upright,
on the right edge in landscape. A sheet takes the pad (D-pad between its
rows, A to pick, B or Escape to close) and carries its own hint row, so it
needs no Cancel button. The in-game menu is one too, drawn over the engine.

## Cores

Cores are separately installed, versioned `*.enginehost.tar.xz` bundles, not
Android packages or separate apps. Enginehost verifies and extracts them into
private storage, then loads an approved entrypoint inside its own `:runtime`
process, under Enginehost's UID, so the
host owns the one game-folder permission and all configuration. The separate
process contains native crashes and is terminated after a session; it is not a
security sandbox from an approved core, which is why Core Trust is a hard
gate.

An enginehost core must contain or embed an actual portable implementation
of its engine/runtime. Delegating a Windows executable to Wine, Box64, or a
generic PC compatibility app does not implement an enginehost core. Engines
without a viable native interpreter remain explicitly unsupported until that
interpreter is started. An interpreter does not need perfect compatibility to
ship: early core versions may implement only a useful subset, as long as the
limitations and supported engine versions are declared honestly. Later
co-installable core builds can extend that implementation and compatibility.

Each bundle's internally signed manifest declares its API version, Java
entrypoint, engine family, core build version, canonical GitHub origin,
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
the narrowest declared compatibility span, then the newest core build
allowed by the game's optional `pluginVersion` allowlist. A capability is
eligible only when every entry in the game's `runtimeRequirements` map is carried in its optional
`runtimeComponents` at exactly that version, or named in its `loadsComponents`, whatever the version.

The entrypoint implements the Java interface in the publishable `plugin-api`
module (cores depend on it as `compileOnly`). A core compiles against this repository's
`plugin-api/` itself, checked out by the `.github/actions/plugin-api` action pinned by commit, as
the signing workflow is; it commits no copy of the API of its own (Droidtop/tracker#768: ten cores
carried hand copies, all out of date). A web core (HTML, RPG Maker MV/MZ) also compiles in
`plugin-web/`, checked out by the same action: `GameWebView` sets up the confined WebView,
`GameServer` serves the game folder as a private https origin, and `LocalStorageBridge` keeps
`localStorage` in the save folder. The core supplies only its engine check, its input and a
`GameServer.Site` (entry pages, a stand-in for a missing file, any extra page script); the two cores
had each carried their own copy, and the copies had drifted apart. It receives a host-owned render
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

Each core repository's complete GitHub Releases history is its catalog. Every
published release carries an `enginehost-release.json` browsing envelope plus
the independently signed engine-bundle assets, and the project retains old releases. Enginehost
can enumerate, filter, download, verify, and offer any compatible release rather
than treating only “latest” as useful. See
[`docs/plugin-catalog.md`](plugin-catalog.md) and the release-manifest schema.

Dedicated core forks keep their upstream history and never live in
this host repository. Their `plugin-core` branch contains the portable Android
wrapper changeset; release branches start at relevant engine releases and
apply that changeset. Incompatible runtime lines use co-installable package
slots and explicit capabilities so builds can be installed side by side.

Current core repositories:

- [Ren'Py](https://github.com/Droidtop/enginehost-renpy-plugin): one
  `plugin/<version>` branch per engine minor, 7.3 to 7.8 and 8.0 to 8.5,
  because each line's runtime serves only its own series.
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
- [HTML](https://github.com/Droidtop/enginehost-html-plugin): web games, and
  Twine stories as a capability of the same core (there is no separate Twine
  repository).
- [NScripter/ONScripter](https://github.com/Droidtop/enginehost-nscripter-plugin),
  built on OnscripterYuri.
- [Godot](https://github.com/Droidtop/enginehost-godot-plugin): one
  `plugin/<version>` branch per engine minor, 4.0 to 4.7, because GDScript
  exported as binary tokens is refused across minors.

## Third-party assets

- The launch screen sets the game title in Gantari (Lafontype), licensed
  under the SIL Open Font License 1.1; see `third_party/gantari/OFL.txt`.
