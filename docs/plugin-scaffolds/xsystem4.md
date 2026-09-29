# xsystem4 (AliceSoft System 4) engine plugin: scaffold plan

Step 1 of the xsystem4 effort (the census's new-engine recommendations,
#26: AliceSoft System 4, alongside Buriko/BGI): this page is the written
plan. No repository, no code, and no host change in this step. The sandbox
(`docs/engine-sandbox.md`) and CatSystem2 are deliberately untouched.
Unlike AGS, the host does not yet know this engine: no
`engines-database.json` row classifies a System 4 game, `EngineNames.kt`
has no family name for it, and no `EngineDetector.kt` enrichment reads one.
A bundle alone cannot launch what the scanner cannot classify, so the
host-side wiring a later step needs is listed under "What comes next".

The evidence behind this plan: `docs/engine-bundle-format.md` and
`docs/plugin-catalog.md` read in full; the plugin API in
`plugin-api/src/main/java/dev/enginehost/api/`; the host's launch path and
engine vocabulary (`GameRunner.kt`, `EngineRegistry.kt`,
`EngineDetector.kt`, `EngineNames.kt`, `ControllerBindings.kt`); read-only
clones of the upstream projects -- `nunuhara/xsystem4` at
`04333ad689d7e517a93d58141bb214c19ba16129` (master, 2026-09-21, also its
`nightly` tag) with the fork the task text names, `kichikuou/xsystem4`
(master stale at `6a36fc8f`, 2024-12-29), checked separately, and
`kichikuou/xsystem4-android` at `1c8f691` (v1.0.7, 2026-09-22), which pins
the engine as a submodule at exactly `04333ad`; and two shipped plugin repos
as structural references, `Droidtop/enginehost-buriko-plugin` at its
`plugin-core` branch (`05a0de8`), the model this plan follows, and
`Droidtop/enginehost-love2d-plugin` at its `plugin-core` branch
(`6c0b00c5`), the SDL-family plugin-api precedent the wrapper should copy
rather than invent.

## (a) Upstream xsystem4 and its real Android status

**Which repository is upstream.** The engine's home is
`nunuhara/xsystem4` -- "Cross-platform implementation of AliceSoft's System
4 engine" (the repository description), C11, GPL-2.0, 98 stars, issues
open, actively maintained: master at `04333ad` (2026-09-21), which is also
what its `nightly` tag points at; the newest alpha tag is `alpha-5.1`.
The URL the task names, `github.com/kichikuou/xsystem4`, is a fork of it
whose master is stale (last commit 2024-12-29); the same author's
`kichikuou` account is where the Android port lives, not where the engine
does. Everything below is cited from the live `nunuhara` tree at
`04333ad`, the exact revision the Android port pins.

**Not desktop-only.** Upstream xsystem4 has a real, shipped, in-tree
Android story, and saying otherwise would be false:

- The engine repository itself carries an Android build: the root
  `CMakeLists.txt` is titled "CMakeLists.txt for Android build"
  (`add_library(xsystem4 SHARED)`, `USE_GLES`, links `GLESv3`, finds SDL2 /
  Freetype / SndFile / ZLIB from a staging prefix), and Android-specific
  accommodation code sits in the engine sources under `__ANDROID__`:
  `src/input.c` defers virtual-keyboard key events behind text events
  (Android sends a KEYDOWN/KEYUP pair per soft-keyboard press), synthesizes
  mouse events from touches, `src/font_freetype.c` loads fonts that live
  in APK assets (FreeType cannot open an asset name), `src/video.c` and
  `src/hll/MsgSkip.c` carry smaller screen and skip-mode adjustments.
  Desktop builds use meson instead (`meson.build`; SDL2, freetype, glew or
  GLES via its `opengles` option, libsndfile, libffi, libturbojpeg,
  libwebp, libpng, zlib, cglm, flex, bison; optional FFmpeg and a
  chibi-scheme debugger).

- `kichikuou/xsystem4-android` is the official Android port, by the same
  author/org (the Kichikuou project that maintains the System 3.x
  reimplementation and "Kichikuou on Web"). Self-described
  work-in-progress, but it ships installable releases: v1.0.7 (2026-09-22,
  "Based on nunuhara/xsystem4@04333ad", Rance 9 bugfixes, one newly
  supported game) publishes a single 27 MB APK that contains all its ABIs,
  with a GitHub Releases history back to v1.0.0 and an Obtainium listing.
  Supported environments per its README: Android 5.0 or later; CPU 32-bit
  ARM, 64-bit ARM, or 64-bit x86.

- The port's own CI (`.github/workflows/build.yml`) builds the native
  libraries for all three ABIs on every push (`ABI_NAMES: arm64-v8a
  armeabi-v7a x86_64`, `checkout` with `submodules: recursive`,
  `ninja-build`, the NDK runner image provides, then
  `./build-shared-libs.sh`, then Gradle `assembleRelease`). Published
  releases exist; this plan makes no claim about any particular run's
  color, and nothing here has run anything on a device.

**The port's architecture** (what a bundle keeps, and what it must not):

- `LauncherActivity`/`GameList.kt` -- the game browser: scans its own
  files dir and SD card for folders containing `System40.ini` or
  `AliceStart.ini` (the System 4 game markers), parses the ini for the
  game's name and `SaveFolder`, extracts a launcher icon out of the game's
  Windows `.exe` (`PEResourceExtractor`, PE `RT_GROUP_ICON`), offers
  ZIP install. Browsing is Enginehost's role and is already done better
  here: `GameScanner.kt` classifies via the shared registry and
  `PeIcon.kt`/`GameIcon.kt` already read PE icons. None of this ships.
- `XSystem4Activity` -- an `SDLActivity` subclass; the whole engine front
  end. `getLibraries()` returns `{"SDL2", "xsystem4"}`; `getArguments()`
  returns `["--save-folder", saveDir, "--save-format=rsm", gameRoot]`;
  `onDestroy` kills the process so SDL's native thread cannot outlive the
  Activity. It also handles one engine message,
  `COMMAND_OPEN_PLAYING_MANUAL` (`0x8000`, raised from
  `src/hll/SystemService.c`), by opening the game's `Manual/index.html`
  in a WebView (`ManualActivity`).
- Touch gestures, upstream's own mapping: tap is left-click, tapping the
  black bars outside the game screen is right-click, touch-and-hold is the
  Ctrl key, two-finger swipe is scroll.

**Native libraries and engine data.** The port's root `CMakeLists.txt`
builds a pinned dependency chain as CMake ExternalProjects -- libogg
1.3.5, libvorbis 1.3.7, libjpeg-turbo 3.1.0, libwebp 1.3.2, libpng
1.6.47, libffi 3.4.6, libsndfile (the author's own fork at `be82f4b`,
with FLAC and Opus disabled), libdeflate 1.25 -- with LTO on
(`CMAKE_INTERPROCEDURAL_OPTIMIZATION`), then the engine itself via the
engine's in-tree Android `CMakeLists.txt`, which fetches cglm v0.9.2 and
builds the in-tree `subprojects/libsys4` submodule (the System 4
file-format library: AIN scripts, ALD archives, ACX, save formats). The
port's `cmake/FindSDL2.cmake`/`FindFreetype.cmake` fetch SDL 2.30.9 and
FreeType 2.13.3, pinned by hash. The result is three shared libraries per
ABI -- `libxsystem4.so`, `libSDL2.so`, `libcglm.so` -- installed into
`project/app/src/main/jniLibs/<abi>`, plus the engine's runtime data,
`shaders/` and `fonts/`, installed as APK assets; the engine reads
shaders through `SDL_LoadFile` (`src/video.c`'s `read_shader_file`, which
falls back to the data dir), and on Android SDL's file layer reads assets
out of the APK, so assets are how the data ships. The two bundled fonts
(VL-Gothic-Regular, HanaMinA) carry their own licence files. App-level
settings: `compileSdk 34`, `minSdk 21`, `targetSdk 34`, Kotlin, NDK r23+
(the README's example is 26.1), CMake 3.21+, Java 17 in CI.

**Saves.** The engine's default save location is a SYSTEM location, not
beside the game: `get_xsystem4_home()` resolves `$XSYSTEM4_HOME`, then
`$XDG_DATA_HOME/xsystem4`, then `$HOME/.xsystem4`, else the CWD, and
`config_init()` puts saves at `<home>/<game name>/<SaveFolder>`, where
`SaveFolder` comes from the game's own ini (default `SaveData`). Every
save read and write -- `src/savedata.c`, `src/resume.c` -- goes through
`savedir_path()`, i.e. `config.save_dir`. The command line
`--save-folder <dir>` replaces `config.save_dir` after all ini and user
config is read (`src/system4.c`: `config.save_dir = strdup(savedir)`),
and the engine `mkdir_p`s it. That is the org's save policy as a native
engine feature: the Android port already uses it to point saves at its
own files dir, and an Enginehost bundle points it at the folder the
person chose -- with **no engine patch at all** (AGS, by contrast, needs
its platform driver patched). The port also passes `--save-format=rsm`,
the resume-save format the real engine's games use (the engine's own
default is `SAVE_FORMAT_RSM`); the bundle mirrors the upstream app's
arguments exactly.

**Licence.** xsystem4 and libsys4 are **GPL-2.0-or-later** (`COPYING` is
the GPLv2 text; the source headers say "version 2 of the License, or (at
your option) any later version"). The payload must carry and the
manifest must list every bundled component's licence; the port already
maintains that inventory in `collect-licenses.sh` -- SDL (zlib), FreeType
(dual FTL/GPLv2; the port carries GPLv2.TXT), libogg/libvorbis (BSD),
libjpeg-turbo, libwebp, libpng, libffi (MIT), libsndfile (LGPL-2.1,
the fork), libdeflate (MIT), cglm (MIT), plus the two font licences --
which is the audit starting point, the same job the AGS plan schedules.

**State and compatibility, honestly.** Upstream's own table
(`game_compatibility.md` at `04333ad`) lists **53 games Supported**
(Rance VI, Sengoku Rance including the MangaGamer release, Daibanchou,
DALK外伝, ...), **12 Unsupported** (DUNGEONS & DOLLS, ...), **16
Unknown**, and says plainly that many Unknown games run fine with
quirks. Compatibility is per game, not per engine version; the
capability vocabulary deliberately cannot express it. Nothing in this
plan has been run anywhere; a green build would not be evidence a game
runs either.

## (b) The EnginePlugin mapping, modeled on the Buriko plugin

The task names the Buriko plugin as the model, and it is: the closest
shipped structural relative -- a desktop C engine with SDL2 and no
Android presence of its own, wrapped by a thin Gradle project whose
`EngineHostBurikoActivity` extends `SDLActivity`, loads its libraries
from the bundle, and hands the engine the game folder as
`getArguments()`. But Buriko's wrapper is
`runtimeTransport: "android-activity"`, and that transport is now
closed to new official plugins
(`docs/engine-bundle-format.md`, 2026-09-27: official plugins stop
using it; `docs/engine-sandbox.md` "Single transport" -- an isolated
runtime must be a Service, never an Activity). xsystem4 is SDL2-family,
the exact family the sandbox's `SdlEnginePlugin` adapter was designed
for, and the plugin-api transport is where every official plugin is
heading. **This bundle is authored from the start as
`runtimeTransport: "plugin-api"`.**

The working precedent is the LÖVE plugin's `EngineHostGamePlugin`
(`enginehost-love2d-plugin` plugin-core `6c0b00c5`): it drives SDL's
static lifecycle itself, attaches a real `SDLSurface` into
`session.display()`, and reaches the handful of genuinely
Activity-shaped SDL calls through `EngineHost.activity()`. The xsystem4
wrapper is that shape with this engine's arguments:

| upstream `XSystem4Activity` | Enginehost equivalent |
| --- | --- |
| `EXTRA_GAME_ROOT` (game installation) | `EnginePluginSession.gamePath()`, passed as the engine's one positional argument |
| `EXTRA_SAVE_DIR` -> `--save-folder` | `EngineHost.saveDirectory()` -> `--save-folder <dir>`; the engine does the rest natively (no patch) |
| `--save-format=rsm` | the same argument, mirroring the upstream app |
| `getLibraries() {SDL2, xsystem4}` | `session.bundleDirectory()`; the dex loader already puts the bundle's `lib/<abi>` on the native library path, so `SDL2`/`xsystem4` resolve from the signed payload (a third library, `libcglm`, loads by dependency) |
| `SDLActivity` window, input, lifecycle | the `SdlEnginePlugin` adapter attaches `SDLSurface` into `session.display()`; the host's `RuntimeActivity` owns the window (LOVE's migration is the precedent, including the line-branch seams SDL's Java glue needs) |
| touch gestures (tap=left click, black bars=right click, hold=Ctrl, two-finger=scroll) | unchanged engine behavior once SDL sees host-delivered touch |
| `COMMAND_OPEN_PLAYING_MANUAL` -> `ManualActivity` | open for now: the engine asks the host to show the game's manual; the first bundle logs the request and stays up, and a host mechanism for "engine wants to show the person something" is a later decision, recorded here so it is not improvised later |
| `LauncherActivity`, PE icon extraction, ZIP install | not shipped; `GameScanner.kt` + `PeIcon.kt` already do this host-side |
| `onDestroy` process kill | gone: the host owns the runtime process and ends it |
| `setWindowStyle(true)` SDL workaround | an SDL-glue detail the adapter inherits or the rig check finds |

**Controller input.** xsystem4's own input model is Windows virtual keys
(`key_state[VK_*]`, an SDL-scancode-to-VK table in `src/input.c`) plus
the mouse; it also has an opt-in joypad HLL (up to 4
`SDL_GameController`s, `joybutton_state`, `joy_get_stick_status`),
off by default and enabled per game via the ini's `UseJoypad` or
`--joypad`. Under the plugin-api transport the host owns all pad input
and normalizes it, and there is no raw SDL joystick path for the engine
to bypass into -- the same conclusion the LOVE migration reached. So
the first bundle takes the **shared fallback set**
(`ControllerBindings.forEngine` already answers `common` for an engine
it has no set for; `engine-bundle-format.md` lists `up`, `down`,
`left`, `right`, `confirm`, `cancel`, `menu`, `skip`, `auto`,
`history`, `quick_save`, `quick_load`, `page_previous`, `page_next`,
the stick axes and the triggers), and the wrapper translates each
action into the engine's virtual-key or mouse form. A per-engine set
in the engine's own vocabulary is a later addition only if a game that
uses the joypad HLL needs it; nothing here predetermines its ids.

**Sandbox.** The first bundle targets the ordinary (non-isolated)
plugin-api launch, exactly the sequencing the LOVE adapter is proving:
`usesSurface()`/`attachIsolatedSurface` and the file broker are the
adapter milestone after this one, `isolatable: true` stays the
mandatory goal for every official plugin, and until it lands the
per-launch "Run unsandboxed?" warning keeps showing for this bundle as
for the others. The engine's file reads at launch are the game folder
and its own payload data (shaders, fonts) -- a small audit surface
when that milestone comes.

## (c) Bundle-manifest fields, and the honest ABI assessment

`enginehost/bundle-metadata.json` (the shape both reference plugins
publish; `scripts/build-engine-bundle.py` adds `formatVersion`,
`assetName`, `signing`, `payloadSha256`, `files`, and numbers the third
`pluginVersion` component from the repository's releases):

```json
{
  "bundleId": "dev.enginehost.xsystem4.v1",
  "engine": "xsystem4",
  "pluginVersion": "0.1",
  "apiVersion": 1,
  "entrypoint": "dev.enginehost.plugin.xsystem4.XSystem4Plugin",
  "runtimeTransport": "plugin-api",
  "origin": "https://github.com/droidtop/enginehost-xsystem4-plugin",
  "dexFiles": ["classes.dex"],
  "resourceApks": ["runtime.apk"],
  "capabilities": [
    {
      "id": "system4-standard-v1",
      "engineContext": "standard",
      "runtimeVersion": "1.0",
      "acceptsAnyEngineVersion": true
    }
  ],
  "source": {
    "upstream": "https://github.com/kichikuou/xsystem4-android",
    "revision": "<the pinned port commit; its xsystem4 submodule at 04333ad>"
  },
  "licenses": [
    "GPL-2.0-or-later (xsystem4, libsys4)",
    "...one entry per bundled component, from collect-licenses.sh's inventory..."
  ],
  "notes": "Runs the games xsystem4 runs: upstream's own table lists 53 games Supported, 12 Unsupported and 16 Unknown at the pinned revision. Compatibility is per game, not per version; the engine has no version a System 4 game states."
}
```

- `engine: "xsystem4"` (decided here): the family id a person sees in
  the catalog and in `enginehost.json`, naming the runtime the bundle
  ships, the way `ags` names both AGS's family and its runtime;
  `EngineNames.kt` gains the human name, proposed "AliceSoft System 4".
  One line, one family; `EngineNames.line` treats context `"standard"`
  as an implementation detail, so the catalog says the family name
  alone.
- **Capability.** One line: System 4 has no shipped sub-families or
  version lines to split on -- games state no engine version (the AIN
  file has a bytecode format number the engine reads internally, not a
  product version), and which games run is upstream's per-game table,
  not a version gate. So the capability declares
  `acceptsAnyEngineVersion: true` (KiriKiri's already-published
  precedent) and puts the real compatibility statement in `notes`;
  `runtimeVersion: "1.0"` names the first Enginehost line of the port
  (whose own app version is 1.0.x). The detector enrichment a later
  step adds can read the AIN header's version for diagnostics without
  ever gating on it.
- `dexFiles` is not hand-written: it is computed from the packed APK in
  numeric order (`classes.dex`, `classes2.dex`, ...), because an
  under-declared multidex payload loses the rest of its wrapper at
  launch -- the KiriKiri lesson the AGS plan also records.
- `resourceApks: ["runtime.apk"]`: the wrapper's APK, resources built
  at a distinct package id (`aapt2 --package-id 0x80
  --allow-reserved-package-id`; `docs/engine-bundle-format.md`), which
  the host attaches; the same file may appear in `dexFiles`. The
  engine's runtime data (`shaders/`, `fonts/` with their licence
  files) rides in this APK's assets exactly as upstream ships them,
  since the engine reads them through SDL's Android asset path.
- `declaredOptions`: none in a first bundle. The engine's knobs
  (`--font-mincho`/`--font-gothic`, `--msgskip-delay`, joypad) are
  declared only when the wrapper actually implements a control for
  them; the upstream app exposes no settings either.
- `hostType` needs no value: the host detects `android`.

**ABIs, honestly.** Both required ABIs -- **arm64-v8a and x86_64 --
build upstream**, and so does armeabi-v7a: the port's README and
`build-shared-libs.sh` name exactly `armeabi-v7a`, `arm64-v8a`,
`x86_64`, its CI builds all three on every push, and its released APK
ships them in one archive. **32-bit x86 is not provided by upstream**
(the README says "32-bit ARM, 64-bit ARM, or 64-bit x86") and the bundle
will not carry it either; that is the format's rule working as written
("plus `lib/armeabi-v7a/` or `lib/x86/` where the engine's upstream
provides them"), not a gap needing a `notes` entry, and an x86 device
would need an upstream port first. The packing step still asserts
`lib/arm64-v8a/` and `lib/x86_64/` are both in the payload, because the
two-ABI rule is per bundle, not per upstream. Graphics: the engine
links `GLESv3` with `USE_GLES` on Android, so it needs OpenGL ES 3.0 --
available since Android 4.3, well below the host's minSdk 26 and the
port's minSdk 21. The payload also carries `libcglm.so` per ABI; sizes
are upstream's own released-APK territory (~27 MB for three ABIs with
LTO).

## (d) First CI workflow, modeled on the Buriko plugin repo

The plugin repository is `Droidtop/enginehost-xsystem4-plugin`, created
when this plan executes. It is a fork of `kichikuou/xsystem4-android`
with the engine source arriving as the port already carries it -- a
submodule pinned per line -- so the engine revision is the branch's
content and `source.revision` names it. Branches, per the org model:
`plugin-core` (the wrapper changeset only: workflow, manifest, the
plugin class, Gradle project, docs) and `plugin/1.0` (the pinned port
revision with plugin-core merged in; one line while the port tracks a
single 1.0.x series).

`plugin-core` layout, from the Buriko repo's own shape:

- `enginehost/bundle-metadata.json` -- the manifest above
- `enginehost/LICENSES.md` -- the licence inventory, seeded from
  upstream's `collect-licenses.sh` output and audited before the first
  publish
- `enginehost-public-key.json` -- at the root, as
  `docs/plugin-catalog.md` requires on building branches; derived and
  certified by `scripts/derive-official-key.py` /
  `scripts/certify-repository-key.py`, private half set as the
  repository's `ENGINEHOST_SIGNING_KEY_PEM` secret
- `enginehost-origin.json` -- the implementation identity, as Buriko
  publishes
- `project/` -- the port's own Gradle project (kept: it is upstream's
  one build mechanism, not a re-invented one), with the wrapper's
  entry class added and its manifest carrying the
  `dev.enginehost.plugin.RUN` action and engine metadata the way
  Buriko's does; the upstream `LauncherActivity` browsing surface is
  left behind on the line branch, not shipped
- `.github/workflows/android.yml` -- below

Workflow sketch, job-for-job from the Buriko repo's
`.github/workflows/android.yml` (same guards, same sign/publish/
unstable split), with the native build step being upstream's own:

```yaml
name: Android plugin
on:
  push:
    branches: ["plugin/**"]
  pull_request:
  workflow_dispatch:      # publish / channel (stable|testing) / tag inputs, as in Buriko
permissions:
  contents: read
concurrency:
  group: ${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: true

jobs:
  android:               # builds the payload; no signing key and no write token here
                         # (docs/security/2026-09-24-ci-supply-chain.md)
    # only plugin/** branches build and pack (the LOVE repo's guard: plugin-core
    # alone has no engine tree to build)
    # checkout with submodules: recursive; setup-java 17 temurin;
    # android-actions/setup-android; gradle 8.13
    # native build: apt ninja-build, then
    #   ANDROID_NDK_HOME=$ANDROID_NDK_LATEST_HOME ./build-shared-libs.sh
    #   (upstream's own script, unchanged: ABI_NAMES armeabi-v7a arm64-v8a x86_64,
    #    ANDROID_API_LEVEL 21, LTO, pinned dependency chain, per-ABI
    #    libxsystem4.so + libSDL2.so + libcglm.so into jniLibs/, shaders+fonts
    #    into assets/)
    # gradle :app:assembleDebug
    # verify the APK: aapt dump xmltree ... | grep dev.enginehost.plugin.RUN,
    #   and lib/<abi>/libxsystem4.so present for each of the three ABIs
    # pack the unsigned payload (plugin/** only): runtime.apk, classes*.dex
    #   in numeric order, lib/* for all three ABIs, LICENSES.md and the
    #   component notices; assert lib/arm64-v8a/ and lib/x86_64/ are present;
    #   tar -cf build/unsigned.tar payload bundle-metadata.json; upload

  sign:                  # the org's one signing mechanism, pinned to a full
                         # Enginehost commit SHA (Buriko pins
                         # a8a6270dbfe0e7bd475c6d8209e9615960a8c0d7; the new
                         # repo pins whatever is current when it is created)
                         # uses: Droidtop/enginehost/.github/workflows/sign-engine-bundle.yml@<sha>
                         # secret: ENGINEHOST_SIGNING_KEY_PEM; the key never
                         # shares a runner with the build

  publish:               # manual dispatch only: stable / testing channel; a
                         # bundle is published only once that exact build has
                         # installed and booted a real game on hardware
                         # (docs/plugin-catalog.md's evidence gate)

  unstable:              # every green push to a plugin/* line: rolling
                         # <line>-unstable release, replaced on each build;
                         # both publishing jobs ask droidtop-platforms to
                         # reindex (PLATFORMS_DISPATCH_TOKEN, a no-op without it)
```

Before the first green build: derive and certify the repository key, set
the `ENGINEHOST_SIGNING_KEY_PEM` secret, commit
`enginehost-public-key.json` to the building branches -- the "Keys for
new official repositories" steps in `docs/plugin-catalog.md`. The
`plugin-published` dispatch then registers the repository in the plugins
index, and devices add the origin as official at their next refresh,
with no new Enginehost build.

## What this step deliberately does not do, and what comes next

- **Next steps, each a separate change:** create
  `Droidtop/enginehost-xsystem4-plugin` and land the wrapper -- the
  `SdlEnginePlugin`-shaped entry class modeled on LÖVE's
  `EngineHostGamePlugin`, save routing (one argument), the
  fallback-set-to-virtual-key translation -- against the pinned port
  revision; the host-side wiring: an `engines-database.json` row
  classifying System 4 games on `System40.ini`/`AliceStart.ini`
  (upstream's own detection evidence), landing in droidtop-platforms
  as the one classification authority both apps share, plus the
  `EngineNames.kt` family name, the save-policy classification
  (xsystem4's default is a system save location, so it is a
  save-folder engine, not a beside-the-game one), and detector
  enrichment; a complete licence audit of the dependency chain (the
  `licenses` list is not final until every component's notice has been
  read); and the rig check of a real game upstream lists Supported --
  Sengoku Rance or Rance VI -- on arm64 hardware and an x86_64
  emulator, batched through the rig queue. On-device checking is not
  available from here, and a green build is not proof a game runs.
- **Open, recorded so they are decided later, not improvised:** the
  engine's "open the playing manual" message needs a host mechanism for
  an engine asking to show the person something; a per-engine
  controller action set exists only if a joypad-HLL game needs one.
- **Deliberately untouched in this step:** the engine sandbox
  (`docs/engine-sandbox.md`; this bundle starts on the plugin-api
  transport but is not yet `isolatable: true`), CatSystem2 (the
  sandbox's first milestone), and the host's own code, which needs no
  change for this document.
