# Conformance test games

Every Enginehost plugin family needs a test game the conformance layers can
actually run: layer 1a (`plugin-conformance-static`) checks a built bundle
offline, layer 1b (`plugin-conformance-emulator`) boots it on an emulator
against a real game. Both workflows are the suite's own pending work; this
page is the list of games layer 1b will launch, one row per family that has
a plugin today, and the rules for what each game must show.

A conformance game must exercise three things, and the row says which of the
three apply:

1. **A frame that changes.** The screen is not one static picture: either
   something animates on its own, or scripted input causes a visibly
   different frame. Text-only engines meet this with scripted redraws, so
   the emulator harness must treat "changes after its key event" as a pass
   for those families, not only "changes while idle".
2. **An input response.** A key event (the harness sends `KEYCODE_ENTER` /
   `BUTTON_A`) must visibly change the screen: a click-advance, a menu
   choice, a phase flip.
3. **A save.** The game writes a save through the engine's own save system,
   into the per-game location Enginehost's save-root mapping gives it, and
   reads it back on relaunch (the harness's HOME-and-relaunch step doubles
   as the save check). Families whose engine plugin does not implement a
   save system yet say so instead of pretending.

**Redistribution rule.** Every game the suite fetches by URL must be
freely redistributable (CC0/MIT/zlib-class, GPL, or the engine's own
sample/test project), because the suite fetches it on every CI run and the
fixtures may be handed around. Commercial games are never named or used
here, and no fixture may be a copy of a game from the owner's library.
Where no redistributable game exists and none can be authored, the row
names a **rig-local fixture** instead: data that only ever lives on the
test rig, fetched from nothing, redistributed never.

The suite has two kinds of fixture:

- **In this repository** (`test-games/`, all MIT, `test-games/LICENSE`):
  tiny games authored for this suite. Nothing to fetch, nothing to trust,
  a few KB each, and they exercise exactly the three points and nothing
  else.
- **Fetched from upstream**: the engine's own sample/test project, pinned
  by URL. CI re-fetches it per run; the URL and the licence it is used
  under are in the row.

## The games

| Family | Plugin line | Game | Source | Licence | Size | Exercises |
| --- | --- | --- | --- | --- | --- | --- |
| renpy | line-7.3 … line-8.5 | `test-games/renpy` (ours) | this repo | MIT | 1.9 KB source | animate + click-advance + menu choice + `renpy.save` |
| godot | plugin/4.0 … plugin/4.7 | `test-games/godot` (ours) | this repo | MIT | 3.0 KB source | animate + key flip + `user://` save |
| love2d | plugin/11.5 (series 11) | `test-games/love2d` (ours) | this repo | MIT | 2.4 KB source | animate + key/touch flip + `love.filesystem` save |
| html (compiled-html) | plugin/2.0-2.12 | `test-games/html` (ours) | this repo | MIT | 4.4 KB source | CSS animation + link/Enter + local-storage save |
| nscripter | single line | `test-games/nscripter` (ours) | this repo | MIT | 0.9 KB source | redraw per click + select + `savegame` |
| rpgmaker (2000/2003) | EasyRPG line | EasyRPG **TestGame-2000** (and the 2003 suite) | https://github.com/EasyRPG/TestGame | GPL-3.0 (`COPYING`) | 30 MB git repo incl. history; the per-suite working trees are smaller | map render + movement + menu + save (per suite design; needs a rig check) |
| ags | plugin/3.6.3.15 | the AGS community demo game project | https://github.com/adventuregamestudio/ags-demo-game | script 0BSD, art CC BY-NC-ND 4.0 (`LICENSE.md`) | 1.8 MB git repo | rooms + interaction + F5/F7 save/restore |
| kirikiri2 | plugin/2.31 / stable | the KAG3 template package | https://github.com/krkrz/kag3 | KAG3 package licence (W.Dee: modification and redistribution free) | ~430 KB unpacked | title/config screen + scenario click-advance + KAG save bookmark |
| buriko | plugin/0.0.1 | rig-local fixture only — see below | — | — | — | — |
| catsystem2 | plugin/2.0 | rig-local fixture (or SDK-built scene) — see below | — | — | — | — |
| cmvs | plugin/2.0-3.0 | rig-local fixture only — see below | — | — | — | — |
| flash_air (air/swf) | ruffle 0.4.1 bundle | specified, to be CI-built — see below | — | — | — | — |
| rpgmaker (xp/vx/vxace) | mkxp-z lines | specified, editor-built — see below | — | — | — | — |
| rpgmaker (mv/mz) | mv-mz shell | specified, editor-built — see below | — | — | — | — |

## The in-repository games (test-games/)

All five are ours, MIT (`test-games/LICENSE`), and were written for the
conformance harness, not to be good games. Each one states what it
exercises in its own header. Assembly notes:

- **renpy** — `test-games/renpy/game` is a Ren'Py `game/` directory. To
  make it a launchable folder, copy it beside the Ren'Py SDK's runtime
  pieces (`renpy/`, `lib/`, `renpy.py`, exactly what the SDK's own
  "Build Distributions" produces for a game), because Enginehost's
  detection expects the `renpy/` runtime dir next to `game/`. The script
  is script-language only, so the same game runs on every plugin line
  from 7.3 to 8.5. `config.save_directory` is fixed
  (`enginehost-conformance-renpy`) so the save root it lands in is
  deterministic. A launcher can also build the folder once and share the
  built copy between runs.
- **godot** — `test-games/godot` is an unexported project (one of the
  two shapes detection accepts, `project.godot`), deliberately free of
  any imported asset, so it needs no editor pass: the same tree runs as a
  project and as an exported `.pck` for any 4.x line. Script and scene
  syntax stick to 4.0 APIs so nothing here rules out the older lines.
  The save is a `user://` JSON file, read back in `_ready`, so the
  relaunch step proves the save survived. Uses the compatibility
  renderer (GLES3) so emulators without Vulkan are fine.
- **love2d** — `test-games/love2d` is an unpacked LÖVE game: `main.lua`
  plus `conf.lua` declaring `function love.conf`, which is the exact
  unpacked shape Enginehost's detection rule keys on (zip it and it is
  also a `.love`). LÖVE 11 API, matching the plugin's 11 capability
  series. `t.identity` fixes the save directory name under the save root.
  Any key, mouse button or touch flips the phase **and writes the save**,
  so the harness's single `KEYCODE_ENTER`/`BUTTON_A` event both changes
  the frame and creates the save; the relaunch then reads it back.
- **html** — `test-games/html/index.html` is shaped like a compiled Twine
  2 story (`tw-storydata` with `tw-passagedata` passages), which is what
  the plugin's `compiled-html` context runs, but its runtime is a minimal
  original one, so no third-party story format is redistributed. Only the
  save link's passage is written to the story's `localStorage`, which the
  Twine plugin maps into the Enginehost save directory; the next launch
  resumes from there. Enter/Space activates the first link, which is how
  the harness's key event reaches the story. **Observed while writing this:**
  the html plugin's *published* lines (`plugin/2.0-2.12`, `plugin/stable`)
  correctly declare `engine: html`, but its `plugin-core` branch has
  drifted to `engine: "twine"` (`dev.enginehost.twine.2_12.v1`), a family
  name nothing in the host or the engines-database knows. The plugin owner
  should reconcile that before the conformance suite runs against a
  plugin-core build.
- **nscripter** — `test-games/nscripter/0.txt` is a plain-text NScripter
  script in pure ASCII, so it runs under the engine's sjis, gbk and utf8
  script encodings alike. It follows the same pattern as the nscripter
  plugin's own headless test game (`tests/game/0.txt` in the plugin repo).
  Note for detection: a folder with only `0.txt` does not trip
  Enginehost's nscripter rule (it wants `0.txt` plus an `.nsa` archive,
  or `nscript.dat`); the conformance launcher passes an inline config
  anyway, so the suite is unaffected, but anyone adding this game to a
  library should expect CONFIGURE rather than a direct classify.

**Needs a rig check** for all five: they were written without a device and
have not been launched yet. The rig run should confirm each boots, answers
the key event, and leaves a save file under the mapped save root.

## The fetched games

### rpgmaker, contexts 2000 and 2003 (EasyRPG)

**EasyRPG TestGame** is the EasyRPG project's own test-suite game, the
game their Player is tested against and the closest thing the RPG Maker
2000/2003 world has to an official sample: collections of test rooms per
map, built with RPG Maker 2000 and 2003.

- Source: https://github.com/EasyRPG/TestGame (suite to use:
  `TestGame-2000` first; `TestGame-2003` for the 2003 context. The repo
  also carries `TestGame-EasyRPG`, `TestGame-Maniac` and
  `TestGame-PowerMode2003`, which exercise engine extensions the current
  plugin does not need for basic conformance.)
- Licence: GPL-3.0 (the repo's `COPYING`); redistribution is what the
  repo exists for.
- Size: the git repository is ~30 MB including full history; a shallow
  clone or archive of one suite is much smaller and is what CI should
  fetch.
- Exercises: map rendering, movement, menus and the save system, per the
  suite's own design.
- Needs a rig check: whether `TestGame-2000` boots on the plugin with no
  RTP installed (the suites ship their own assets, but the exact RTP
  independence must be observed, not assumed; if an RTP is required, the
  run should use the EasyRPG RTP replacement with the `rtpPaths` option
  rather than Gotcha Gotcha Games' copyrighted RTP, which this suite
  must never redistribute).

### ags

The AGS community demo game project is the Adventure Game Studio
community's own demo game, made by the engine's project for this purpose
(a work in progress, per its README).

- Source: https://github.com/adventuregamestudio/ags-demo-game
- Licence: game script 0BSD, graphical assets CC BY-NC-ND 4.0 (the repo's
  `LICENSE.md`). For a conformance fixture used verbatim with
  attribution, that licence permits redistribution; the suite must not
  modify the game or strip the licence files.
- Size: ~1.8 MB git repository.
- Exercises: room render and walkable areas, hotspot/interaction (input
  response), and save/restore through the stock template's F5/F7 keys —
  the same keys Enginehost's `ags` controller map binds.
- Caveat: the repository holds the **project source**, not a compiled
  game. A one-time compile with the AGS editor (the plugin line is
  3.6.3.15) produces the game folder (`acsetup.cfg` plus data files) the
  suite runs; the built copy of this project can be redistributed under
  the same licence.
- Also observed while writing this: the engines-database row for `ags`
  still says "No enginehost plugin yet" with `enginehost: null`, but an
  AGS plugin exists, is registered in the plugins index and has
  releases. The database row should be updated by whoever owns it; until
  then the conformance launcher passes the engine/context inline.

### kirikiri2

**The KAG3 template package** is the engine's own template project: the
KAG3 system scripts, a stub scenario, and the resource folders every KAG
game expects. The krkrz org carries it converted to UTF-8.

- Source: https://github.com/krkrz/kag3. The `data/` directory is the
  game folder; `startup.tjs` at its root is exactly the loose shape
  Enginehost's kirikiri rule accepts.
- Licence: the package's own header -- Copyright (C)2001-2009, W.Dee and
  contributors, "modification and redistribution free"
  (改変・配布は自由です) -- stated in the repository README and in
  `startup.tjs` itself. The underlying engine licence (the licence in the
  plugin repository's `LICENSE`) likewise permits redistribution with
  notice.
- Size: ~430 KB unpacked (the system scripts; the repository has 7
  commits).
- Exercises: boots the KAG3 system to its title/config screen (frames),
  Start enters the sample scenario `first.ks` -- a line of text with a
  click-wait (input response) -- and its `*start|スタート` label names a
  save bookmark, so the KAG save menu is the save path to check.
- Needs a rig check: the package is the kirikiriZ-adjusted variant, and
  `startup.tjs` guards the Z-only plugin links behind `@if (kirikiriz)`,
  so the 2.31-based runtime (Kirikiroid2Yuri) skips them -- but whether
  the system scripts lean on other Z-only behaviour must be observed,
  not assumed.

## The specified games (authored outside this change)

For four families no freely redistributable game was found, and the
fixture cannot be authored from a text editor:

### rpgmaker, contexts xp/vx/vxace (mkxp-z)

No RPG Maker XP/VX/VX Ace game with a redistribution licence was found
(a GitHub search over `rpg-maker`-topicbed MIT-licensed repositories
returned tools and decrypters only). The engines' own sample projects
ship inside the commercial editors and are not redistributable.

Specified fixture: a minimal self-authored project built once in the
RPG Maker VX Ace (and XP/VX) editor — one map, one walkable NPC, one
message with a choice, one save via the menu — using **only original
assets**, so the built folder needs no RTP and is entirely ours to
redistribute. Needs the owner or anyone with an editor licence; a
Windows machine with the editor is enough. Until that project exists,
this family has no redistributable fixture.

### rpgmaker, contexts mv/mz

Same situation and same specification: one minimal MV and one minimal MZ
project (the deployed `www` folder is what the plugin runs), original
assets only, save via the menu. The editor requirement is unavoidable;
the enginehost plugin is the WebView shell and the game ships its own
deployed JS engine, so any such project is fully redistributable once
built from original content.

### flash_air, contexts swf and air

The plugin is a Ruffle 0.4.1 fork declaring both contexts
(`ruffle-0.4.1-swf`, `ruffle-0.4.1-air`, runtime version 0.4.1, any
engine version accepted). No tiny game-like SWF with a clear redistribution
licence was found: Ruffle's own `tests/swfs` are regression fixtures of
mixed provenance, and the demo page ships no redistributable game.

Specified fixture: a minimal self-authored ActionScript source — an
animated square, a key/button response, a `SharedObject` save — compiled
in the flash-air plugin's CI with a free compiler (Apache Royale or
swftools' `as3compile`; the choice is the plugin owner's), producing one
`.swf` for the `swf` context and one unpacked AIR layout (`META-INF/`
plus `mimetype` plus the SWF) for the `air` context. Needs that compiler
decision and a rig check before layer 1b can run this family.

## The rig-local fixtures (never fetched, never redistributed)

Three families run games that are all commercial today, and no
authoring path exists for a free one:

- **buriko** — every BGI game is commercial and the engine executes
  compiled `._bp` scripts no free toolchain produces. The conformance run
  uses a game the owner already has, in place on the rig's share; the
  suite stores no copy and this document names no title. Scope today is
  limited anyway: the engine reaches the title screen but stalls
  (2026-09-24 rig logs, `docs/plugin-catalog.md`), so the family's row in
  the emulator workflow should expect "boots to a visible frame and
  answers nothing" until that defect is fixed.
- **catsystem2** — the plugin is a scene player; it does not run the
  compiled KCS system script, so there is no title screen, no menu and
  no save system to exercise yet. Games are commercial `.int` archives
  keyed to each game's own executable. The conformance run uses a scene
  from a game the owner has (rig-local), or — if a redistributable
  fixture is wanted — a minimal scene built with the official CatSystem2
  authoring tools (free for doujin use) from original assets, which
  would be ours; the `.cst` scene format is a compiled "CatScene"
  container, so a scene cannot be authored from plain text. What it can
  exercise today: background/character/dialogue render (frames) and
  tap-to-advance (input). Save: not applicable to this engine yet.
- **cmvs** — the plugin reads extracted PS2/PS3 dialogue scripts only
  (no choices, graphics, CPZ archives or save state). No freely
  redistributable CMVS sample exists; the MIT-licensed dumper
  repositories the engine work was based on
  (https://github.com/xmoezzz/CMVS-Engine) ship tools, not game data.
  The conformance run uses extracted scripts from a game the owner has,
  in place on the rig. What it can exercise today: dialogue text
  advancing on tap. Save: not applicable to this engine yet.

## What this leaves open

- All five `test-games/` projects need their first rig check (boot, key
  response, save file observed in the mapped location).
- EasyRPG TestGame-2000: rig check for RTP independence; the harness
  should shallow-clone the suite, not the repository.
- The AGS demo game: one editor compile, then a rig check (the stock
  template's F5/F7 path is the save to observe).
- The KAG3 template package: first boot under the 2.31-based runtime.
- mkxp-z, mv/mz, flash_air fixtures: blocked as described above.
- Two database/plugin inconsistencies observed while writing this:
  `ags` has no enginehost mapping in the engines-database despite a
  registered plugin, and the html plugin's plugin-core declares a
  `twine` family the host does not know. Both are for their owners.
