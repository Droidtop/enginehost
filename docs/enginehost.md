# Enginehost design decisions

## Sandbox badge (2026-09-28)

The installed-plugins (`item_plugin_trust.xml`) and catalog-release (`item_release.xml`) cards
now show a second small badge beside the provenance badge, reading from the existing
`isolatable` flag: `InstalledPlugin.isolatable` (post-install) and
`EngineBundleManifest.isolatable` (pre-install). Two states only: "Sandboxed" when
`isolatable == true`, "Unsandboxed" when `false`. Positive state reuses
`eh_official_container` / `eh_on_official_container`; warning state reuses
`eh_caution_container` / `eh_on_caution_container`. No new color token added.

Refs Droidtop/tracker#23. Part of Droidtop/tracker#24 (Enginehost UI v2, docs/ui-v2.md §4).

## The library, the scanner and its filters (2026-10-01)

Refs Droidtop/tracker#210.

### The model: Enginehost is the emulator, cores are cores, games are content

Owner decision, 2026-10-01: Enginehost is a complete game manager and an emulator in its own right,
the way RetroArch is, not a frontend. Each core is a core; a game folder is content; the
library is what RetroArch calls playlists, built by a scanner. This section is how the library
follows that model:

- **Content.** Every game the scanner finds, and every game a person adds by hand, is one row in the
  library database (`GameLibraryStore`, SQLite). A row is the content's path plus the facts needed to
  list, filter and sort it; nothing is read from the game to draw it.
- **Mapped to its core.** A row carries the engine line a detection resolved to (family, context,
  version, runtime requirements). Whether the installed cores can run it is decided by the same
  `PluginResolver` Play uses, once per distinct engine line (`SupportResolver`), never per game.
- **Loaded directly.** A row opens its game's own screen; Y on it plays it, as always. The library is
  also the way into any content Enginehost is handed (a folder picked by hand, a launch from another
  app).
- **Ready for options.** A row is the unit core options and per-game options attach to. The per-game
  option file stays the game folder's own `enginehost.json` (written only by the config creator, see
  CLAUDE.md); the row never stores options, so a game keeps behaving the same whichever app lists it.
- **Not everything is a core's content.** Games Enginehost cannot run are still listed, tagged with
  the builds they ship, so droidtop's Wine and Linux runners (or the person) can pick them up.
  Enginehost stays "just another emulator" to droidtop; nothing here launches a Windows or Linux
  binary.

What a RetroArch-style Enginehost still needs beyond this scan: named playlists and favourites over
the library, history as its own list, a "load content" entry that takes any path, per-game core
override (choose the core and version a game uses), per-core options screens and per-game option
overrides kept outside the game folder, thumbnails and metadata (no scraper exists), and core
management on the library screen. They are tracked, not decided here.

### Detection: what marks a game

Classification of engines stays the registry's alone (`engines-database.json`, the file droidtop
scans with); scanning adds no second engine taxonomy. Around it, the scanner adds the platform layer.

| Signal | Read how | Result |
| --- | --- | --- |
| Registry rules: Ren'Py, RPG Maker XP/VX/VX Ace/2000/2003/MV/MZ, KiriKiri, Buriko, CatSystem2, CMVS, AIR, Godot, NScripter, AGS, LOVE, Unity, GameMaker, Unreal, NW.js/Electron, HTML/Twine, SWF, Wolf RPG, TyranoScript, XNA/FNA/MonoGame, HashLink, Source and the rest of the file | file and folder names, extensions, small header reads, pack headers (`EngineDetector`) | engine, context, version; high confidence. Hosted engines are Android-runnable; the rest keep the builds below. A bare HTML page is low confidence, a Twine story high |
| PE image (`.exe`) | DOS stub, PE header, optional header: Machine (x86_64, x86, arm64, arm), PE32 or PE32+, CLR directory (managed .NET), subsystem (GUI or console) | Windows build, architecture, .NET flag. An MZ file with no PE header is a DOS program, listed as a Windows build |
| ELF binary | `e_machine`, `e_type` (programs and PIE only, never objects) | Linux build with architecture. Extensionless files count only from 64 KiB |
| AppImage | ELF with the `AI` marker, or `.AppImage` | Linux build |
| Launcher script | shebang, or `.sh` over text | Linux build |
| Archive (`.zip .rar .7z .tar .gz .bz2 .xz .zst`, first volume) | extension plus magic bytes (a tar's `ustar` at 257), 8 MiB or more | Archive: needs unpacking before anything else; low confidence |
| Nothing else | | not a game |

Launch file: executables and scripts at the folder root (or in a conventional build subfolder such as
`bin` or `win64`, only when the root has none) are ranked. Installers, uninstallers, redistributables
(`vcredist`, `dxsetup`, `dotnetfx`), crash handlers and updaters are never candidates; companion tools
(`config`, `launcher`, `patch`) are ranked down; the file named like the folder wins. A 64-byte header
read per candidate, at most 12 per folder, is all that is read. A folder of more than eight unrelated
programs, or one small program alone, is not a game. The confidence of a native build with no known
engine is medium when its executable is named like the folder or a data folder sits beside it, else
low; the library says "unconfirmed" for anything below high.

Game root: the scan walks breadth first and stops at the first folder that is a game, so a game is
never searched for games inside itself, and a wrapper folder (`Game-1.0/Game-1.0-pc/`) is walked
through rather than counted. When the registry sees a game one folder down from a folder that also has
files of its own, that folder is the game and its launch file is found in the subfolder. An archive
beside a folder that unpacks from it (same release name, platform tags ignored) is dropped. Results
are also deduplicated in the database: saving a game root deletes any only-found rows inside it.

Each result is an engine (or none), a mask of builds (Android-runnable, Windows, Linux, Web, Archive),
a confidence, the launch file, the architecture and whether it is managed code.

### Scale: a library of thousands on a handheld

- **Walk.** Two or three low-priority threads (half the cores, at least two, at most three) walk
  directories breadth first; every listener callback is serialized. A folder costs one listing with
  one attribute read per child; only file headers are ever read, never whole files. Hidden folders,
  the recycle bin and the app-data tree at the top of storage are skipped. The walk is capped at
  250,000 folders and depth 8, which stop it early and say so.
- **Cache.** Each folder is fingerprinted by its direct children's names, kinds, file sizes and
  modification times (a child folder's own time moves when its entries do), mixed with the rule set.
  A known game whose fingerprint is unchanged is only confirmed; a folder known not to be a game is
  not analysed again. Only folders whose fingerprint changed cost detection. A full rescan ignores
  the cache. A registry update changes the salt, so it rescans what it reclassified.
- **Resumable and cancellable.** Results are saved in batches of 200 as the walk goes, and the scan
  belongs to the process (`ScanController`), not the screen: leaving the screen does not stop it and
  coming back reattaches. Stop keeps everything found. Only a complete walk forgets games it no longer
  finds under the folder it scanned; games the person added stay on Home.
- **Sizes.** A game's size needs a walk of the whole game, so it is measured after the list is usable,
  one game at a time, bounded to 200,000 entries each, and sorting by size fills in as it goes.
- **Storage.** `game-library.db`: one `games` table (indexes on name, engine, parent folder, added,
  played, size), the folders a scan found not to be games, and the scan runs. The earlier path list in
  preferences (capped at 200) is imported once, on creation.
- **No slow corners.** Nothing is quadratic in the library. Filters are indexed columns or a range
  over the primary key; text search is the one scan, over a lowercased name column. The list reads
  rows held in memory, queried on one worker thread where a newer request supersedes an older one.
  Support is resolved per engine line, not per game. Nothing touches the disk or the database to draw
  a row.

### The library UI

Home and the scan screen share one list (`LibraryBrowser`): Home shows what was added, the scan screen
shows everything found under the scanned folder with Add per row and Add all shown. Filters: engine,
platform (Android-runnable, Windows, Linux, Web, Archive), status (runs here, needs a core, not
supported here), folder, and text. Sorts: name, recently added, recently played (Home's default),
size. Controller first: the D-pad selects rows and pages the list and never reaches the search field
or the buttons above it; A opens the selected row (on the scan screen it adds a game, and opens one
already added); X opens the filter sheet (every filter, search, sort, clear); Y plays on Home and
opens the scan menu (choose a folder, rescan changed, rescan everything, stop, add all shown) on the
scan screen. Touch taps a row; the search field and Filters button are touch targets; the list has
fast scroll. A row's status line says builds, architecture, what this device can do, and size; "Needs
a plugin" is the only caution colour.

Droidtop (or any caller) can still open a scan at a folder it knows with `dev.enginehost.SCAN` and a
`path` extra. Droidtop's scan starts at once; another app's follows the person's `LAUNCH` decision for
that caller (allowed: at once; blocked: not at all) and otherwise asks first, naming the app and the
folder (review, 2026-10-16).

### Verified and not

Unit tests cover header sniffing, launch ranking, archive rules, registry-derived screening, game
roots, dedupe, the incremental and resumable store contract, and the query builder, all on synthetic
folders and byte headers. The database, the list and the sheets have no unit tests; they need a device
check (see the tracker).

## Decisions of 2026-10-16

Each is the owner's words, or the coordinator's relay of them, with what was done and where it
lives. The owner's standing rule, the same day: "Spec documents need to be updated with decisions."

- **Cores, and the positioning.** Owner: "Yeah, let's switch to cores. We're clearly 'RetroArch
  for VNs', no need to hide it". Engine plugins are *cores* in everything a person or developer
  reads; the README says Enginehost is to visual-novel and PC-game engines what RetroArch is to
  emulators. The identifiers kept for compatibility are listed at the top of
  `engine-bundle-format.md`. Enginehost `8e8fb45`.
- **The Droidtop CA is not shown.** Owner: "Droidtop Core is a certificate identifier. It's
  should never appear surfaced to the user, or in our code". No UI string, code comment or test
  names it; the signing docs call it the Droidtop CA.
- **Signing root, expiry, legacy.** The Enginehost signing root, derived from the CA ("enginehost
  gets its key separately derived from the ca, then plugins are derived from that"), and "Can we
  ignore its expiration date? We don't want older plugins to be invalidated" are in
  `engine-bundle-format.md` "Signatures" (Enginehost `e9e354d`, `419594b`). Legacy keys: "Deprecate
  legacy keys. One droidtop installation currently exists, and it's mine. Detect legacy plugins if
  someone DOES have one installed, and instruct the user to delete the old one and download the new
  one": a core installed with only the legacy signature does not start, and its card and the
  launch say to remove it and install it again (`engine-bundle-format.md` "Legacy signatures").
- **Re-signing stable builds.** Owner: "we need to resign all the stable builds", each published
  as a new stable release beside the original rather than replacing its assets (the owner's
  choice). `engine-bundle-format.md` "Signing, provisioning and the owner's steps"; Enginehost
  `96d2541`, `cfde265`; eight `<tag>-resigned` releases (Droidtop/tracker#550). Promoting other
  v1 builds to stable is the local agent's re-signing, not this repository's: the owner, "Cores
  ... are already being resigned by a local agent. It's a non-issue".
- **Several builds of a line.** Owner: "Users should not be tied to only one plugin version. This
  is one of the benefits of not using apks." / "Several builds can be installed. That's why
  there's a plugin version requirement in the enginehost spec. If a newer version has a
  regression, or certain versions have bugs, we can blacklist or filter them". `SPEC.md` "Several
  builds of a line, side by side"; Enginehost `c7b0baa`.
- **Blacklisting a build is the person's.** Asked whether the revocation list should be able to
  withdraw one build for everyone, the owner: "That's not a thing. Users can blacklist builds
  using the enginehost.json file". Nothing is built for it; a game's `pluginVersion` allowlist
  is the mechanism (`SPEC.md` "Several builds of a line, side by side").
- **A reindex is never lost.** Owner: "Yeah", to fixing the plugins index losing a reindex when
  another commit reaches droidtop-platforms main during the run. The job now rebuilds on the new
  main and retries the push (droidtop-platforms `3b6647c`).
- **Trust stays per build.** The owner chose to keep a decision bound to one exact build, with an
  approval carried to a same-key update and a denial never carried, over trusting a whole line
  (Droidtop/tracker#23).
- **No large arrays on a core's stack.** Relayed from the coordinator after the CMVS crash
  (Droidtop/tracker#558): catsystem2's 256 KB and 128 KB listings move to the heap, as CMVS's did.
  The rule is in `engine-sandbox.md` "Stack size under the sandbox"; catsystem2 engine `8014211`.
- **Repository renames: not now.** Owner: "nope. no need to rename repos yet, that's largely
  cosmetic". If they happen, the earlier plan stands: `enginehost-<x>-core` with a successor map,
  and "It's still the same engine plugim, just renamed. We change nothing about the crypto";
  `plugin-core` becomes `core-wrapper`, `plugin/<version>` becomes `core/<version>`.
- **Automatic updates keep every build.** Asked whether an update should remove the build it
  supersedes, the owner: "No. Multiple builds are allowed. this lets users pick plugin versions
  based on regressions, not just newest". An update installs beside the builds already there
  (`SPEC.md` "Several builds of a line, side by side"); nothing changed in code.
- **Godot's stable releases come back.** The three Godot stable releases (`godot-v44-v1`,
  `godot-v45-v1`, `godot-v46-v1`) had been left as drafts; the owner said yes to publishing them
  again and re-signing them as the other eight were. Godot's `publish-drafted-stable.yml` does the
  publishing, since a session cannot edit releases.
- **Godot packs on SMB shares.** Owner: "Redo it properly". Waiting on a rig reproduction of how
  the share is mounted and what fails before any code, which the owner gave to the coordinator
  ("probably ask the coordinator to do that"): the September fix's diagnosis (native
  `open()` fails where Java's succeeds, in one process) does not hold as written.
