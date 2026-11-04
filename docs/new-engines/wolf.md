# WOLF RPG Editor core: plan

Droidtop/tracker#858. The owner, 2026-11-04: "we're adding a new engine to the list: wolf RPG".
No open-source Wolf runtime exists, so this core is our own interpreter, the way CatSystem2's
core is our own scene player. Wine running `Game.exe` does not count as a core. The core lives in
`Droidtop/enginehost-wolf-plugin` (created by the owner, 2026-11-04). It is built under the policy
for cores written from scratch (SPEC.md, "Cores we write from scratch"), and this page applies that
policy to Wolf.

Markers: **[V]** checked on a page or in source this pass; **[S]** from a search snippet only;
**[M]** from memory, to be checked by a local lane's static pass (step 1 below). silversecond.com (the
official site and manual) and dxlib.xsrv.jp were blocked from the cloud session, so everything that
only they can settle is marked as open.

## What the host already has

- Detection: the shared database's `wolfrpg` row, and `EngineNames.kt` names the family "WOLF RPG
  Editor". A detected Wolf game today says nothing here runs it.
- Nothing else: no bundle, no capability, no engine context.

## The engine and its files

**Versions.** 2.x (2.01 to 2.281; the classic line most translated games use). 3.x and the paid
Pro edition came out together on 2022-11-22 [S] (gamespark.jp, automaton-media.com); the current
release is about 3.71 [S].

**Layout of a game.** `Game.exe` (`GamePro.exe` for Pro builds), `Game.ini`, then either an
unpacked `Data/` folder or packed archives: `Data.wolf`, or one `.wolf` per `Data/` subfolder
(`BasicData.wolf`, `MapData.wolf`, `Picture.wolf`, ...). Saves go to `Save/` beside the game
[M], which Enginehost leaves alone (saves beside the game stay beside the game).

**Archives.** A `.wolf` file is a DX Library DXArchive. Each engine era uses a fixed key and a
fixed archive version, the same for every game of that era (UberWolf `WolfDec.cpp:58-68` [V];
popupie/wolftools `src/constants.mjs` [V]):

| Wolf version | DXArchive version | Key |
|---|---|---|
| 2.01, 2.10 | 5 | fixed key |
| 2.20 | 6 | fixed key |
| 2.225 | 8 | fixed ASCII key |
| 3.00 to 3.50 | 8 | fixed key, crypt version 0x12C, 0x13A, 0x14B, 0x15E |
| Pro | 8 | per-game key held in a separate key archive (ChaCha20) |

**BasicData files.** `Game.dat` (screen size, tile size, fps, fonts, title, engine version; in
2.x XOR-obfuscated with a fixed LCG [V] wolftrans `filecoder.rb:214`), `DataBase.dat` +
`.project`, `CDataBase.*`, `SysDataBase.*`, `CommonEvent.dat`, `TileSetData.dat` and
`MapData/*.mps`. 3.x adds a UTF-8 marker (header byte 0x55; 2.x is Shift-JIS), and from 3.5
LZ4-compressed maps with a variable layer count [V] (UberWolf `FileCoder.hpp`, `Map.hpp`).
Version markers in file headers: 0xC2 for 2.x, 0xC3 for 3.0, 0xC4 for 3.3 [V] (djytw
`common.ksy`).

## What the core reads: only what the game itself reads

The owner (task for #858): "Read only what the game itself can read: no breaking Wolf 3 or Pro
protection." So:

- **Read:** an unpacked `Data/` folder; 2.x archives; 3.x archives that use the engine's fixed
  key and carry no protect key.
- **Refuse, with a plain message:** Pro games (`GamePro.exe`, or a key archive beside the data)
  and any 3.x game built with a protect key. The core does not search for, derive or brute-force
  a key that the game does not hand to its own runtime the standard way. The game page says that
  this game uses Wolf's protection and Enginehost does not run it.
- The survey tool (below) counts how many of the owner's games each rule excludes, so the owner
  can see the cost before anything is built around it.

## Drawing and sound: not DX Library

Wolf is built on DX Library (Takumi Yamada). An Android build of DxLib exists only as prebuilt
static libraries (Atsumi3/DxLibAndroidApp, DxLib 3.19d, all four ABIs [V]); its Android source
and its exact audio support were not checked (official site blocked). Linking a closed `.a` buys
nothing, because the core reimplements behaviour, not DxLib's API, and the only DxLib piece the
data needs is the archive format, which is documented. So the core is **SDL2 + GLES for drawing,
SDL_mixer with libvorbis for sound** (the same stack the LOVE and xsystem4 plans use), FreeType
for text with Noto Sans CJK standing in for MS Gothic [M: Wolf's default font].
A local lane's static pass (step 1) lists every DxLib call `Game.exe` makes; each becomes a row in the
behaviour description with the SDL/GLES call that provides it.

## How the core is built: the from-scratch policy, applied

1. **Static pass, nothing run; a local lane only** (the owner: "LOCAL lane only"). A local lane
   reads `Game.exe` of each engine era the survey finds (2.10, 2.20, 2.225, 2.281, 3.x): imports,
   DxLib calls, the event-command dispatch table, timing (frame pacing, wait units), the picture
   and message systems. Its tools stay with it, not in the core repository. It reports an
   inventory, not code; the behaviour description (`docs/behaviour/` in the core repository) is
   written from that report, and the core from the description, never from `Game.exe`'s code.
2. **Risk list.** Every behaviour that could be abused goes on #858 with its answer: not
   implemented that way, or behind a Fibrewall permission, off by default. Candidates to confirm
   in the static pass, all [M]:
   - string commands that read and write text files by path (can they leave the game folder?);
   - opening a URL or a file with the system shell;
   - calling an external DLL (Wolf 3's DLL command);
   - screenshots and other writes outside `Save/`;
   - any network call;
   - parsers that trust lengths and counts from the game's data (every reader in the core is
     bounds-checked and fuzzed instead).
3. **Survey of real games.** `tools/survey/wolf_survey.py` in the core repository scans a folder
   of games in place and writes one JSON report: engine era per game (from `Game.dat` and archive
   headers), packed or unpacked, Pro or protect key, screen and tile size, and for data it can
   read, how often each event command code is used. Read-only; never copies a file.
4. **One broad pass.** Formats first (all of them, both text encodings), then the interpreter
   for event commands in the survey's order of use, then maps and movement, messages and
   choices, pictures, sound, saves. The public code list (wolftrans `command.rb:310-370`, about 60
   codes [V]) is a cross-check, not the source.
5. **Rig comparison.** The harness runs a game under the official runtime and under the core
   with the same scripted input, a fixed random seed and a fixed clock, and diffs the screen and
   the variables at checkpoints. Each mismatch is the next fix. It runs on a local rig session,
   on the owner's files read in place, with `Game.exe` isolated.

## Bundle

- `bundleId` `dev.enginehost.wolfrpg.v1`, engine `wolfrpg`, line `0.1` (`plugin/0.1`), engine
  source on `engine/0.1` (the CatSystem2 layout: our own engine is the line's upstream).
- `isolatable: true` from the first build: a plugin-api core, one native library,
  `libwolfrpg.so` for `arm64-v8a` and `x86_64` only, and every file read through the broker.
- Unstable until a game loads a save on the console (the owner's bar for Stable).
- Needs from the owner before the first signed build: the repository's signing certificate
  (`enginehost-signing-certificate.pem`), `ENGINEHOST_CERTIFICATE_KEY_PEM` and
  `PLATFORMS_DISPATCH_TOKEN` secrets, and `plugin-core`, `engine/0.1`, `plugin/0.1` branches
  created from the lane's `ci/` branches.

## Open questions

- The official manual (silversecond.com): event-command parameters, the save format, the
  autotile layout, the variable address ranges. The static pass covers them if the site stays
  out of reach.
- What the owner's games actually are (the survey answers it).
- Whether the official browser portal "ゲームアソビタ" (2026-10-10 [S]) has a runtime we could
  learn from in the open. Unknown.
