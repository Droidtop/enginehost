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
`payloadSha256`, and `files`. Every `files` item signs the relative path, byte
size, POSIX permission bits, and SHA-256 digest.

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
