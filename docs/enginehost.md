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

### The model: Enginehost is the emulator, plugins are cores, games are content

Owner decision, 2026-10-01: Enginehost is a complete game manager and an emulator in its own right,
the way RetroArch is, not a frontend. Each engine plugin is a core; a game folder is content; the
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
platform (Android-runnable, Windows, Linux, Web, Archive), status (runs here, needs a plugin, not
supported here), folder, and text. Sorts: name, recently added, recently played (Home's default),
size. Controller first: the D-pad selects rows and pages the list and never reaches the search field
or the buttons above it; A opens the selected row (on the scan screen it adds a game, and opens one
already added); X opens the filter sheet (every filter, search, sort, clear); Y plays on Home and
opens the scan menu (choose a folder, rescan changed, rescan everything, stop, add all shown) on the
scan screen. Touch taps a row; the search field and Filters button are touch targets; the list has
fast scroll. A row's status line says builds, architecture, what this device can do, and size; "Needs
a plugin" is the only caution colour.

Droidtop (or any caller) can still open a scan at a folder it knows with `dev.enginehost.SCAN` and a
`path` extra.

### Verified and not

Unit tests cover header sniffing, launch ranking, archive rules, registry-derived screening, game
roots, dedupe, the incremental and resumable store contract, and the query builder, all on synthetic
folders and byte headers. The database, the list and the sheets have no unit tests; they need a device
check (see the tracker).
