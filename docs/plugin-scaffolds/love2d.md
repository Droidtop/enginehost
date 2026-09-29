# LÖVE engine plugin: scaffold plan

LÖVE is one of the engines the census's new-engine recommendations
(Droidtop/tracker#26, the batch whose 18 title classes are the coverage
goal) name for this library. This page is the written plan for its
Enginehost plugin. It differs from the AGS plan one directory over
(`docs/plugin-scaffolds/ags.md`) in one thing: the LÖVE plugin already
exists. `Droidtop/enginehost-love2d-plugin` -- a fork of the official
`love2d/love-android` -- has carried the wrapper since 2026-09-25,
publishes a bundle on every green push (tags `love2d-11-v1-unstable`,
`love2d-11-v1-testing` in its release history), and was the first
SDL-family plugin migrated onto the plugin-api transport. So this step
is documentation only: no code, no host change, nothing in the plugin
repository. The plan records the scaffold's decisions with the evidence
they were checked against, and maps what remains. The sandbox
(`docs/engine-sandbox.md`) and CatSystem2 are untouched, as is the host,
which knew LÖVE before any plugin existed: `EngineDetector.kt`'s `love2d`
detection (the `LoveGame` reader in `EngineFormats.kt`, which finds an
unpacked `main.lua` folder, a `.love` zip, or a fused `love.exe` and
reads the version the game's own `conf.lua` sets `t.version` to),
`EngineNames.kt`'s "LÖVE" with context `standard`, and
`ControllerBindings.kt`'s `love` action set.

The evidence behind this plan: `docs/engine-bundle-format.md` and
`docs/plugin-catalog.md` read in full; the plugin API in
`plugin-api/src/main/java/dev/enginehost/api/`; the host's launch path
(`RuntimeActivity.kt`, `PluginRegistry.kt`, `GameRunner.kt`);
`docs/engine-sandbox.md`'s "Single transport", "First conversion: LOVE"
and "Surface handoff" sections; and read-only clones of the shipped
repositories: `Droidtop/enginehost-love2d-plugin` at its `plugin-core`
(`6c0b00c5`) and `plugin/11.5` (`7cae602c`) branches, plus
`Droidtop/enginehost-rpgmaker-mkxp-z-plugin` and
`Droidtop/enginehost-rpgmaker-easyrpg-plugin` at their `plugin-core`
branches as structural references -- the two shipped SDL-family bundles
whose manifest and workflow shapes the LÖVE plugin follows (both carry
`.github/workflows/android-plugin.yml` and the same `runtime.apk`
payload shape).

## (a) The real upstream LÖVE Android port

The engine is `love2d/love` (zlib, `license.txt`). Its Android port is
`love2d/love-android`: an upstream project in its own right, not a
community fork -- it is the port whose APKs love2d.org's own releases
page distributes, with its own wiki and issue tracker -- and it is what
the plugin fork's `main` tracks. The port's `11.5` tree (release commit
`7a32a370`, what the `plugin/11.5` line pins) has this shape:

- `love/` -- an Android **library** module that builds the whole native
  runtime with ndkBuild (`love/src/jni/Android.mk`): `liblove.so` plus
  `libc++_shared.so`, `libmpg123.so` and `libopenal.so` as their own
  shared libraries. The engine itself is the tree's one submodule,
  `love/src/jni/love` (pinned at `6eb8d546`, LÖVE 11.5); SDL2 is
  vendored **in-tree** at `love/src/jni/SDL2`, including its Java glue
  (`org.libsdl.app.SDLActivity`/`SDLSurface`), which is exactly why the
  fork can patch that glue on its line branch.
- `app/` -- a thin launcher APK module (the game picker UI end users
  install), flavors `normal`/`embed` (a packed-in game or one handed
  from outside) crossed with `record`/`noRecord` (microphone).
- Toolchain: JDK 17, NDK `25.2.9519653`, `minSdk 16`,
  `compileSdk`/`targetSdk 34`, `./gradlew assembleNormalRecord`
  (upstream's README and build files; the line's CI builds the same way).

Upstream `main` has since moved on: the native build is CMake through
the new `love2d/megasource` submodule (targets `love_android`, `OpenAL`,
`love`), `minSdk` is 23, `compileSdk`/`targetSdk` 35, NDK `27.3`, and
the whole tree was re-laid-out (`app/src/main/cpp/love`). The line
deliberately stays pinned at the released 11.5 shape; a future line
re-checks every seam against the new layout when a new LÖVE release
lands (see "what comes next").

**Licence.** LÖVE, LÖVE for Android, and the Enginehost wrapper are all
**zlib** (the wrapper's `LICENSE` is the zlib text; the payload carries
it as `LICENSE.enginehost.txt`). mpg123 and OpenAL Soft are
**LGPL-2.1-or-later** and ship as their own shared libraries precisely
so they can be swapped independently -- the bundle's licence table
(`enginehost/LICENSES.md` in the plugin repo) is the audit, and the
corresponding source for everything is the branch it was built from. The
remaining bundled components (SDL2, LuaJIT, FreeType, libogg,
libvorbis, libtheora, libmodplug, Oboe, ...) keep their own licences,
whose notices travel in the payload as `LICENSE.love.txt` and
`LICENSE.love-android.txt` and are named in the manifest's `licenses` --
the godot bundle's `LICENSE-spine-runtimes.txt` precedent for how a
component licence travels.

**State.** The line's history shows its launches were exercised on the
rig (BlueStacks): the two rig-found crashes below in (b) are fixed, the
fixes describe the logcat evidence, and the testing-channel release
exists. That is not evidence a game runs on arm64 hardware, and the
testing channel that `pluginVersion 0.9.0` was declared for is, by the
catalog's own definition, the "runs real games but has not been lived
with" bar -- not stable.

## (b) How the entry point maps onto `EnginePlugin`

The wrapper first shipped as `EngineHostGameActivity` on the
`android-activity` transport (`dev.enginehost.runtime.*` extras read
inside a bundled Activity). The owner's 2026-09-27 **Single transport**
decision -- `docs/engine-bundle-format.md`'s "runtimeTransport:
android-activity is deprecated for official plugins",
`docs/engine-sandbox.md`'s "Single transport" -- moved every official
plugin onto `runtimeTransport: plugin-api`, and LOVE was the first
SDL-family conversion (`docs/engine-sandbox.md`, "First conversion:
LOVE": smallest wrapper of the four SDL engines checked --
`GameActivity` is 645 lines -- and the most already-recorded
groundwork). The entrypoint is now `org.love2d.android.EngineHostGamePlugin`
(`love/src/main/java/`, in the plugin repo), implementing
`dev.enginehost.api.EnginePlugin` (`plugin-api/.../EnginePlugin.java`):
the host's `RuntimeActivity` owns the window and instantiates the
plugin through the bundle's dex loader (`RuntimeActivity.kt:116`),
hands it an `EnginePluginSession` (`RuntimeActivity.kt:88`) and drives
its lifecycle (`callPlugin`, `RuntimeActivity.kt:221`). The mapping,
upstream to host:

| upstream love-android | host equivalent |
| --- | --- |
| the launcher hands the engine a game (intent data URI) | `session.gamePath()` (the game folder) + `session.execFile()` (a `.love`, or a fused `love.exe`, from the detector) |
| `GameActivity.getGamePath()`'s native-called entry | `GameActivity.setEnginehostGame` -- an in-tree seam; when the first plugin-api build left this JNI-called entry unset, the rig caught the NPE (see below) |
| PhysFS mounts the game | unchanged: a folder by path with a trailing separator, a `.love` or a fused exe by file path -- the wrapper computes exactly that string |
| saves under the system application-data folder | `EngineHost.saveDirectory()`, handed down as `ENGINEHOST_LOVE_SAVE_PARENT` (the save patch, below) |
| `SDLActivity`, the Activity Android starts | `RuntimeActivity`; the plugin attaches a real `SDLSurface` into `session.display()`, drives SDL's static lifecycle itself, and the vendored `SDLActivity`/`SDLSurface` carry a scoped line-branch patch routing the genuinely-Activity-shaped calls through `SDLActivity.sHostActivity` = `EngineHost.activity()` |
| libraries from the app package | `SDL.loadLibrary` for `c++_shared`, `mpg123`, `openal`, `love` -- resolved from the bundle's `lib/<abi>` on the dex loader's native path |
| the pad, read through SDL's own enumeration | `onControllerEvent`: every event arrives already resolved to LÖVE's own `love.gamepad` name (`love_a`, `love_leftx`, ...), which the wrapper turns into the exact SDL key or axis call |
| the game ends | `sOnGameEnded` -> `EngineHost.finish()`; unlike the activity transport it replaces, the plugin holds the host object, so `EngineHost.fail(message)` also covers a late engine failure |

The seams this adds up to, as the line's own history records them
(`b58bb121`, "Seams for Enginehost: game path, save folder, both ABIs,
resource id"; then the transport migration):

- **Game path.** `GameActivity.setEnginehostGame` (in-tree Java, since
  love-android owns `GameActivity`). The first plugin-api build set only
  SDL's argument hooks and the rig found the native side calls
  `getGamePath()` for real, whose `checkLovegameFolder()` fallback NPE'd
  on the unattached instance (`3e731a4e`, rig item dq-actsandbox-02; the
  fix cites the logcat line).
- **mSingleton.** `SDLActivity.mSingleton` must be a real `GameActivity`
  instance, never attached: LOVE's own glue casts it
  `(GameActivity)`, and a bare `SDLActivity` there threw
  `ClassCastException` on the rig (`af61a816`, dq-actsandbox-01). The
  genuinely-Activity-shaped calls (`getWindow()`,
  `isInMultiWindowMode()`, `getRequestedOrientation()`, orientation,
  minimize, `openURL`) go through `SDLActivity.sHostActivity`, set to
  `EngineHost.activity()` -- the host-service addition this migration
  itself motivated (`EngineHost.java`: nullable, deliberately null
  under isolation). `97f48fc1` is the vendored SDL glue patch.
- **Saves.** The org's save policy, applied to LÖVE's own logic: a game
  saves under the system's application-data folder --
  `%APPDATA%/LOVE/<identity>` for a `.love`/folder game,
  `%APPDATA%/<identity>` for a fused one. The engine's
  `Filesystem::setIdentity` reads `ENGINEHOST_LOVE_SAVE_PARENT` (the
  wrapper sets it to the save directory, or its `/LOVE` child for
  unfused games) and uses it as the parent the identity goes under.
  LÖVE is the `love/src/jni/love` submodule, which the fork does not
  own, so this seam is `enginehost/patches/love/0001-enginehost-save-parent.patch`,
  applied to the pinned revision by CI. Where a game saves inside that
  folder stays the game's own choice.
- **Controller.** The host's resolved map is LÖVE's own
  `GamepadButton`/`GamepadAxis` vocabulary (`ControllerBindings.kt:222-244`,
  the documented `love_a`/`love_leftx` table in
  `docs/engine-bundle-format.md`'s controller section). Under this
  transport `RuntimeActivity` normalizes all input, so the wrapper
  reports each action as the SDL button or axis LÖVE's game code
  already reads -- one mapping UI for every engine, no second
  bypass-vs-map concept; the host menu shortcut (Select+Start out of
  the box) is reserved as for every plugin.

**Not yet isolatable.** This is the non-isolated half of the Single
transport migration, deliberately first: it proves the transport change
before the Surface-handoff question is settled. `EnginePlugin`'s
`usesSurface()`/`attachIsolatedSurface()` and the host-side
`setGameSurface` plumbing exist (the sandbox doc's "Surface handoff"
sections); what gates `isolatable: true` is the still-open platform
question the sandbox doc records with rig evidence -- on emulator-5560
SELinux denies `isolated_app` use of the graphics allocator fd, the
BlueStacks half is pending, and the fallback is the
software-readback path. The PhysFS broker seam (wiring
`EngineFileBroker` into PhysFS's own `PHYSFS_Io` the way
CatSystem2/CMVS's file layers were done) is the follow-on file work.
Until then the per-launch "Run unsandboxed?" prompt shows exactly as
before the migration.

## (c) Bundle-manifest fields, and the ABI question

The author declares `enginehost/bundle-metadata.json`; the pinned
`sign-engine-bundle.yml` job adds `formatVersion`, `assetName`,
`signing`, `payloadSha256` and `files`, and numbers the build
(`pluginVersion` `X.Y.Z-N`). The line's manifest as it ships:

```json
{
  "bundleId": "dev.enginehost.love2d.11.v1",
  "engine": "love2d",
  "pluginVersion": "0.9.0",
  "apiVersion": 1,
  "entrypoint": "org.love2d.android.EngineHostGamePlugin",
  "runtimeTransport": "plugin-api",
  "origin": "https://github.com/droidtop/enginehost-love2d-plugin",
  "dexFiles": ["classes.dex"],
  "resourceApks": ["runtime.apk"],
  "capabilities": [
    {
      "id": "love2d-11-standard-v1",
      "engineContext": "standard",
      "runtimeVersion": "11.5",
      "supportedSeries": ["11"]
    }
  ],
  "source": {
    "upstream": "https://github.com/love2d/love-android",
    "revision": "plugin/11.5"
  },
  "licenses": [
    "zlib (LÖVE, LÖVE for Android, the Enginehost wrapper)",
    "LGPL-2.1-or-later (mpg123, OpenAL Soft; shared libraries)",
    "the other bundled notices in LICENSE.love-android.txt"
  ],
  "notes": "LÖVE 11.5, the official LÖVE for Android runtime. Runs 11.x games (11.0 to 11.5 keep one API); games written for 0.10 or earlier need their own line."
}
```

- `engineContext` is `"standard"`: `EngineNames.kt` treats `love2d` the
  way it treats `ags` -- context `standard` names the bare family.
- `supportedSeries: ["11"]` matches the detector's fact, not a guess:
  the game's `conf.lua` states its version and 11.0 through 11.5 keep
  one API (upstream's own compatibility rule), so the series admits the
  whole line while the capability's exact `runtimeVersion` records what
  is bundled.
- `dexFiles` is computed by CI from the APK it packs, in numeric order
  (`classes2` before `classes10`), because a multidex payload that
  under-declares its dex loses the rest of the wrapper at launch.
- `resourceApks` is `runtime.apk`, the built app-module APK, carrying
  the wrapper class, the runtime's resources and the native libraries.
  Its resource table compiles at package id `0x80`
  (`androidResources { additionalParameters += ["--package-id", "0x80",
  "--allow-reserved-package-id"] }`), the format's rule for a bundle's
  resources; the host reads the id back out of `resources.arsc` and
  refuses a collision.
- `declaredOptions`: none. The wrapper reads no per-game knob Enginehost
  could offer a control for; an option is declared only when the
  wrapper actually implements it.
- `hostType` needs no value: the host detects `android` from the dex
  and the entry class.

**ABIs: both required ones exist upstream.** `arm64-v8a` is an upstream
release ABI (upstream's own changelog: 11.2 "Added support for ARM64
devices"; it is in the release `abiFilters` both at the pinned 11.5
tree and on current `main`). `x86_64` is a release ABI on current
upstream `main` (`abiFilters 'armeabi-v7a', 'arm64-v8a', 'x86_64'`); at
the pinned 11.5 tree it was a **debug**-only ABI, and the line's seam
commit promoted it into the release set, which the line's published
builds prove out. `x86` is not built at either revision. The line
therefore builds exactly the two required ABIs -- the fork's
`love/build.gradle` narrows upstream's `abiFilters` to `'arm64-v8a',
'x86_64'` -- and the packing step asserts both `lib/arm64-v8a/liblove.so`
and `lib/x86_64/liblove.so` are in the payload before it ships, because
the rule is per-bundle, not per-upstream. Upstream's third ABI,
`armeabi-v7a`, is not carried; the format's minimum is satisfied and
the host's install-time check (refusing a bundle whose every ABI the
device runs none of) is unaffected either way.

## (d) The first CI workflow, as it shipped

The plugin repo follows the org's branch model: `main` tracks upstream
(kept fast-forwardable), `plugin-core` is the wrapper changeset alone,
and `plugin/11.5` is the upstream `11.5` release with `plugin-core`
merged in and the line's seams applied. Only `plugin/**` branches
build, because `plugin-core` alone has no LÖVE tree to build. The
workflow (`.github/workflows/android-plugin.yml`, same name and shape
as mkxp-z's and EasyRPG's) is:

```yaml
name: Build enginehost LÖVE plugin
on:
  push: { branches: ["plugin/**"] }
  pull_request:
  workflow_dispatch:      # publish / channel (stable|testing) / tag inputs
permissions: { contents: read }
concurrency: { group: ${{ github.workflow }}-${{ github.ref }}, cancel-in-progress: true }

jobs:
  android:   # only on refs/heads/plugin/*
    # checkout (submodules: recursive; persist-credentials: false)
    # apply enginehost/patches/love/*.patch to the love submodule
    # ./gradlew :app:assembleNormalNoRecordRelease
    #   (normal: runs a game it is handed; noRecord: no microphone permission)
    # pack payload: runtime.apk; classes*.dex (dexFiles recomputed in
    #   numeric order into the manifest); lib/<abi>/*.so; the licence
    #   notices; assert liblove.so exists for BOTH arm64-v8a and x86_64
    # tar payload + bundle-metadata.json; upload the unsigned artifact.
    # No signing key and no write token in this job (the supply-chain
    # rule, docs/security/2026-09-24-ci-supply-chain.md).

  sign:      # the org's one signing mechanism
    # uses: Droidtop/enginehost/.github/workflows/sign-engine-bundle.yml@<full sha>
    # with tooling-sha the same commit; secrets: ENGINEHOST_SIGNING_KEY_PEM
    # the key never shares a runner with the build

  unstable:  # every green push to a line: rolling <line>-unstable release,
            # replaced on each build (tags love2d-11-v1-unstable exist)

  publish:  # manual dispatch only: stable / testing channel; the envelope's
            # channel field is set to the chosen one, the tag derived from
            # the bundle id (love2d-11-v1-testing is the current one);
            # afterwards the plugin-published dispatch to droidtop-platforms
            # (PLATFORMS_DISPATCH_TOKEN, a no-op without it)
```

The key steps that preceded the first green build are the catalog's
"Keys for new official repositories" steps, and they are done:
`enginehost-public-key.json` is committed at the repository root on
`plugin-core` and the lines, derived and certified from the master
seed, with the private half in the `ENGINEHOST_SIGNING_KEY_PEM`
secret. The `plugin-published` dispatch registered the repository in
the plugins index, so devices add the origin as official at their next
refresh with no new Enginehost build.

## What this step deliberately does not do, and what comes next

- **This change adds this file only.** No host change, no
  plugin-repo change, no workflow change.
- **Deliberately untouched:** the engine sandbox
  (`docs/engine-sandbox.md` -- its own "Surface handoff" sections hold
  the isolation state, and this plan does not rewrite them), CatSystem2
  (the sandbox's first milestone), and the host's LÖVE detection,
  naming and controller set, which already exist.
- **Next steps, each a separate change:** the BlueStacks half of the
  surface-handoff rig check, then the PhysFS broker seam, then
  `isolatable: true` with its own rig pass -- the migration's recorded
  goal and the "official plugin" bar; an arm64-hardware rig check of a
  real 11.x game and actual lived-with use before any stable promotion;
  a separate line for games written for LÖVE 0.10 or earlier, which the
  bundle's own `notes` names as needing one; and, when a new LÖVE
  release lands, a new line pinned against upstream's current
  CMake/megasource tree, re-checking every seam above against it. A
  green build is not proof that a game runs, and on-device checking is
  not available from here.
