# AGS engine plugin: scaffold plan

Step 1 of the AGS effort (the census's new-engine recommendations, AGS's
batch, #26): this page is the written plan. No repository, no code, and no
host change in this step. The sandbox (`docs/engine-sandbox.md`), CatSystem2,
and the detection/version-reader code are deliberately untouched: the host
already knows AGS -- `EngineDetector.kt`'s `ags` detection (the `.ags` data
file, or the old-format `.exe` carrying it, names its editor version),
`EngineNames.kt`'s "Adventure Game Studio", and `ControllerBindings.kt`'s
`ags` action set.

The evidence behind this plan: `docs/engine-bundle-format.md` and
`docs/plugin-catalog.md` read in full; the plugin API in
`plugin-api/src/main/java/dev/enginehost/api/`; the host's launch path
(`GameRunner.kt`, `EnginehostComponentFactory.kt`, `RuntimeActivity.kt`);
a read-only clone of `adventuregamestudio/ags` at `83c6a082bd` (master,
2026-09-23); and two shipped plugin repos cloned read-only as structural
references, `Droidtop/enginehost-godot-plugin` at its `plugin/4.5` branch
(`c7017632`) and `Droidtop/enginehost-kirikiri-plugin` at its `plugin/stable`
branch (`86eaf827`).

## (a) The real upstream AGS Android port

The official engine repository is `adventuregamestudio/ags` (branch
`master`; the version string is `3.6.4.0`, `Common/ac/def_version.h`). The
Android port lives **in that tree**, not in a separate community
repository: the standalone ports and the editor's Android builder plugin
that predate it are no longer maintained, and the in-tree port is the one
the project's own CI builds and publishes:

- `Android/agsplayer/` -- the "AGS Player" app end users install: a game
  browser (`MainActivity`) that requests `MANAGE_EXTERNAL_STORAGE` and
  hands a chosen folder to `AGSPlayerRuntimeActivity`. The browsing role
  is Enginehost's, not the bundle's, so the player app is not what we
  ship.
- `Android/library/runtime/` -- an Android library module
  (`uk.co.adventuregamestudio.runtime`) carrying the runtime itself:
  `AGSRuntimeActivity`, the settings/credits activities, and a vendored
  copy of SDL2's `SDLActivity` under `org.libsdl.app`.
- `Engine/platform/android/acpland.cpp` -- the engine's Android platform
  driver: file access, the save-directory logic, logging, JNI glue.

**Packaging.** Upstream CI (`.github/workflows/build.yml`, job
`build-android-bundle`) builds on ubuntu-latest with Java 17 (temurin), the
command-line SDK tools, `ndk;25.2.9519653` + `build-tools;34.0.0`, and
`Android/and-build.sh`: prepare, `assembleDebug`, `assembleRelease`, rename
to `AGS-<version>-{debug,release}.apk`, and a project archive; the release
job publishes those APKs on tag pushes. The module's settings: `compileSdk
34`, `minSdk 19`, `targetSdk 33`, `ndkVersion 25.2.9519653`, CMake >= 3.22.1,
`-DANDROID_APP_PLATFORM=android-16`, `-DANDROID_STL=c++_static`.

**Native libraries.** The `:runtime` module builds the engine through the
repository's top-level CMake (`externalNativeBuild { cmake { path
'../../../CMakeLists.txt' } }`) with
`abiFilters 'armeabi-v7a', 'arm64-v8a', 'x86', 'x86_64'`. A running game
loads three libraries (`AGSRuntimeActivity.getLibraries()`):

- `libSDL2.so` -- SDL2 `release-2.30.11`, fetched and built from source by
  `CMake/FetchSDL2.cmake` (URL and SHA-1 pinned in-tree); SDL_sound is
  fetched and pinned the same way (`CMake/FetchSDL_Sound.cmake`).
- `libengine.so` -- CMake target `engine`, `add_library(engine SHARED)` when
  the platform is Android (`Engine/CMakeLists.txt:7`).
- `libags.so` -- CMake target `ags`, `add_library(ags SHARED)` on Android
  (`Engine/CMakeLists.txt:597`): the SDL `main`/entry layer that drives
  `engine`.

The Android build compiles the engine's own plugin set in as well
(agsblend, agsflashlight, ags_parallax, ags_snowrain, agstouch,
agspalrender; `Engine/CMakeLists.txt:482-533`), so no separately-shipped
engine components are expected.

**Licence.** The AGS source is under the **Artistic License 2.0**
(`License.txt`, full text in-tree; "Package" means the AGS source; original
author Chris Jones, copyright 1999-2011 Chris Jones and 2011-2026 various
contributors per `Copyright.txt`; the Android port was created by Jochen
Schleu, "JJS"). The native tree additionally carries third-party components
whose licences a bundle must carry in the payload and list in `licenses`:
SDL2 (zlib), SDL_sound, the Xiph OGG/Vorbis/Theora codecs, tinyxml2, miniz,
glm, stb, aastr, freetype, alfont, apeg, glad, and libcda (BSD). The godot
bundle is the precedent for how a component licence travels:
`LICENSE-spine-runtimes.txt` in the payload beside `LICENSE.txt`, named in
`licenses`.

**State.** At `83c6a082bd` (2026-09-23) the upstream CI "Android" job is
green (run `35917672792`): the Android toolchain builds the APKs on every
push. That is not evidence a game runs on a device; the rig check that
belongs to this effort is a later step.

## (b) How the entry point maps onto this org's transports

The upstream entry point is `AGSRuntimeActivity`
(`Android/library/runtime/.../AGSRuntimeActivity.java`), which extends the
vendored `SDLActivity`. Its launch: `onCreate` reads the intent extras
`filename` (path of the `.ags` data file, or of an old-format `.exe` that
carries the data), `directory` (the game directory) and `loadLastSave`
(boolean); it pre-reads the game's own `android.cfg` for screen rotation;
SDL then loads the three libraries and runs the engine's native main with
the game file as its single argument.

This org has two transports, named in `PluginRegistry.kt:184-185`:
`"plugin-api"` -- the host's `RuntimeActivity` instantiates a class
implementing `dev.enginehost.api.EnginePlugin` -- and `"android-activity"` --
the bundle's own Activity: `GameRunner.kt:145` routes the launch through
the host-declared `BundledActivityProxy`, and
`EnginehostComponentFactory.instantiateActivity` (lines 20-79) requires
`runtimeTransport == "android-activity"`, re-verifies the installed
bundle, attaches the `resourceApks`, and instantiates the `entrypoint`
class through the plugin's dex loader, which it must assign to `Activity`
(line 74). The shipped KiriKiri plugin is the precedent for this shape: its
metadata declares `"runtimeTransport": "android-activity"` with the
engine's own `com.yuri.kirikiri2.MainActivity` as entrypoint, and its
`KR2Activity` reads the host's extras
(`dev.enginehost.runtime.PATH`, `dev.enginehost.runtime.ENGINE_CONTEXT`)
directly.

**Recommendation: `android-activity`.** AGS's frontend is an Activity that
owns the window, the input dispatch and the SDL lifecycle; the
android-activity transport exists for exactly that, and it is the smaller
change (KiriKiri did it for a cocos2d-x Activity; the godot line did the
plugin-api route only after its own fork refactored the engine's Android
front end into a host-attachable fragment). The mapping, upstream to host:

| upstream | host equivalent |
| --- | --- |
| `filename` extra | `dev.enginehost.runtime.PATH` (the game folder) + `dev.enginehost.runtime.EXEC_FILE` (the data file within it, the detector's `execFile`) |
| `directory` extra | `dev.enginehost.runtime.PATH` |
| its save location | `dev.enginehost.runtime.SAVE_PATH` (see save routing below) |
| (nothing) | `dev.enginehost.runtime.CONTROLLER_BINDINGS` -- the host's resolved map over the `ags` action set (`ControllerBindings.kt:257-274`: `ags_mouse_left`, `ags_wheel_north`, `ags_key_escape`, the `ags_pointer_x`/`ags_pointer_y` axes, and the rest), which the engine front end translates into AGS input events |
| `loadLastSave` | a declared option, off by default |
| native libraries from the app package | `dev.enginehost.runtime.BUNDLE_ROOT` (`EnginehostComponentFactory.kt:60,86`); the plugin dex loader already puts the bundle's `lib/<abi>` on its native path (lines 63-69), so `System.loadLibrary` for `SDL2`/`engine`/`ags` resolves from the signed payload |
| the game's `android.cfg` | unchanged -- it is game content the engine reads from the game folder |

Work this mapping implies in the engine tree (the fork carries it; see (d)):

- **Save routing.** The engine's Android save location is its own:
  `GetUserSavedgamesDirectory()` writes into the process CWD when that is
  writable (the `./tmptest.tmp` probe in `MakeGameSaveDirectory`) and falls
  back to `android_base_directory`, the game folder
  (`Engine/platform/android/acpland.cpp:437-458`). Under Enginehost that
  CWD is the host app's private directory, so the plugin must make the
  engine's SYSTEM save location mean the folder the person chose: seed
  `android_save_directory` from `SAVE_PATH` (a patched
  `AGSRuntimeActivity` passes it down, or the platform driver reads it).
  That is the org's save policy: the engine's save logic is unchanged,
  only what its system location means.
- **Activity quirks.** KiriKiri's two quirks apply here too: `isTaskRoot()` must
  answer true (the engine treats a non-root task as a duplicate instance
  and finishes itself in `onCreate`) and `getClassLoader()` must answer the
  bundle's loader (the engine caches it for the JNI calls it makes on its
  GL thread). SDLActivity may have equivalents of its own; the rig check
  finds them.
- **What the activity transport does not have.** A bundled Activity has no
  `EngineHost` object, so `EngineHost.fail(message)` and
  `restart(arguments)` (added after the first hosts shipped;
  `docs/engine-bundle-format.md`, "Host services added after apiVersion 1
  shipped") do not cover this shape: a late engine failure reports through
  the Activity's own end, and the host's `CrashWatch`, armed for the launch
  at `EnginehostComponentFactory.kt:38`, is what turns that into a launch
  screen. The host menu shortcut (Select+Start out of the box) is reserved
  in both transports and cannot be refused.

**The alternative, for the record: `plugin-api`.** A wrapper implementing
`EnginePlugin.onCreate(EnginePluginSession)` gets `session.gamePath()`,
`session.execFile()`, `session.bundleDirectory()`, `session.optionsJson()`,
`session.host()` (`EngineHost.saveDirectory()`, `fileSystem()`, `fail()`,
...) and a `ViewGroup display()` to attach rendering into; controller
actions arrive through `onControllerEvent(EngineControllerEvent)`. That is
the shape layer 2 of the sandbox will need, since an Activity cannot run
under `isolatedProcess` (`docs/engine-sandbox.md` keeps
activity-transport plugins on layer 1 until the host-side rewrite it
scopes). But getting AGS there means tearing SDLActivity's
Activity-ness into a host-attachable view, the godot-fragment precedent,
and a far larger engine-side change. A first bundle takes the
android-activity transport; the entry class can stay the same when the
plugin-api shape is built, so nothing in this choice is spent.

## (c) Bundle-manifest fields, and the ABI question

The author declares `enginehost/bundle-metadata.json` (the shape both
shipped plugins use); `scripts/build-engine-bundle.py` then adds
`formatVersion`, `assetName`, `signing`, `payloadSha256` and `files`, and
sets the third `pluginVersion` component to the CI run counter. First AGS
bundle:

```json
{
  "bundleId": "dev.enginehost.ags.v36.v1",
  "engine": "ags",
  "pluginVersion": "0.1",
  "apiVersion": 1,
  "entrypoint": "uk.co.adventuregamestudio.runtime.AGSRuntimeActivity",
  "runtimeTransport": "android-activity",
  "origin": "https://github.com/droidtop/enginehost-ags-plugin",
  "dexFiles": ["classes.dex", "..."],
  "resourceApks": ["runtime/ags.apk"],
  "capabilities": [
    {
      "id": "ags-3.6-standard-v1",
      "engineContext": "standard",
      "runtimeVersion": "3.6.4",
      "supportedSeries": ["3.6"]
    }
  ],
  "source": {
    "upstream": "https://github.com/adventuregamestudio/ags",
    "revision": "<pinned commit>"
  },
  "licenses": ["Artistic License 2.0", "...one entry per bundled third-party component..."],
  "notes": "<only if something is not true that a reader would assume>"
}
```

- `engineContext` is `"standard"`: `EngineNames.kt:124` treats `ags` the
  way it treats `love2d` (context `standard` names the bare family).
- `runtimeVersion`/`supportedSeries` track the engine line: the host's
  detector reads the editor version out of the game data (the
  `3.6.1.14`-style values in `EngineDetectorTest`), so
  `supportedSeries: ["3.6"]` admits the whole line while the capability's
  exact `runtimeVersion` records what is actually bundled.
- `dexFiles` is not hand-written: KiriKiri's CI computes it from the APK it
  packs (`classes.dex`, `classes2.dex`, ... in numeric order, not lexical),
  because a multidex payload that under-declares its dex loses the rest of
  the wrapper at launch.
- `resourceApks` is the one runtime APK, built with its resources at a
  distinct package id (`aapt2 --package-id 0x80
  --allow-reserved-package-id`; `docs/engine-bundle-format.md`), which the
  host reads back out of `resources.arsc` and refuses on collision. The
  same file may also appear in `dexFiles`, as the godot bundle's
  `runtime/godot.apk` does.
- `declaredOptions`: none in a first bundle. The per-game knobs
  (rotation, mouse emulation, renderer, ...) already live in the game's
  own `android.cfg`, and a user option is declared only when the wrapper
  actually implements it.
- `hostType` needs no value: the host detects `android` from the dex and
  the entry class and records the detected value.
- `notes`: not needed for a missing ABI, because there is none -- see
  below.

**ABIs: both required ones build upstream, and so do the two others.** The
`:runtime` module's `defaultConfig` declares
`abiFilters 'armeabi-v7a', 'arm64-v8a', 'x86', 'x86_64'`, and the upstream
CI's Android job builds that whole set on every push (green at
`83c6a082bd`, 2026-09-23). Nothing is missing, so this engine is safe to
commit to on the ABI rule: the bundle carries **all four** `lib/<abi>`
directories, the format's `lib/arm64-v8a/` and `lib/x86_64/` "at minimum,
plus `lib/armeabi-v7a/` or `lib/x86/` where the engine's upstream provides
them", and no metadata `notes` entry has to name a prebuilt the host would
otherwise fail at `System.loadLibrary`. The packing step still asserts the
two required ABIs are in the payload, because the rule is per-bundle, not
per-upstream.

## (d) First CI workflow, modeled on the existing plugin repos' own structure

The plugin repo is `Droidtop/enginehost-ags-plugin`, created when this plan
executes. It is a fork of `adventuregamestudio/ags` with the enginehost
wrapper layered on -- both shipped plugin repos carry their engine's source
(godot's is the engine itself, KiriKiri's is the Kirikiroid2 fork), so the
engine revision is the branch's content and `source.revision` names it.
Branches: `plugin-core` (the wrapper trunk) and `plugin/3.6` (the line that
builds; one line while AGS is a single 3.6 series).

Repo layout:

- `enginehost/bundle-metadata.json` -- the manifest above
- `enginehost-public-key.json` -- at the root, as
  `docs/plugin-catalog.md` requires for building branches; the key is
  derived and certified by `scripts/derive-official-key.py` /
  `scripts/certify-repository-key.py` from the master seed, and its private
  half is set as the repository's `ENGINEHOST_SIGNING_KEY_PEM` secret
- `project/android/` -- the wrapper's Gradle project: the entry Activity
  (the upstream `AGSRuntimeActivity`, patched in-tree to read the host's
  extras and route saves), the runtime's resources at package id 0x80, and
  `jniLibs` filled from the engine job
- `LICENSE.txt` and `Copyright.txt` plus each bundled component's licence,
  carried into the payload
- `.github/workflows/enginehost-android.yml` -- below

Workflow sketch. Job split, naming and guards taken from
`enginehost-android.yml` in `enginehost-godot-plugin@plugin/4.5` and
`android-plugin.yml` in `enginehost-kirikiri-plugin@plugin/stable`:

```yaml
name: Build Enginehost bundle
on:
  push:
    branches: [plugin-core, "plugin/**"]
  pull_request:
  workflow_dispatch:      # publish / channel (stable|testing) / tag inputs, as in both repos
permissions:
  contents: read
# One push supersedes the previous one; a promotion gets a group of its own
# keyed on its run id so the next push cannot kill it (the KiriKiri comment).
concurrency:
  group: ${{ github.workflow }}-${{ github.ref }}-${{ github.event_name == 'workflow_dispatch' && github.run_id || 'push' }}
  cancel-in-progress: true

jobs:
  engine:   # matrix: arm64-v8a + x86_64 (the required two); armeabi-v7a + x86 as the extras
    # CMake + NDK 25.2.9519653, upstream CI's own toolchain: Java 17 temurin,
    # cmake >= 3.22.1, -DANDROID_PLATFORM=android-16 -DANDROID_STL=c++_static,
    # FetchContent cache for the pinned SDL2 / SDL_sound, keyed on CMakeLists.txt
    # + CMake/Fetch*.cmake the way upstream's own CI keys it.
    # Produces libSDL2.so, libengine.so, libags.so per ABI and fails the job
    # if any of the three is absent from the build output.

  android:  # the wrapper and the unsigned payload
    # checkout; setup-java 17; android-actions/setup-android with an empty
    # packages list (the godot job's 2026-09-16 note: the action's default
    # list asks for the withdrawn "tools" package and dies)
    # stage the engine job's artifacts into project/android/app/src/main/jniLibs/<abi>/
    # gradle :app:assembleDebug; keep the unstripped .so beside the payload
    # for symbolizing crashes (the KiriKiri step)
    # pack the unsigned payload: runtime/ags.apk, the classes*.dex (dexFiles
    # computed from that APK, in numeric order), lib/<abi>/*.so for every ABI,
    # LICENSE.txt + Copyright.txt + each component licence
    # assert lib/arm64-v8a/ and lib/x86_64/ are both in the payload
    # tar -cf build/unsigned.tar -C build/unsigned payload bundle-metadata.json; upload

  sign:     # the org's one signing mechanism, pinned to a full Enginehost commit SHA
    # uses: Droidtop/enginehost/.github/workflows/sign-engine-bundle.yml@<full-sha>
    # with tooling-sha the same commit; both shipped repos are currently
    # pinned at 4b9e41cb231b293d08852890aef54b619d4d57ec; the new repo pins
    # whatever is current when it is created.
    # secret: ENGINEHOST_SIGNING_KEY_PEM; the key never shares a runner with the build.

  unstable: # every green push to a plugin/* line: rolling <line>-unstable release,
            # replaced on each build, as in both repos
  publish:  # manual dispatch only: stable / testing channel; the envelope's
            # channel field is set to the chosen one, the tag is derived from
            # the bundle id; afterwards the plugin-published dispatch to
            # droidtop-platforms (PLATFORMS_DISPATCH_TOKEN, a no-op without it)
```

Before the first green build: derive and certify the repository key, set
the `ENGINEHOST_SIGNING_KEY_PEM` secret, and commit
`enginehost-public-key.json` to the building branches -- the "Keys for new
official repositories" steps in `docs/plugin-catalog.md`. The
`plugin-published` dispatch then registers the repository in the plugins
index, and devices add the origin as official at their next refresh with
no new Enginehost build.

## What this step deliberately does not do, and what comes next

- **Next steps, each a separate change:** create
  `Droidtop/enginehost-ags-plugin` and land the wrapper -- entry Activity,
  save routing, the controller-map translation -- against a pinned upstream
  revision; a complete licence audit of the native tree, since the
  `licenses` list is not final until every bundled component's notice has
  been read; and the rig check of a real AGS 3.6 game on arm64 hardware and
  an x86_64 emulator, batched through the rig queue. A green build is not
  proof a game runs, and on-device checking is not available from here.
- **Deliberately untouched in this step:** the engine sandbox
  (`docs/engine-sandbox.md` -- an activity-transport plugin is layer-1-only
  until the host-side rewrite that document scopes), CatSystem2 (the
  sandbox's first milestone), and the host's AGS detection, engine naming
  and controller set, which already exist.
