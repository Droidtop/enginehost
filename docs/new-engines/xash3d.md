# Xash3D FWGS (GoldSrc) plugin: scope

The cheapest new-engine item in the 2026-09-25 engine census (its GoldSrc
cluster and its recommendation list's item 3): 10 census rows are GoldSrc
games,
and the open replacement runtime is actively maintained and already ships
Android builds upstream. This page is the written scope for that plugin --
upstream Android build, the native libraries a bundle must carry, the
licence facts a signed payload must state, the mapping onto Enginehost's
launch path, and the ABI check. No repository, no code and no host change
in this step; `docs/plugin-scaffolds/ags.md` remains the full template for
the plugin step (the AGS scaffold plan, written against the same bundle
contract), and this page defers to it for the workflow skeleton instead of
duplicating it.

What the host already knows: the shared `engines-database.json` (v5) has a
`goldsrc` row -- detection-only, `valve/liblist.gam` at the game-folder
root, `enginehost: null`, notes "No enginehost plugin yet" -- and
`EngineNames.family()` already answers "GoldSrc" in its
recognised-but-nothing-here-runs block. Detection exists; everything past
it does not.

Evidence: the upstream tree read on 2026-09-29 at master
(`FWGS/xash3d-fwgs`: README.md, `wscript`, `.gitmodules`,
`.github/workflows/c-cpp.yml`, `scripts/gha/build_android.sh`,
`scripts/configure-ninja.py`, `common/backends.h`, `game_launch/game.cpp`,
`Documentation/gpl_copyright_header.h`, `android/app/build.gradle.kts`,
`android/settings.gradle.kts`, `android/app/src/main/AndroidManifest.xml`,
`android/app/src/main/java/su/xash/engine/XashActivity.java`),
`FWGS/hlsdk-portable`'s README and LICENSE at master, the shared database
read from `droidtop/droidtop-platforms` main, and on this side
`docs/engine-bundle-format.md`, `docs/engine-sandbox.md`,
`docs/plugin-catalog.md`, `docs/plugin-scaffolds/ags.md`,
`RuntimeActivity.kt`, `EnginehostComponentFactory.kt`, `GameRunner.kt`,
`PluginRegistry.kt`, `PluginCapabilities.kt`, `EngineNames.kt`,
`EngineRegistry.kt`.

## (a) The real upstream Android port

The Android port lives **in the engine tree**: `android/` is a Kotlin-DSL
Gradle
project with a single `:app` module (`settings.gradle.kts`:
rootProject.name "Xash3D FWGS", `include(":app")`), namespace and
applicationId `su.xash.engine`, `compileSdk`/`targetSdk` 35, `minSdk` 21,
`ndkVersion 29.0.14206865`. Two activities matter:

- `su.xash.engine.MainActivity` -- the game browser, the self-updater
  (ACRA, `REQUEST_INSTALL_PACKAGES`, a `continuous` build type signed with
  the committed debug key and `ENABLE_AUTO_UPDATE`) and the settings
  screens. Browsing, updating and settings are Enginehost's roles, not the
  bundle's, so none of this ships.
- `su.xash.engine.XashActivity` -- the engine front end. It extends the
  vendored `org.libsdl.app.SDLActivity` (SDL's Java layer is pulled in from
  `3rdparty/SDL/android-project` via `sourceSets`), loads exactly two
  libraries (`getLibraries()` = `{"SDL2", "xash"}`), and feeds the engine
  through environment variables it derives from its launch intent
  (`getArguments()`): `gamedir` → `XASH3D_GAME` (default `valve`),
  `gamelibdir` → `XASH3D_GAMELIBDIR`, `basedir` → `XASH3D_BASEDIR` (default
  `<external storage>/xash`), `pakfile` → `XASH3D_EXTRAS_PAK2`, plus a
  free-form `argv` (default `-console -log`) and a `KEY=value` `env`
  array. It also points `XASH3D_RODIR` at the app's own
  `filesDir/gamelibs`, upstream's comment calls that "a supplier for
  downloaded game libraries".

The native build is upstream's own, per ABI: AGP's experimental Ninja
integration calls `scripts/configure-ninja.py` once per
`ninja.abiFilters` entry, which configures **three** components and then
the engine -- SDL2 (CMake, cloned and pinned at tag `release-2.32.8`),
`hlsdk-portable` at its `mobile_hacks` branch (CMake with
`-DANDROID_APK=ON`), `mainui` (`-DBUILD_AS_PART_OF_ENGINE=ON`), then
`waf configure --android=<abi>,,<minsdk> -s <SDL>
--skip-sdl2-sanity-check --enable-bundled-deps` and a Ninja build of the
engine. The `wscript` Android profile sets the renderer mix (GLES1 via
nanogl, gl4es and gles3compat on; desktop GL and GLWES off), builds no
launcher binary on Android (SDL's main drives the engine), and carries the
engine version constant `VERSION = '0.99'` (the app's own versionName is
`"0.21-<git hash>"`).

**Upstream CI ships Android builds on every push.**
`.github/workflows/c-cpp.yml` has an `ubuntu-latest` matrix entry
(`targetos: android`, `targetarch: multiarch`) whose
`scripts/gha/build_android.sh` runs `./gradlew assembleContinuous` and
renames the APK to `artifacts/xash3d-fwgs-android.apk` (plus a mappings
archive); the release job repackages the artifacts into the GitHub
release on every push to master (the `continuous` tag the README points
installers at). So "upstream ships Android builds" is a per-push green CI
fact, not a stale release asset. That is evidence the toolchain builds; it
is not evidence a game runs -- the rig check belongs to the plugin step,
same as AGS.

## (b) Native libraries, and where the game logic comes from

A GoldSrc game is *data plus game logic*: the person supplies the `valve/`
folder (the README's install step is "copy the valve directory"; the
registry row detects exactly that), and the engine supplies everything
else. The per-ABI native set the upstream Gradle project packages:

- `libSDL2.so` -- SDL2 `release-2.32.8`, cloned and built by the same
  configure script that builds the engine.
- `libxash.so` -- the engine (`waf`'s `engine` subproject), with the menu
  compiled in: `mainui` builds `BUILD_AS_PART_OF_ENGINE`, and the wscript
  skips its own mainui subproject on Android
  (`Subproject('3rdparty/mainui', ... DEST_OS != 'android')`).
- The renderer libraries the Android profile enables (nanogl/GLES1, gl4es,
  gles3compat; software off by default).
- The Half-Life **game logic**, built from `FWGS/hlsdk-portable`'s
  `mobile_hacks` branch with `-DANDROID_APK=ON` -- the server and client
  libraries for the `valve` gamedir. GoldSrc games ship Windows x86
  `hl.dll`/`client.dll`, which no Android ABI can load, so the plugin
  cannot run a single title without these; the exact `.so` names are read
  from the hlsdk Android build output when the wrapper repo is created.
  `hlsdk-portable` also carries restored source for the Half-Life
  expansions and some mods as branches (its README and wiki), which is how
  any census title beyond Half-Life itself would ever run -- see (f).

The `xash-extras` submodule is mounted as APK assets by the Gradle
project (`assets.directories.add(.../xash-extras)`); the wrapper's
resource APK carries it the same way. The bundle's `lib/<abi>` set is
whatever this same build produces per ABI -- the plugin repo builds the
upstream configuration, it does not invent a second one.

## (c) Licence: what a signed payload must state

**The engine is GPL-3.0-or-later, not the GPL-2.0 the census line says.**
There is no `COPYING`/`LICENSE` file at the repository root (GitHub's
licence detection returns nothing; the raw paths 404) -- the licence
statement lives in the per-file headers. The project's own canonical
header template, `Documentation/gpl_copyright_header.h` ("do not include
this file, only copy the header"), is GPL **"either version 3 of the
License, or (at your option) any later version"**, and every header sampled
across the tree matches it (`game_launch/game.cpp` (Uncle Mike, 2011),
`common/backends.h` (Mittorn), `scripts/configure-ninja.py` (Velaron)).
The census's "GPL-2.0" is recorded here as not what the tree says, so the
bundle's `licenses` list does not inherit it. The full per-file audit is
still the pre-signing step it was for AGS; a GPL-3+ engine also decides
the bundle's own licence posture, which is the creating repo's call.

**The game logic is not GPL at all.** `FWGS/hlsdk-portable`'s `LICENSE` is
Valve's Half-Life 1 SDK License: free use, modification and redistribution
in source and object form, with the licence file required to travel and
commercial use reserved. The payload therefore carries two licence texts
side by side -- the GPL for the engine half, Valve's SDK licence for the
`valve` game-logic libraries -- and `licenses` names both, the same way
the godot bundle's component licences travel.

Components the build bundles, to be confirmed by the pre-signing audit:
SDL2 (zlib), the Xiph codecs opus/vorbis/ogg/opusfile (BSD), bzip2,
mbedtls (Apache-2.0), libbacktrace (BSD), and the FWGS submodules mainui,
nanogl, gl-wes-v2, gl4es, MultiEmulator, maintui, freevgui, xash-extras,
library_suffix, yy-thunks -- each one's notice is read before the first
sign, exactly the AGS scaffold's "not final until every bundled
component's notice has been read".

## (d) Mapping onto Enginehost's transports

**Recommendation: `android-activity`** (`PluginRegistry.kt:228-229`).
`XashActivity` is an SDLActivity that owns the window, the input dispatch
and the GL lifecycle -- the shape the transport exists for, and the
KiriKiri precedent's exact profile (its metadata declares
`runtimeTransport: "android-activity"` with the engine's own activity
reading the host extras directly). The `plugin-api` alternative means
tearing SDLActivity's Activity-ness into a host-attachable view -- the
godot-fragment surgery -- and buys nothing for a first bundle; the entry
class survives unchanged into that later shape. As an activity-transport
plugin it stays on the engine sandbox's layer 1
(`docs/engine-sandbox.md`: activity-transport plugins are layer-1-only
until the host-side rewrite that document scopes), where the `:runtime`
seccomp filter blocks IP sockets: the engine's network features (master
server, server browser, the NAT-bypass, self-update) are dead inside
Enginehost by design, and **single-player is the supported scope**. The
upstream manifest's `INTERNET`/`REQUEST_INSTALL_PACKAGES`/
`MANAGE_EXTERNAL_STORAGE` lines never cross over -- a bundle installs
nothing and requests nothing; the host's process sandbox governs.

The patch is small and lands where the branch model puts upstream-file
changes (the line branch): a `XashActivity` that reads the host's extras
instead of its own, the way KiriKiri's does. Upstream extra → host
equivalent:

| upstream | host |
| --- | --- |
| `basedir` (`XASH3D_BASEDIR`, the folder containing `valve/`) | `dev.enginehost.runtime.PATH` (`RuntimeActivity.kt:303`) |
| `gamedir` (`XASH3D_GAME`, default `valve`) | `dev.enginehost.runtime.EXEC_FILE` carries the gamedir name the detector chose; `valve` while the registry's row is the whole truth |
| `gamelibdir` (`XASH3D_GAMELIBDIR`) | `dev.enginehost.runtime.BUNDLE_ROOT` (`EnginehostComponentFactory.kt:86`) + the running ABI: the engine dlopen's the Half-Life logic out of the payload's `lib/<abi>`, the same role upstream points at `filesDir/gamelibs` |
| `argv` (default `-console -log`) | fixed at that default for the first bundle |
| `pakfile` (`XASH3D_EXTRAS_PAK2`), `env` array, `usevolume`, `package` | unused by the wrapper |
| (nothing -- the engine has its own touch + SDL2 gamepad input) | **no `dev.enginehost.runtime.CONTROLLER_BINDINGS` sent** (`GameRunner.kt:181` attaches a map only when one exists): capability `controllerInput: "NATIVE"`, the honest default every shipped bundle already uses |

**Saves: no routing change.** Xash3D writes saves and config inside the
game tree (`<basedir>/<gamedir>/SAVE/`, `config.cfg` beside it) -- an
engine that saves beside the game keeps doing so, and the host's
`dev.enginehost.runtime.SAVE_PATH` extra stays unused by this plugin. The
`XASH3D_RODIR` read-only-dir
mechanism stays upstream's answer for read-only installs and is not wired
up by a first bundle.

**Host-side ride-alongs when the plugin lands, none of them new
mechanisms:**

1. The shared database row gains its `enginehost` mapping
   (`family: "goldsrc"`, `context: "standard"`) and the `ENGINEHOST`
   strategy -- a `droidtop-platforms` change both apps pick up on
   refresh, per the one-classification-authority rule
   (`EngineRegistry.kt:13-26`).
2. `EngineNames.line()` needs `"goldsrc"` added beside `"love2d"`/`"ags"`
   (`EngineNames.kt:137`) so a `standard` context does not render as
   "GoldSrc standard".
3. No `ControllerBindings` action set and no `EngineDetector` enrichment
   in the first bundle; `steam.inf` in a GoldSrc install is a candidate
   version marker for a later enrichment, verified before it is claimed.

**Quirks the rig check must watch** (same class as KiriKiri's
`isTaskRoot`/`getClassLoader` findings): `XashActivity.onDestroy` calls
`System.exit(0)` -- upstream's own comment calls it a temporary
global-state reset -- which kills the `:runtime` process at every destroy
rather than only at clean exit; and SDLActivity may carry its own
task-root/class-loader assumptions the launch screen has to survive.

## (e) Manifest fields, and the ABI check

First bundle, modelled on the two shipped plugins' metadata (the creating
repo finalises the ids):

```json
{
  "bundleId": "dev.enginehost.goldsrc.v1",
  "engine": "goldsrc",
  "pluginVersion": "0.1",
  "apiVersion": 1,
  "entrypoint": "su.xash.engine.XashActivity",
  "runtimeTransport": "android-activity",
  "origin": "https://github.com/droidtop/enginehost-xash3d-plugin",
  "dexFiles": ["classes.dex", "..."],
  "resourceApks": ["runtime/xash3d.apk"],
  "capabilities": [
    {
      "id": "goldsrc-xash3d-fwgs-v1",
      "engineContext": "standard",
      "runtimeVersion": "0.99",
      "acceptsAnyEngineVersion": true,
      "controllerInput": "NATIVE"
    }
  ],
  "source": {
    "upstream": "https://github.com/FWGS/xash3d-fwgs",
    "revision": "<pinned commit>"
  },
  "licenses": ["GPL-3.0-or-later", "Half-Life 1 SDK License (Valve)", "...one entry per audited component..."]
}
```

- `runtimeVersion` quotes the engine's own version constant
  (`wscript`: `0.99`); `acceptsAnyEngineVersion: true` is the KiriKiri
  precedent (`PluginCapabilitiesTest.kt:13`) for a family whose folders
  carry no readable runtime version -- which is exactly a replacement
  runtime's situation.
- `resourceApks` is the one runtime APK, its resources at a distinct
  package id (`aapt2 --package-id 0x80`), carrying the xash-extras assets
  and the wrapper's strings; `dexFiles` is computed from that APK in
  numeric order like KiriKiri's CI does.
- The signing job, unstable/publish channels and the repository key steps
  are `docs/plugin-scaffolds/ags.md` (d) verbatim -- the same
  `sign-engine-bundle.yml` pin, the same `ENGINEHOST_SIGNING_KEY_PEM`
  secret, the same `enginehost-public-key.json` at the root -- with one
  engine-job delta: the build *is* upstream's own Gradle/Ninja
  configuration run per ABI, and upstream's mappings archive is kept
  beside the payload for crash symbolizing.

**ABI check: the required pair does not both build upstream.** The
upstream Gradle project builds `armeabi-v7a`, `arm64-v8a` and `x86`
(`ninja.abiFilters` in `android/app/build.gradle.kts`) -- **no x86_64**.
The bundle contract requires `lib/arm64-v8a/` and `lib/x86_64/` at
minimum, plus `armeabi-v7a` or `x86` where upstream provides them
(`docs/engine-bundle-format.md:111-112`). The engine itself targets x86_64
elsewhere (its desktop builds are 64-bit) and the per-ABI configure takes
the ABI straight from Gradle, so the fork's change is adding `x86_64` to
that one set -- but nobody upstream builds the Android/x86_64 combination
today, not in CI and not in a release, so the first green x86_64 build of
engine + menu + renderers + hlsdk `mobile_hacks` is this plugin repo's own
first proof, not a reuse of upstream's. The packing step still asserts
both required ABIs in the payload (the rule is per-bundle, not
per-upstream), and the rig check runs the result: arm64 hardware and the
x86_64 emulator, single-player Half-Life.

## (f) What this step deliberately does not do

- **Create `Droidtop/enginehost-xash3d-plugin`** -- a fork of
  `FWGS/xash3d-fwgs`, `plugin-core` holding the wrapper (metadata, the
  patched-activity glue, resource APK project, workflow, key), `plugin/<line>`
  pinning one upstream master revision with plugin-core merged in; the
  line name is proposed as `plugin/0.21` (the Android app's version line).
- **Cover the census titles beyond Half-Life itself.** The first capability
  serves `valve` (Half-Life), the only gamedir whose game logic upstream's
  own
  build produces. Expansions and mods need their `hlsdk-portable` branches
  built per gamedir, plus registry-refinement rows (the cmvs pattern) if
  their folders do not contain `valve/`; which gamedirs the census's ten
  titles actually need is in the private census sheet and is the creating
  repo's first decision after the bundle runs.
- **The full licence audit, the first x86_64 build, and the rig check**
  (System.exit quirk included) -- all named above, all part of the plugin
  step, none provable from this session.
