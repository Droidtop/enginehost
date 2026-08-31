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
