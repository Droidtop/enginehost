# GameMaker engine plugin: scoping

Step zero of any GameMaker effort: the written scope the 2026-09-25
new-engine census asked for before committing to what it priced as a
"medium-large" reimplementation (46 titles, 2.7 percent of the library,
among the largest still-unaddressed buckets after Unity). This page is
research only: no repository, no code, no host change, no SPEC change.
What exists today stays as it is. The host already recognizes the family
-- `EngineDetector.kt`'s `gamemaker` detection (an unhosted family, read
the same way AGS and LOVE are), `EngineNames.kt`'s "GameMaker", and
`EngineFormats.kt`'s `GameMakerData`, which reads the data file's `GEN8`
chunk for the bytecode and IDE version -- and droidtop's SPEC (7d,
"Coverage: what runs where") routes the family to Wine on the ground
that "no portable interpreter exists".

## What a GameMaker game actually is

Three materially different products carry the GameMaker name, and they
are not one plugin's worth of engine:

- **GameMaker Classic (5.x-8.1, 1999-2011).** A single `.exe` holding
  the engine, all assets and the game logic ("the executable contains
  not only the game engine code, but all of the objects, scripts,
  sprites, room layouts, everything required for the game logic" --
  OpenGMK, https://github.com/OpenGMK/OpenGMK). Always VM bytecode; the
  YoYo Compiler did not exist yet. Enginehost's detector does not see
  these today: `GameMakerData` looks for Studio-era data files
  (`data.win`, `game.unx`, `game.ios`), not data embedded in a bare
  executable.
- **GameMaker: Studio 1 / Studio 2, VM output (the default).** "The VM
  (Virtual Machine) target uses a generic *runner* for each platform and
  then interprets the code for your game"
  (https://manual.gamemaker.io/lts/en/Introduction/Compiling.htm). The
  game is a data file a runner interprets -- the interpreter-shaped
  case, the same shape as KiriKiri's data.xp3 -- and this is the shape
  the detection already reads.
- **GameMaker: Studio 1 / Studio 2, YYC output.** "The YYC (YoYo
  Compiler) generates C++ code from your GML code, before using the
  target's C++ compiler to compile it into native code for the target
  platform", bought as a two-to-three-times speedup for "larger or
  CPU-intensive projects" (same manual page). The game's logic is native
  code in the executable; UndertaleModTool, whose whole business is
  reading data files, marks VM code work on YYC builds "not supported"
  (https://github.com/UnderminersTeam/UndertaleModTool). There is
  nothing for a reimplementation to interpret.

## VM vs YYC in the library

Store pages do not say which export a title uses -- the same blind spot
the census hit for Unity's Mono vs IL2CPP. The priors all point one
way, but nobody has measured the 46: VM is the default target and the
manual's own advice for "smaller games or games where performance is
not ever going to be an issue"; the engine's best-known titles are
small 2D indie games (Undertale, Hotline Miami, Nuclear Throne, Hyper
Light Drifter -- https://en.wikipedia.org/wiki/GameMaker); every
Classic-era title is VM by definition; and the census itself expected
bytecode "for most titles, with a native 'YYC' fallback for some". So:
likely majority VM, genuinely unknown until each title's data file is
inspected -- a cheap, codeless measurement (a data file with VM code
present vs one without, or no data file at all).

**Can one plugin cover both? No, and not as a matter of effort.** VM
output is one bounded, community-documented bytecode and asset format
behind a generic runner: a reimplementation target like any Enginehost
engine. YYC output is per-title native code: the same category as Unity
IL2CPP, uninterpretable at any size, permanently on the Wine path.
From Enginehost's side these are different engines that share a name,
so even a built plugin would cover only part of the family, and any
routing must split it per title on the data-file test above.

## Candidate runtimes and licensing

| Candidate | What it is | Licence | Runs Studio data files? |
| --- | --- | --- | --- |
| YoYo Games' runner | The engine itself; the Android exporter builds per-title APK/AAB from a game's source under the developer's licence (manual, Compiling) | Proprietary | It is the interpreter, and it is not distributable |
| ENIGMA (https://github.com/enigma-dev/enigma-dev) | Open IDE and compiler for its own EDL language (a GML/C++ mix), parsed and translated to C++ | None at the repo root as of 2026-09-29 (GitHub reports no licence file) | No -- it compiles source; it cannot load a compiled game at all |
| OpenGMK (https://github.com/OpenGMK/OpenGMK) | Clean-room sourceport of the Classic (GM8/8.1) runner, plus a decompiler and gamedata libraries | GPL-2.0 | No -- Classic-era embedded exes only; Studio support "unclear right now" (its own README) |
| GM8Emulator (https://github.com/Adamcake/Legacy-GM8Emulator) | OpenGMK's predecessor | MIT (archived read-only, 2020) | No -- same Classic-only scope |
| UndertaleModTool (https://github.com/UnderminersTeam/UndertaleModTool) | Modding/decompiling toolkit for data files, with a wiki documenting the format and VM instructions | GPL-3.0 (`LICENSE.txt`) | Reads and edits them; not a runtime |

Two corrections to the census's own GameMaker note fall out of this
table, recorded here because the census is what the next reader will
check: UndertaleModTool is GPL-3.0, not the MIT the census assumed --
its code cannot be lifted into a non-GPL plugin, though reading its
wiki as a format specification is unaffected; and the "open GMS
bytecode interpreter" the census hoped to find in the RE community does
not exist -- a 2026-09-29 search of GitHub for projects that run
compiled GameMaker games finds OpenGMK and its MIT predecessor, both
desktop and Classic-only, and nothing Studio-era.

The licensing answer is therefore blunt: **no open GameMaker VM runtime
exists for the Studio generations the modern library is made of.**
Every viable path is a clean-room reimplementation of the data-file
format, the GML VM and the runner's builtin library, with
UndertaleModTool's wiki and byte-exact reader as the format reference
and OpenGMK as proof the older generation was reproducible at all.
(Historical footnote: YoYo open-sourced its GameMaker 8-era web-player
plugin in mid-2011 and then deprecated it for HTML5 export; no
maintained descendant was found.)

## Android precedent outside YoYo's own exporter

**None found.** The only GameMaker runtime on Android is YoYo Games'
own export target, which is developer-side and per-title: it produces
an APK/AAB from the game's source and says nothing about running the
person's already-owned PC builds. The open projects above are
desktop-only: OpenGMK ships Windows binaries, calls Linux "near
future", requires desktop OpenGL 3.3, and names no Android target
anywhere in its documentation. No third-party Android app or port that
runs compiled GameMaker games was found; if one has shipped, it left no
trace in the projects or their forks.

## Size on the census's scale

The census priced a GML-VM core at "medium-large, comparable to the
KiriKiri work already done", and the upstream evidence supports it:

- OpenGMK -- a Rust rewrite by an active team, years and 3,625 commits
  in -- is still unreleased and reports that "the full GameMaker
  Classic standard library is absolutely massive, and there's a good
  bit left to cover", for the *older* generation, on desktop, with no
  sandbox, no controller mapping and no ABI story.
- A Studio-era clean room adds what Classic did not have:
  bytecode-version drift (the `GEN8` reader's own comment: Studio 2
  writes `2.0.0.0` there whatever the IDE release, so the bytecode
  version is the finer fact), GMS 2's layers, sequences and structs, a
  GLSL ES shader surface, and the same magnitude of builtin library --
  interpreted this time rather than precompiled.

So, on the census's small-to-large scale: **a first playable plugin,
scoped hard to one Studio bytecode line and a bounded builtin set
proven against the actual census titles, is medium-large. The whole
family (Classic exes plus GMS 1 plus GMS 2.x) is large.** YYC is not
sizeable at all -- it is out of scope by nature, not by effort.

## The Enginehost-vs-Wine line

SPEC 7d's coverage table puts GameMaker on the Wine side, in the row of
"Windows-only engines", with the reason "no portable interpreter
exists, and Enginehost's rule is that a plugin embeds a real
implementation of its engine, never a Wine hand-off". This scope splits
that row and makes the call explicit:

- **YYC-mode GameMaker belongs there permanently**, for the same reason
  as Unity IL2CPP and Unreal: per-title native code, no data format to
  interpret. No future finding changes this.
- **VM-mode GameMaker is not categorically excluded the way those
  engines are.** A `data.win` game is interpreter-shaped: one bounded,
  documented format behind a generic runner, exactly KiriKiri's shape,
  and a clean-room VM would satisfy the "embeds a real implementation"
  rule. GameMaker's exclusion from Enginehost is a build-cost decision,
  not a category decision -- the first engine in that SPEC row for which
  that is true. (The engine sandbox, `docs/engine-sandbox.md`, would
  treat a clean-room VM like any other native engine plugin; it adds no
  cost specific to this engine.)

**VM-mode GameMaker is in-principle Enginehost territory; YYC-mode
never is; and today both stay on the Wine path because the cost is not
justified.** If a plugin is ever built, droidtop's routing must split
the family per title on the data-file test, and the plugin declares the
bytecode eras it actually implements -- the same capability shape as
any other engine family.

## Recommendation

**Do not build.** No SPEC change follows (the engine-coverage line gains
an entry only if the answer is to build), and the host's detection --
which already reads the bytecode and IDE version and reports the family
as unhosted -- stays exactly as it is.

What would reopen this page, and nothing else:

1. **Flag the 46 census titles VM vs YYC from their data files.** A
   small research task in the exact shape of the pending Unity
   detector: no engine code, just turning "likely majority VM" into a
   real addressable count. Worth batching with that detector work, not
   scheduling alone. A tiny VM subset would close this permanently; a
   large one still would not justify the build on its own.
2. **An open Studio-era runtime appearing.** OpenGMK adding `data.win`
   support or an Android target is the signal: either one converts the
   effort from a clean-room medium-large to something Enginehost's
   existing plugin shapes can package (a GPL-2.0 fork line, the xsystem4
   shape), and this page gets rescoped, not merely revisited.
