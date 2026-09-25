# xsystem4 Plugin Scaffold

## Overview

This scaffold documents the planned xsystem4 engine plugin for Enginehost, an AliceSoft System 4 engine implementation for unix-like operating systems. The xsystem4 project (github.com/kichikuou/xsystem4) currently targets Linux, macOS, and Windows desktop platforms, with Android support requiring significant porting work.

The plugin will bring System 4 games like Rance, Dungeons and Daughters, and their variants to Android devices through Enginehost's plugin architecture.

## Upstream xsystem4 Project

### Real Project Status
- **Repository**: https://github.com/kichikuou/xsystem4
- **Description**: An implementation of AliceSoft's System 4 game engine for unix-like operating systems
- **Current Platforms**: Linux, macOS, Windows (via MSYS2)
- **Architecture**: C with stack-based VM, graphics/sound subsystems, and Buriko Program execution
- **Build System**: Meson build system with SDL2 backend

### Key Technical Details
- **Engine Family**: System 4 (AliceSoft)
- **Execution Model**: Stack-based Virtual Machine executing Buriko Programs (`._bp` files)
- **Game Format**: Games require extracted assets via `unarc` tool (not direct asset loading)
- **Supported Games**: 40+ AliceSoft games including Rance series, Sengoku Rance, various "From Alice" titles

### Android Porting Requirements
The upstream xsystem4 currently lacks Android support. Porting would require:

1. **SDL2 Android Porting**: Adapt the SDL2 backend to Android (Graphics, input, audio)
2. **Asset Loading**: Implement direct loading from APK/file system instead of extracted archives
3. **Native Library**: Build `libxsystem4.so` for arm64-v8a and x86_64
4. **Configuration**: Support for `System40.ini` configuration files
5. **Controller Support**: Map Android controller input to System 4's input model

## Enginehost Mapping

### Plugin Architecture
The xsystem4 plugin will follow the existing `EnginePlugin` interface in `plugin-api/src/main/java/dev/enginehost/api/EnginePlugin.java:1`:

```java
public interface EnginePlugin {
    void onCreate(EnginePluginSession session) throws Exception;
    default boolean onControllerEvent(EngineControllerEvent event) throws Exception { return false; }
    default void onStart() throws Exception {}
    default void onResume() throws Exception {}
    default void onPause() throws Exception {}
    default void onStop() throws Exception {}
    default void onDestroy() throws Exception {}
}
```

### Android Transport Model (Based on Buriko Plugin)

Following the pattern established by the Buriko/Ethornell plugin, xsystem4 will:

1. **Native Library Loading**: Load `libxsystem4.so` in the runtime process (similar to how Buriko loads `libopenbgi.so`)
2. **Game Directory Access**: Provide file system access to the game directory (Buriko plugin uses `EngineFileSystem`)
3. **Controller Mapping**: Translate Android controller events to System 4's input model
4. **Engine Lifecycle**: Manage the xsystem4 VM lifecycle through Enginehost callbacks

### Key Implementation Components

#### 1. JNI Bridge (similar to Buriko plugin)
```c
// libxsystem4.so exports:
void xsystem4_initialize(const char* game_path);
void xsystem4_run_frame();
void xsystem4_shutdown();
```

#### 2. Controller Translation
Buriko plugin translates shared fallback input model to engine-specific actions. For xsystem4:
- **Source**: Android motion/key events via `EngineControllerEvent`
- **Target**: System 4's input model (keyboard, mouse, gamepad)
- **Mapping**: Convert Android `KeyEvent`/`MotionEvent` to System 4 input codes

#### 3. File System Access
```java
// EngineFileSystem interface for game directory access
// Similar to how Buriko plugin accesses game assets
```

#### 4. Configuration Handling
- Support for `System40.ini` configuration files
- Integration with Enginehost's `options` JSON for engine-specific settings

## Bundle Manifest and ABI Assessment

### Bundle Structure
Based on `docs/engine-bundle-format.md:100-110`, the xsystem4 bundle must include:

```json
{
  "bundleId": "dev.enginehost.xsystem4",
  "assetName": "xsystem4",
  "engine": "xsystem4",
  "pluginVersion": "1.0.0",
  "apiVersion": "1",
  "entrypoint": "dev.enginehost.xsystem4.Xsystem4Plugin",
  "origin": "https://github.com/Droidtop/enginehost-xsystem4-plugin",
  "signing": { "algorithm": "SHA256withECDSA" },
  "dexFiles": ["/assets/enginehost.jar"],
  "capabilities": [ {
    "id": "xsystem4-1.0.0",
    "engineContext": "system4",
    "runtimeVersion": "1.0.0",
    "supportedSeries": ["1.0"]
  } ],
  "payloadSha256": "...",
  "files": [
    "/enginehost-bundle.json",
    "/enginehost-bundle.sig",
    "/lib/arm64-v8a/libxsystem4.so",
    "/lib/x86_64/libxsystem4.so",
    "/assets/System40.ini",
    "/assets/fonts/",
    "/assets/shaders/"
  ],
  "licenses": ["/COPYING"],
  "notes": "Requires upstream xsystem4 with Android build target"
}
```

### ABI Assessment

#### Current Upstream Status
- **Upstream Build**: Desktop-only (Linux/macOS/Windows via MSYS2)
- **Required Work**: Full Android port from scratch

#### Required Native Libraries
Both ABIs **must be built from scratch**:

1. **arm64-v8a**: Android's preferred ARM64 architecture
2. **x86_64**: Intel/AMD emulator and ChromeOS support

#### Build Targets
- **Minimum Android API**: 21 (Android 5.0) for broad compatibility
- **Supported ABIs**: arm64-v8a, x86_64
- **Dependencies**: SDL2 Android port, C runtime, audio/video codecs

### Bundle Size Estimates
- **Native Library**: ~50MB per ABI (libxsystem4.so with SDL2, audio, video)
- **Total Bundle Size**: ~120MB including fonts, shaders, and game data

## CI Workflow

### Build Workflow (Based on Buriko Plugin's `.github/workflows/build.yml`)

```yaml
name: Build xsystem4 Engine Bundle

on:
  push:
    branches: [ plugin/** ]
    paths:
      - 'plugin/**'
      - '.github/workflows/build.yml'
  pull_request:
    branches: [ main ]
    paths:
      - 'plugin/**'

jobs:
  build:
    runs-on: ubuntu-latest
    strategy:
      matrix:
        abi: [arm64-v8a, x86_64]
    steps:
      - uses: actions/checkout@v4

      - name: Set up Android SDK
        uses: android-actions/setup-android@v3
        with:
          build-tools: "34.0.0"
          ndk: "25.2.9519653"

      - name: Set up JDK
        uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '17'

      - name: Cache Gradle packages
        uses: actions/cache@v4
        with:
          path: |
            ~/.gradle/caches
            ~/.gradle/wrapper
          key: ${{ runner.os }}-gradle-${{ hashFiles('**/*.gradle*', '**/*.gradle.kts') }}

      - name: Build native libxsystem4.so
        run: |
          mkdir -p build
          cd build
          # Clone and build upstream xsystem4 with Android port
          git clone https://github.com/kichikuou/xsystem4.git xsystem4-upstream
          cd xsystem4-upstream
          # Apply Android-specific patches
          # Build with NDK for target ABI
          meson setup --cross-file ../ndk-crossfile.ini android-${{ matrix.abi }}
          ninja -C android-${{ matrix.abi }}
          # Copy libs to plugin directory

      - name: Build Enginehost bundle
        run: ./gradlew :buildEngineBundle --stacktrace
        env:
          BUILD_NUMBER: ${{ github.run_number }}
          TARGET_ABI: ${{ matrix.abi }}

      - name: Sign bundle
        uses: ./.github/actions/sign-bundle
        with:
          bundle-path: build/outputs/bundle/*.enginehost.tar.xz
          tooling-sha: ${{ github.sha }}

      - name: Upload bundle artifacts
        uses: actions/upload-artifact@v4
        with:
          name: xsystem4-${{ matrix.abi }}-bundle
          path: build/outputs/bundle/*.enginehost.tar.xz
          retention-days: 7

  sign:
    needs: build
    runs-on: ubuntu-latest
    if: github.ref == 'refs/heads/plugin/main'
    steps:
      - uses: actions/download-artifact@v4
        with:
          pattern: xsystem4-*
          path: bundles

      - name: Publish release
        uses: softprops/action-gh-release@v1
        with:
          files: bundles/**/*.enginehost.tar.xz
          draft: true
          prerelease: true
```

### Verification Workflow

Following the patterns in `docs/plugin-catalog.md:128-170`:

```yaml
name: Promote Plugin Release

on:
  workflow_dispatch:
    inputs:
      channel:
        description: 'Release channel'
        required: true
        default: 'testing'
        type: choice
        options:
        - testing
        - stable
      evidence:
        description: 'Evidence statement of hardware testing'
        required: true

jobs:
  promote:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/download-artifact@v4
        with:
          name: xsystem4-bundle

      - name: Verify bundle
        run: |
          ./scripts/verify-engine-bundle.py build/outputs/bundle/*.enginehost.tar.xz

      - name: Promote release
        run: |
          ./scripts/promote-plugin-release.py \
            --run-number ${{ github.run_number }} \
            --channel ${{ github.event.inputs.channel }} \
            --evidence "${{ github.event.inputs.evidence }}" \
            build/outputs/bundle/*.enginehost.tar.xz
```

## Plugin Repository Structure

The xsystem4 plugin repository (`github.com/Droidtop/enginehost-xsystem4-plugin`) should mirror the structure of the Buriko plugin but with System 4-specific adaptations:

### Directory Layout
- `plugin/` - Engine wrapper for Android (core plugin API integration)
- `third_party/xsystem4/` - Upstream xsystem4 sources (with Android port)
- `third_party/sdl-android/` - SDL2 Android port (or use system SDL2)
- `assets/` - Default configuration and support files

### Branch Strategy
Following the pattern in `README.md:275-279`:
- `plugin/<version>` - Android wrapper for each xsystem4 major version
- `plugin-core` - Portable core changes
- `main` - Upstream xsystem4 changes (where possible)

## Testing Requirements

### Device Testing
Games that currently work on xsystem4 desktop:
- **Rance VI**: Supported
- **Sengoku Rance**: Supported
- **DUNGEONS & DOLLS**: Unsupported

### CI Rig Requirements
**Needs a rig check**:
1. Android device with x86_64 or arm64-v8a support
2. Pre-installed xsystem4 with Android port
3. Test game assets (e.g., Rance VI game directory)
4. Steps to verify:
   - Install xsystem4 plugin bundle
   - Launch Rance VI game
   - Verify main menu appears
   - Test save/load functionality
   - Test controller input

## Future Work

1. **Complete Android Port**: Finish SDL2 Android integration in upstream xsystem4
2. **Asset Management**: Implement direct asset loading from APK/file system
3. **Controller Remapping**: Add System 4-specific controller configuration
4. **Graphics Optimization**: Optimize rendering for mobile GPUs
5. **Performance Tuning**: Reduce memory footprint for mobile devices
6. **v2 Bundle Support**: Migrate to portable engine bundle format (see `docs/engine-bundle-format.md:523-565`)

## License and Attribution

- **Engine**: GPL-2.0 (from upstream xsystem4)
- **SDL2**: zlib license
- **Fonts/Shaders**: As per upstream xsystem4 distribution
- **Plugin Code**: Apache 2.0 (enginehost license)

The plugin will carry all upstream licenses in the bundle manifest (`licenses` field) and comply with attribution requirements.
