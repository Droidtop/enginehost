# XNA / FNA / MonoGame engine plugin: scoping

Step 1 of the XNA-family effort (the census's new-engine recommendations,
the family's batch of 18 titles, Droidtop/tracker#23): this page is the
written scope. No repository, no code, and no host change in this step.
The sandbox (`docs/engine-sandbox.md`), CatSystem2, and the detection
code are deliberately untouched. Unlike AGS -- where the host already
detected, named and bound the engine -- this family is one Enginehost
names but cannot run: `EngineNames.kt:27` maps `xna-fna-monogame` to
"XNA / FNA / MonoGame" under the engines "the registry recognises that
nothing here runs", so today a detected folder comes out `hosted = false`
(`EngineDetector.kt:88,129`) and Play answers with the cannot-run
sentence (`DetectedConfig.kt:55`). Making the family runnable therefore
touches the platform registry row as well as a new plugin repo.

The evidence behind this scope: `docs/engine-bundle-format.md` and
`docs/plugin-catalog.md` read in full; `docs/plugin-scaffolds/ags.md`
(the AGS effort's step 1) as the model for shape and depth; the plugin
API in `plugin-api/src/main/java/dev/enginehost/api/`; the host's launch
path (`GameRunner.kt`, `EnginehostComponentFactory.kt`,
`RuntimeActivity.kt`, `PluginCapabilities.kt`, `ControllerBindings.kt`);
and the upstream sources read over GitHub on 2026-09-29 at their
default branches: `FNA-XNA/FNA` (master), `FNA-NET/FNA` (main, with its
`FNA.NET.csproj` and submodule set), `FNA-NET/FNA3D` and `FNA-NET/FAudio`
(licence texts), `MonoGame/MonoGame` (develop, tags v3.8.5 and v3.8.5.1,
its `MonoGame.Framework.Android.csproj`, `Platform/Android/`,
`.github/workflows/main.yml`), `MonoGame/MonoGame.Library.OpenAL` (its
pinned `kcat/openal-soft` submodule), `kcat/openal-soft` (COPYING), and
`0x0ade/XnaToFna`. The two shipped plugin repos are the structural
precedents, as they were for AGS: `Droidtop/enginehost-godot-plugin`
(`plugin/4.5`) and `Droidtop/enginehost-kirikiri-plugin`
(`plugin/stable`).

## (a) The real upstream Android story -- there are two, and they are not equal

**FNA proper has no Android target.** The upstream project
(`FNA-XNA/FNA`, 3.0k stars) describes itself as "an XNA4 reimplementation
that focuses solely on developing a fully accurate XNA4 runtime **for
the desktop**" (its README), and its platform topics are Windows, Linux,
macOS, SteamOS. Its parts are SDL2 (via SDL2-CS bindings), FNA3D as the
renderer and FAudio as the sound engine, each built per desktop OS.

**The FNA.NET fork is the FNA Android path, and it is arm64-only.**
`FNA-NET/FNA` ("FNA.NET", NuGet package `FNA.NET`, version 2.2.11.2602
in its csproj) is a one-maintainer "opinionated fork of FNA" that
packages FNA for NuGet and adds mobile targets. Its own README's
supported-platforms table gives Android exactly one row: **Arm64**,
renderer OpenGL, "Vulkan (many devices)" with the newer SDL3 GPU. And
it comes with a note that matters to this org directly: "Emulator is
not supported as SDL3 GPU has no support for it." The fork's
`FNA.NET.csproj` targets `net9.0-android`
(`SupportedOSPlatformVersion` 21), pulls its prebuilt native
libraries from `FNA.NET.NativeAssets` 2.1.2.2602 and its SDL3 Android
Java bindings from `SDLDroidSharp-JBindings` 3.4.0; its submodules
are SDL2-CS, SDL3-CS, FNA3D, FAudio, Theorafile, dav1dfile, plus
NVorbis 0.10.5 for Ogg.

**MonoGame has the official, maintained Android backend.**
`MonoGame/MonoGame` (14.4k stars; current line tags v3.8.5 and
v3.8.5.1) is the foundation-run XNA reimplementation, and Android is a
first-class target in its README ("Android 6 (API 23) and up
(OpenGL)"). The target is `MonoGame.Framework.Android.csproj`:
`TargetFramework net8.0-android`, `SupportedOSPlatformVersion 23`,
`DefineConstants ANDROID;GLES`, `MGOpenGL=GLES`, `IsAotCompatible`,
PackageId `MonoGame.Framework.Android`, importing
`Platform/OpenGL.targets` and `Platform/OpenAL.targets`. Upstream CI
(`.github/workflows/main.yml`, job `build`) builds the Android target
on every push: .NET SDK 9.0.314, `dotnet workload install android` on
the Windows and Linux x64 runners, MonoGame's own
`install-android-dependencies` action, then
`dotnet run --project build/Build.csproj -- --target=Default`, which
packs and (on push) deploys the NuGet packages. The upstream is not an
Android APK, though: MonoGame ships as a **framework that** a game's own
project compiles against. There is no upstream "player" that opens a
folder the way AGS Player does -- that role is the plugin's.

**Native libraries.** A MonoGame-based wrapper APK carries three sets
of native code, all enumerated into the bundle's `files` list by the
packing step rather than by this page:

- The .NET for Android runtime's own set: the Mono runtime
  (`libmonosgen-2.0.so`), the `libmono-android` bridge pair and the
  profiler libraries the workload packs into every app -- this family is
  the host's first managed-code engine, and the Mono runtime is what
  makes the game's own IL loadable at all.
- OpenAL for sound: the Android csproj takes it from
  `MonoGame.Library.OpenAL` 1.24.3.2, MonoGame's own "OpenAL Build
  System" repository, whose submodule pins `kcat/openal-soft` at
  `9335813`. Graphics needs no native library: GLES and EGL are bound
  from managed code (`Platform/Graphics/OpenGL.Android.cs`).
- Whatever the wrapper itself adds (none expected in a first bundle).

The FNA.NET path instead carries SDL3, FNA3D, FAudio, Theorafile and
dav1dfile prebuilts from `FNA.NET.NativeAssets`, for arm64-v8a only.

**The games themselves are managed IL, and that decides the plugin's
shape.** The census's 18 titles are Windows builds: the game's own
managed assembly (a `.exe` of pure IL), `Content/` XNB assets, and one
framework's DLLs beside it -- stock XNA 4.0's `Microsoft.Xna.Framework.*`,
`FNA.dll`, or `MonoGame.Framework.dll`. Nothing in that folder is
Android-native. A generic bundle therefore has to do three jobs at
launch: supply the managed runtime, supply an Android framework that
implements the `Microsoft.Xna.Framework` API, and redirect the game
assembly's framework references onto the bundled one. All three are
established practice in this ecosystem:

- Mono's JIT works on Android (unlike iOS), so the game's IL can be
  loaded from the game folder at launch rather than baked into the
  wrapper APK -- the same host-carries-runtime, game-carries-content
  split as the Ren'Py and EasyRPG bundles.
- MonoGame's assembly is `MonoGame.Framework` with root namespace
  `Microsoft.Xna.Framework`, so MonoGame-compiled games bind without any
  rewriting.
- XNA- and FNA-compiled games need their references redirected.
  `0x0ade/XnaToFna` ("Relink games / tools using the XNA Framework 4.0
  to use FNA instead", zlib-licensed, Mono.Cecil-based) is the
  desktop precedent for rewriting compiled games without sources; on
  Android the same redirect can be done at launch with an
  `AssemblyResolve` hook that answers the stock XNA and FNA assembly
  names with the bundled framework, or once with Cecil. Which of the
  two is an implementation-time decision; the risk it carries is
  FNA-specific extensions (FNAPlatform and friends), which the census
  titles have to be measured against one by one.

## (b) How the entry point maps onto this org's transports

The upstream entry point is `AndroidGameActivity`
(`MonoGame.Framework/Platform/Android/AndroidGameActivity.cs`): a plain
`Activity` that sets `Game.Activity = this`, registers screen
receivers, and expects the game object to be created beside it; the
window is `AndroidGameWindow` over `MonoGameAndroidGameView`, a
`GLSurfaceView`, and `AndroidGamePlatform` asserts `Game.Activity` is
set and runs the loop asynchronously (`GameRunBehavior.Asynchronous`).

This org has two transports, named in `PluginRegistry.kt:228-229`:
`"plugin-api"` -- the host's `RuntimeActivity` instantiates a class
implementing `dev.enginehost.api.EnginePlugin` -- and
`"android-activity"` -- the bundle's own Activity: `GameRunner.kt:159`
routes the launch through the host-declared `BundledActivityProxy`, and
`EnginehostComponentFactory.instantiateActivity` (lines 20-79) requires
`runtimeTransport == "android-activity"`, re-verifies the installed
bundle, attaches the `resourceApks`, and instantiates the `entrypoint`
class through the plugin's dex loader with the bundle's `lib/<abi>` on
its native library path (lines 63-69). The KiriKiri plugin is the
shipped precedent for this shape, and the AGS plan
(`docs/plugin-scaffolds/ags.md` (b)) is the full argument.

**Recommendation: `android-activity`.** MonoGame's frontend is an
Activity that owns the window, the GL surface and the input dispatch,
and the transport exists for exactly that. The mapping, upstream to
host:

| upstream | host equivalent |
| --- | --- |
| `TitleContainer`/content root (the Android target defaults to APK assets, `TitleContainer.Android.cs`) | `dev.enginehost.runtime.PATH`: the wrapper seeds the content root with the game folder, so XNB loading reads the chosen folder instead of an APK the bundle does not have |
| the game's save writes (its own `System.IO` paths, the XNA `Storage` API where used) | `dev.enginehost.runtime.SAVE_PATH`: the engine's save logic is unchanged, only its system locations are made to mean the folder the person chose. Note MonoGame's Android backend implements no XNA `Storage` of its own, so per-title save behaviour is a rig-check item, not a design one |
| `Game.Activity` | the bundle's entry Activity, instantiated by the host's component factory |
| native libraries from the app package | `dev.enginehost.runtime.BUNDLE_ROOT` (`EnginehostComponentFactory.kt:60,86`); the dex loader already puts the bundle's `lib/<abi>` on the native path, so the Mono runtime and OpenAL load from the signed payload |
| MonoGame's own input (`Platform/Input/GamePad.Android.cs`, touch) | consumed by the engine itself: the capability declares `controllerInput: NATIVE` (`PluginCapabilities.kt:31,49`), the honest default of every shipped bundle, so the host must let Android's dispatch reach the engine's view; no `ControllerBindings` action set in a first bundle. The host menu shortcut stays reserved in both transports |
| (nothing upstream) | `dev.enginehost.runtime.ENGINE_CONTEXT` etc. as every bundle receives them |

The one genuinely new problem this family sets -- and the first thing
the wrapper effort must burn down -- is **bootstrapping the Mono runtime
inside Enginehost's process**. A normal .NET for Android app initialises
Mono from its own `Application` class and a `MonoRuntimeProvider`
content provider in its manifest; a bundle gets neither, because the
process is Enginehost's (`EnginehostApplication`), the manifest is
Enginehost's, and the bundle's dex is loaded by the host's
`PluginDexLoader` on demand. So the entrypoint cannot be the
C#-generated activity stub directly: the entrypoint is a thin plain-Java
Activity in the bundle's dex that reads the host's extras, starts the
Mono runtime against the bundle's `lib/<abi>` and managed assemblies,
then hands over to managed code that constructs the game and its
`AndroidGameWindow` against itself, preserving the
`AndroidGameActivity` contract upstream's platform layer asserts on.
This bootstrap shim is wrapper work in the plugin repo, not host work;
nothing about it widens the sandbox (the engine still runs in the
`:runtime` process under the app's UID, and an activity-transport
plugin stays layer 1 per `docs/engine-sandbox.md`).

**The alternative, for the record: `plugin-api`.** `MonoGameAndroidGameView`
is a `GLSurfaceView`, and a `ViewGroup display()` from
`EnginePluginSession` could host it; that is the shape layer 2 of the
sandbox will want. But `AndroidGamePlatform` asserts a real
`Game.Activity`, and the Mono bootstrap problem does not get smaller in
this shape -- it gets less testable. As with AGS, a first bundle takes
the android-activity transport and keeps the entry seam thin enough that
the plugin-api shape can reuse everything under it.

## (c) Bundle-manifest fields, the ABI question, and the licences

The author declares `enginehost/bundle-metadata.json`;
`scripts/build-engine-bundle.py` adds `formatVersion`, `assetName`,
`signing`, `payloadSha256` and `files`, and numbers the third
`pluginVersion` component from the CI run. First bundle:

```json
{
  "bundleId": "dev.enginehost.monogame.v38.v1",
  "engine": "xna-fna-monogame",
  "pluginVersion": "0.1",
  "apiVersion": 1,
  "entrypoint": "<the plain-Java bootstrap Activity>",
  "runtimeTransport": "android-activity",
  "origin": "https://github.com/droidtop/enginehost-monogame-plugin",
  "dexFiles": ["classes.dex", "..."],
  "capabilities": [
    {
      "id": "monogame-3.8-any-v1",
      "engineContext": "default",
      "runtimeVersion": "3.8.5",
      "acceptsAnyEngineVersion": true,
      "controllerInput": "NATIVE"
    }
  ],
  "source": {
    "upstream": "https://github.com/MonoGame/MonoGame",
    "revision": "<pinned commit>"
  },
  "licenses": ["Microsoft Public License", "...one entry per bundled component, audited..."],
  "notes": "<only if something is not true that a reader would assume>"
}
```

- `engine` is `xna-fna-monogame`, the id the registry row and
  `EngineNames.kt` already use -- not `monogame`: the bundle serves the
  family, and the census's titles span stock XNA, FNA and MonoGame
  builds.
- `engineContext` is `"default"` (`DEFAULT_ENGINE_CONTEXT`,
  `PluginCapabilities.kt:5`), which `EngineNames.line()` renders as the
  bare family name for every engine; no `EngineNames` change is needed
  while one runtime serves the whole family. If a later bundle splits
  contexts (a true FNA runtime line alongside MonoGame), that change
  comes with the split.
- `acceptsAnyEngineVersion: true` is the honest statement: the bundled
  framework **replaces** the game's own framework DLL, so the version
  the folder carries (XNA 4.0 refresh, FNA's calendar versions,
  MonoGame 3.x) gates nothing; `runtimeVersion` records what is actually
  bundled. A launch therefore needs only the detected engine, and the
  family needs no version enrichment in the host to run: the KiriKiri
  precedent already launches on engine alone. The exec file is also
  unnecessary -- the wrapper finds the managed assembly in the folder
  (typically the one `.exe` at the root); an
  `EngineFormats.kt`-style reader for the framework DLL's version and
  the game assembly's name is a later nicety, not a launch requirement.
- `dexFiles` is computed from the wrapper APK in numeric order, the
  KiriKiri lesson about multidex under-declaration. `resourceApks` only
  if the wrapper actually carries Android resources; MonoGame games read
  files, not resources, so a first bundle may carry none.
- Detection side, in this repo and the platform repo, when the first
  bundle is real: the `xna-fna-monogame` row in `engines-database.json`
  gains its `enginehost` mapping (that file lives in droidtop-platforms
  -- `vendor/` here is a pinned submodule and is never edited; the
  bundled snapshot follows at the next pin bump), and only then does a
  detected folder stop answering "nothing here runs it".

**ABIs: the MonoGame path clears the rule; the FNA.NET path does not.**
The org rule is arm64-v8a AND x86_64 per bundle
(`docs/engine-bundle-format.md:111-116`), and both rigs (BlueStacks,
emulator-5560) are x86_64, so a bundle without x86_64 cannot even be
rig-checked. .NET for Android builds all four ABIs
(armeabi-v7a, arm64-v8a, x86, x86_64) for a net8.0-android project, and
upstream CI exercises the Android build on every push. FNA.NET is
arm64-only on Android today and says the emulator is unsupported.

Decision recorded on this page: **MonoGame is the Android runtime for
the family.** FNA.NET is the accuracy-focused alternative -- for titles
whose IL leans on FNA-specific behaviour it may prove necessary -- and
it is re-evaluated the day it ships x86_64 Android prebuilts; until
then it cannot satisfy the ABI rule, cannot be rig-checked, and rests
on a single maintainer rather than the foundation. The FNA-specific
titles, if the census's 18 contain them, are served by the reference
redirect of (a) first and measured on the rig, not by adopting
FNA.NET's arm64-only line.

**Licences -- the census summary needs correcting, from the files.**
The census line sums the family up as "MIT/zlib"; the licence texts say
more precisely:

- **MonoGame: MS-PL** (`LICENSE.txt`: "Microsoft Public License (Ms-PL)",
  copyright the MonoGame Foundation), with portions under the MIT
  licence from the Mono.Xna team, named in the same file. MS-PL is the
  OSI-approved MIT-family licence, so the summary's intent holds.
- **FNA: MS-PL** too (the fork carries FNA's own licence file unchanged
  in `licenses/`), plus `lzxdecoder.LICENSE` (dual MS-PL/LGPL) and
  `monoxna.LICENSE` (MIT) -- the same Mono.Xna ancestry.
- **FNA3D and FAudio: zlib**, verified from their licence texts (Ethan
  Lee's zlib notices); SDL2 and SDL3, both stacks' window layer, are
  zlib as well.
- **The one copyleft component: OpenAL.** MonoGame's Android audio is
  built from `kcat/openal-soft`, whose COPYING is the GNU Library
  General Public License v2 text -- LGPL, not MIT, not zlib. It travels
  in the payload like every component licence
  (`docs/engine-bundle-format.md:252-256`, the godot Spine precedent):
  the notice and licence text in the payload, the component named in
  `licenses`, the openal-soft revision pinned in `source`, and the
  plugin repository carrying the corresponding source offer the LGPL
  asks for. This is the first LGPL component in a plugin payload, and
  it is why the bundle's licence list is built from the files read in
  full, not from the census's one-line summary.
- Unverified so far, to be read in the licence audit step: which ABIs
  `MonoGame.Library.OpenAL` actually ships prebuilts for (the Android
  rule holds only if all four are there or the plugin builds them from
  its pinned submodule), and the .NET for Android runtime's own
  notices. The audit is not done until every bundled component's notice
  has been read -- the same rule the AGS plan set for its native tree.
  (NVorbis, checked while writing this page, is not on the Android
  target at all: `Platform/OpenAL.targets` pulls it into DesktopGL
  only, so it is not in the payload.)

## (d) First CI workflow, modeled on the existing plugin repos' own structure

The plugin repo is `Droidtop/enginehost-monogame-plugin`, created when
this plan executes: a fork of `MonoGame/MonoGame` with the enginehost
wrapper layered on, per the branch model -- `plugin-core` for the
wrapper trunk (bundle metadata, the bootstrap Activity, the managed
launcher, docs), `plugin/3.8` the pinned upstream line with plugin-core
merged in, engine-source fixes on the line or upstream. The engine
source is large but it is the branch's content, as godot's is; no
submodule fetch is needed for Android -- the wrapper can reference the
published `MonoGame.Framework.Android` NuGet pinned by version, with
the fork kept for engine-side patches (save routing, content root, and
whatever the first rig checks find).

Workflow sketch. Job split, naming and guards taken from
`enginehost-android.yml` in `enginehost-godot-plugin@plugin/4.5` and
`android-plugin.yml` in `enginehost-kirikiri-plugin@plugin/stable`, as
the AGS plan did; the engine job is a dotnet build where theirs were
CMake:

```yaml
name: Build Enginehost bundle
on:
  push:
    branches: [plugin-core, "plugin/**"]
  pull_request:
  workflow_dispatch:      # publish / channel (stable|testing) / tag inputs, as in both repos
permissions:
  contents: read
concurrency:
  group: ${{ github.workflow }}-${{ github.ref }}-${{ github.event_name == 'workflow_dispatch' && github.run_id || 'push' }}
  cancel-in-progress: true

jobs:
  android:  # the wrapper and the unsigned payload, ONE dotnet job, no native matrix
    # checkout; setup-dotnet 9 + `dotnet workload install android`
    # (upstream's own CI pins 9.0.314); restore with the pinned
    # MonoGame.Framework.Android and MonoGame.Library.OpenAL versions
    # dotnet build/publish the wrapper for all four ABIs
    # (RuntimeIdentifiers android-arm;android-arm64;android-x86;android-x86_64)
    # from the published APK take classes*.dex in numeric order (dexFiles)
    # and lib/<abi>/*.so for every ABI; keep the unstripped .so beside the
    # payload for symbolizing crashes (the KiriKiri step)
    # pack the unsigned payload: the wrapper's managed assemblies, the dex,
    # lib/<abi>/*.so (Mono runtime, MonoDroid bridge, OpenAL), LICENSE.txt
    # + each component licence; assert lib/arm64-v8a/ AND lib/x86_64/ made it in
    # tar -cf build/unsigned.tar -C build/unsigned payload bundle-metadata.json; upload

  sign:     # the org's one signing mechanism, pinned to a full Enginehost commit SHA
            # uses: Droidtop/enginehost/.github/workflows/sign-engine-bundle.yml@<full-sha>
            # secret: ENGINEHOST_SIGNING_KEY_PEM; the key never shares a runner with the build.

  unstable: # every green push to a plugin/* line: rolling <line>-unstable release
  publish:  # manual dispatch only: stable / testing channel; the plugin-published
            # dispatch to droidtop-platforms (PLATFORMS_DISPATCH_TOKEN)
```

Before the first green build: derive and certify the repository key,
set the `ENGINEHOST_SIGNING_KEY_PEM` secret, and commit
`enginehost-public-key.json` to the building branches -- the "Keys for
new official repositories" steps in `docs/plugin-catalog.md`. Also
before the first green build: the registry row mapping in
droidtop-platforms (c), or the bundle installs with nothing to say to a
detected folder.

## What this step deliberately does not do, and what comes next

- **Next steps, each a separate change:** create
  `Droidtop/enginehost-monogame-plugin` and spike the wrapper -- the
  Mono bootstrap inside a borrowed process is the risk to retire first,
  before any polish, because everything else in the wrapper depends on
  it working; the registry row's `enginehost` mapping in
  droidtop-platforms; the complete licence audit of everything the
  wrapper APK actually packs (the LGPL OpenAL obligations above all);
  and the rig check of real census titles -- arm64 hardware and an
  x86_64 emulator, batched through the rig queue, including the
  per-title save behaviour and the FNA-reference-redirect risk list.
  A green build is not proof a game runs, and on-device checking is not
  available from here.
- **Deliberately untouched in this step:** the engine sandbox
  (`docs/engine-sandbox.md` -- an activity-transport plugin is layer-1
  only until the host-side rewrite that document scopes), CatSystem2,
  the host's detection, naming and controller code, and `vendor/`
  (the platform submodule is pinned, never edited here).
