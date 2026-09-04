# Engine bundle format, version 1

An engine bundle is one XZ-compressed POSIX tar archive named
`*.enginehost.tar.xz`. It is self-verifying: the signature and all information
needed to verify it are members of the archive, not required sidecar files.

The first two regular-file members must be, in this order:

1. `enginehost-bundle.json` — the exact UTF-8 manifest bytes being signed.
2. `enginehost-bundle.sig` — base64 DER ECDSA signature over those exact bytes.

The remaining regular-file members are payload. Their order must exactly match
the manifest's `files` array. Safe directory entries are allowed, but links,
devices, absolute paths, parent traversal, duplicate files, unsigned files, and
special tar entries are rejected.

The signature algorithm is `SHA256withECDSA` using a P-256 public key encoded as
X.509 SubjectPublicKeyInfo. The manifest carries the public key and its SHA-256
fingerprint. Enginehost additionally requires that fingerprint to equal the
key pinned for the manifest's `origin`; replacing both an archive and its
self-declared key therefore does not impersonate the repository.

The manifest includes `bundleId`, `assetName`, `engine`, `pluginVersion`,
`apiVersion`, `entrypoint`, `origin`, `signing`, `dexFiles`, `capabilities`,
`payloadSha256`, and `files`. An optional `engines` array names the engines the
bundle supports the way a person reads them ("RPG Maker XP", "RPG Maker VX
Ace"); it is the catalog card's title, and when absent the host derives the
list from the capabilities' contexts. Every `files` item signs the relative path, byte
size, POSIX permission bits, and SHA-256 digest.

Within one `bundleId`, `pluginVersion` is the total order on builds: a
release with the same ID from the same origin and a strictly higher
`pluginVersion` is an update and replaces the older build in place, while a
new ID (a new series, or a deliberate `-vN` bump) coexists. The repository's
metadata declares only the major and minor components as a statement of
intent; `build-engine-bundle.py` sets the third component to the CI run
counter (`GITHUB_RUN_NUMBER`, or `--build-number`), so every build of a line
is a strictly newer build than the one before it without anyone editing a
file. A hand-written third component is therefore replaced in CI, and a
deliberate bump is expressed in major or minor. How updates are
discovered, gated and approved is docs/plugin-catalog.md's Updates section;
the invariant that matters here is that replacing bytes never carries over
approval, which is bound to the exact archive digest and signer.

Each capability declares the exact `runtimeVersion` bundled. Compatibility can
be advertised as exact `supportedVersions`, inclusive `supportedRanges`, and/or
`supportedSeries`. A series is a dotted component prefix: `"8.2"` matches
`8.2`, `8.2.1`, and `8.2.1.24030407`, but never `8.3`. This lets version-line
plugins track the newest revision in a line while retaining the game's full
detected version for diagnostics and regression analysis.

`payloadSha256` is SHA-256 over each regular payload file in manifest/tar order:

```
UTF8(path) || 0x00 || ASCII(decimal_size) || 0x00 || raw_file_bytes
```

repeated without separators between records. Per-file hashes make individual
failures diagnosable; the aggregate binds the complete ordered payload.

Enginehost verifies the manifest signature before extracting payload, enforces
size and memory limits, extracts only into a private staging directory, checks
every signed property, writes a host-owned installation record, makes the tree
read-only, and atomically renames it into the installed-bundle registry. It
rechecks the signed manifest, pinned origin key, and all file hashes before
loading dex or native libraries into the isolated `:runtime` process.

`resourceApks` is an optional array of signed payload paths. Enginehost attaches
each listed APK's compiled resources to the runtime before loading the plugin
entrypoint. A path may appear in both `dexFiles` and `resourceApks`; this lets a
single embedded runtime APK carry code, resources, assets, and JNI libraries
without being installed as a separate Android package. Native libraries remain
under `lib/<abi>/` in the bundle so the runtime class loader can resolve them.

A resource APK must compile its resource table at a package id of its own.
Android resource IDs are `0xPPTTEEEE`, and aapt2 builds an ordinary
application at the default `0x7f` — the same id Enginehost's own resources
use. Two tables at one id do not produce an error; the platform answers a
lookup from whichever table it finds first, so the bundle silently receives
a host string, drawable or layout and fails somewhere unrelated. Build the
bundle's resources at a distinct id, conventionally `0x80` or above, with
aapt2's `--package-id` (Gradle: `androidResources { additionalParameters +=
listOf("--package-id", "0x80", "--allow-reserved-package-id") }`).
Enginehost reads the id out of `resources.arsc` and refuses to load a
bundle that collides, rather than letting it surface as a wrong resource.

Enginehost attaches each APK's loader to every `Resources` object a plugin
can reasonably resolve from — the runtime activity's and the application's.
An engine that keeps `context.applicationContext` therefore finds its own
resources without the bundle having to attach anything itself.

## Declared options

`declaredOptions` is an optional manifest array describing the `options`
keys the bundle's engine actually reads, so the config editor can offer a
labelled, typed control instead of a blind JSON field. It is advisory and
explicitly non-exhaustive: it is never a validation schema, a user can
always add a key no bundle declared, and no config fails to save because of
one.

Each entry carries `key` (required), `label`, `description`, `repeats`
(default false, for keys whose value is a JSON array), and `type`:

| type      | control                                                    |
| --------- | ---------------------------------------------------------- |
| `string`  | free text, parsed as JSON when it parses (the default)      |
| `number`  | free text                                                   |
| `boolean` | true / false                                                |
| `choice`  | a `choices` array of `{value, label}`                       |
| `path`    | the system folder picker; the value is a directory path     |
| `file`    | the system file picker; the value is a single file path     |

`path` and `file` are distinct because the engines are: mkxp-z's
`rtpPaths` wants a directory, while its `customScript` and `midiSoundFont`,
and EasyRPG's `soundfont`, `font1` and `font2`, each want one file. A
`file` entry may add a `mimeTypes` array of hints, which is passed to the
picker so a SoundFont field does not offer images; it is optional, and
omitting it offers everything. Extensions are deliberately not part of the
hint — the system picker filters on the MIME type a document provider
reports and has no notion of a file name suffix.

An unrecognised `type` falls back to the free-text editor rather than being
rejected, so a bundle declaring a type a given Enginehost build predates
stays usable.

## Runtime components (subplugins)

Some games need more than a stock engine: Goodbye Eternity is a Godot 4.5
game whose scenes are Spine animations, and its own desktop exports are
custom Godot builds with EsotericSoftware's `spine_godot` runtime compiled
in. An engine bundle must therefore be able to carry extensions to its
engine. Spine is the first; the format treats it as a general concept so
the next one follows the same path.

A runtime component ships **inside the parent bundle's signed payload** —
same tar, same manifest, same signature, same pinned origin key. A
component is native code loaded into the `:runtime` process, so it carries
exactly the trust weight of the engine itself; a separately-delivered or
separately-signed component would be a second trust mechanism beside the
existing one, and is deliberately not part of the format. Adding a
component means publishing a new bundle build.

A capability declares what it carries in `runtimeComponents`, a JSON
object of component name to exact version:

```json
"capabilities": [{
  "id": "godot-4.5-standard-v1",
  "runtimeVersion": "4.5.1",
  "supportedSeries": ["4.5"],
  "runtimeComponents": { "spine-godot": "4.2" }
}]
```

The name is scoped to the engine's ecosystem (`spine-godot`, not `spine`);
the version is the component's compatibility line as its own ecosystem
defines it — for spine-godot, the Spine editor major.minor whose exported
skeletons the runtime loads. A game's `enginehost.json` states what it
needs in `runtimeRequirements`, and a capability is eligible only when
every named component is present at exactly the required version:

```json
{ "engine": "godot", "engineVersion": "4.5.1",
  "runtimeRequirements": { "spine-godot": "4.2" } }
```

This reuses the engine-version vocabulary rather than inventing one — it
is the same two-sided constraint mkxp-z already expresses by declaring
`vxace` against both Ruby 3.1.3 and 1.9.2. When no installed capability
satisfies the requirements, plugin selection fails before launch with the
requirements named, which is a legible answer; the alternative this
section exists to prevent is the engine coming up without the component
and rendering black.

How the engine finds the component at runtime is engine-specific and
deliberately outside this contract. For Godot, `spine_godot` is compiled
into `libgodot_android.so` as an engine module — the same way the games'
own desktop exports embed it; such games ship no `.gdextension`, so the
classes must exist in the engine binary before their scripts parse. A
future component for another engine may instead be a payload shared
library its plugin loads at startup. Either way the bytes are signed
payload members, and the manifest's `source` may record the component's
upstream and revision under `source.components`.

A component's licence travels with it: the Spine Runtimes License permits
redistribution only when the licence and copyright notice are included, so
the Godot bundle ships `LICENSE-spine-runtimes.txt` in its payload beside
`LICENSE.txt` and `COPYRIGHT.txt`, and the manifest lists the licence in
`licenses`.

### The version matrix: compiled-in versus loadable components

Compiling a component into the engine binary makes every (engine version ×
component version) pair its own ~180MB bundle. That is multiplicative and
will not survive a second component version. It is how the first spine
bundle ships — it exactly reproduces what the games' own desktop builds do,
which made it the lowest-risk way to get a first game rendering — but it is
not the shape the format settles on.

The engine supports an additive shape, verified in the Godot 4.5.1
sources. Games need not declare an extension for the engine to load one:
the host does. During core registration — before any game script parses —
`register_core_types.cpp` runs `GDExtensionManager::load_extensions()`,
which ends by asking the OS for platform extensions;
`OS_Android::load_platform_gdextensions()` gets its list from the Java
side (`Godot.kt`, `getGDExtensionConfigFiles()`), which collects every
registered Android `GodotPlugin`'s `getPluginGDExtensionLibrariesPaths()`.
A `.gdextension` config path returned there is loaded at
`INITIALIZATION_LEVEL_CORE`, so the classes exist before the game's
scripts reference them — the same guarantee the compiled-in module gives.
spine-runtimes builds its extension flavor for `android.release.arm64`
officially (`spine_godot_extension.gdextension`, `build-extension.sh`,
godot-cpp), so the pieces exist on both sides.

In that shape one bundle carries one stock engine binary per Godot
version plus N small component libraries, and declares one capability per
combination it can serve — same engine `runtimeVersion`, different
`runtimeComponents` — with the plugin handing Godot the `.gdextension`
for whichever capability was selected. A new Spine version is then a few
megabytes of payload and a new capability entry, not a new engine build.
`runtimeRequirements` matching needs no change.

What keeps the first bundle compiled-in rather than this: the extension
flavor of spine-godot is the newer of Esoteric's two builds and has not
been proven against these games' module-imported resources on device.
Verifying that one game renders identically under the extension build is
what settles it; when it does, the compiled-in module should be retired
so there is one mechanism, not two.

## Controller input for android-activity plugins

Enginehost owns the controller map: one set of actions (`up`, `down`, `left`,
`right`, `confirm`, `cancel`, `menu`, `skip`, `auto`, `history`, `quick_save`,
`quick_load`, `page_previous`, `page_next`, the stick axes and triggers), a
global binding for each, and per-engine overrides, all edited in Enginehost's
controller settings. A plugin never hardcodes what a pad button does.

Plugin-api plugins receive the mapped actions through `onControllerEvent`.
Plugins on the android-activity transport receive the resolved map as the
`dev.enginehost.runtime.CONTROLLER_BINDINGS` extra, a JSON object from action
id to binding, `{"type":"key","code":<KeyEvent code>}` or
`{"type":"axis","axis":<MotionEvent axis>,"direction":-1|0|1}`. Such a plugin
matches incoming pad events against that map and translates each action into
whatever its engine understands (a keyboard key, an engine input, a touch);
the translation from action to engine input is the plugin's, the choice of
button is the person's.
