# Engine bundle format, version 1

**Cores** (decided 2026-10-16, owner: "Yeah, let's switch to cores. We're
clearly 'RetroArch for VNs', no need to hide it"). An engine bundle installed
in Enginehost is a *core*, in everything a person or developer reads: the app,
these docs, release notes. Identifiers that other code, files or apps depend
on keep the old word, because renaming them would break compatibility for no
reader's benefit: Kotlin names (`EnginePlugin`, `PluginCatalog`, ...),
manifest fields (`pluginVersion`, `pluginVersions`, `runtimeTransport:
plugin`), the JWS `typ` `droidtop-plugin+jws` and the URN
`urn:droidtop:plugin:`, droidtop-platforms' `plugins/` folder and its plugins
index, and these docs' file names. Repository names and the `plugin-core` and
`plugin/<line>` branches change in a separate, coordinated step
(Droidtop/tracker#550). The Droidtop CA is a certificate identifier and is
named only where signing is explained.

An engine bundle is one XZ-compressed POSIX tar archive named
`*.enginehost.tar.xz`. It is self-verifying: the signature and all information
needed to verify it are members of the archive, not required sidecar files.

The first regular-file members must be, in this order:

1. `enginehost-bundle.json` — the exact UTF-8 manifest bytes being signed.
2. `enginehost-bundle.sig` — the legacy signature (below), when the bundle carries one.
3. `enginehost-bundle.jws` — the format-v1 signature ("Signatures" below), when it carries one.

At least one of the two signatures is present. The remaining regular-file
members are payload. Their order must exactly match
the manifest's `files` array. Safe directory entries are allowed, but links,
devices, absolute paths, parent traversal, duplicate files, unsigned files, and
special tar entries are rejected.

Signature format v1 is an X.509 certificate chain to the pinned Enginehost
signing root's key ("Signatures"). The legacy signature is `SHA256withECDSA` by a P-256
key the manifest's `signing` block carries (X.509 SubjectPublicKeyInfo and its
SHA-256 fingerprint), which Enginehost requires to equal the key pinned for the
manifest's `origin`; replacing both an archive and its self-declared key
therefore does not impersonate the repository. A bundle signed in v1 alone has
no `signing` block. Older Enginehost builds read only the legacy layout, so they
cannot install a bundle that carries `enginehost-bundle.jws`; they update
themselves first.

The manifest includes `bundleId`, `assetName`, `engine`, `pluginVersion`,
`apiVersion`, `entrypoint`, `origin`, `signing` (legacy only), `dexFiles`, `capabilities`,
`payloadSha256`, and `files`. A bundle that runs several engines (the web
runtime serves `html`, `rpgmaker` and `flash_air` from one browser) lists
their ids in an optional `engines` array and names the engine each
capability serves in its `engine` field; a capability without one belongs to
the bundle's `engine`. A game resolves against a bundle when its engine is
one the bundle runs and a capability for that engine matches its context and
version. The catalog card's title is derived from the capabilities. Every
`files` item signs the relative path, byte size, POSIX permission bits, and
SHA-256 digest.

Within one `bundleId`, `pluginVersion` is the total order on builds: a
release with the same ID from the same origin and a strictly higher
`pluginVersion` is an update. It is installed beside the older builds, which
stay until removed (several builds of a line coexist, SPEC.md "Several builds
of a line, side by side"); a new ID (a new series, or a deliberate `-vN` bump)
is a separate line. `pluginVersion` is `X.Y.Z-N`
(decided 2026-09-28, Droidtop/tracker#126): the version the repository
declares, then N, a per-version build counter -- the first published build of
a declared version is `-1`, the next `-2`, and a new declared version starts
again at `-1`. `dev.enginehost.Version` orders by X.Y.Z, then by N; a version
with no `-N` compares as build 0, so `1.0.0-N` is the 1.0.0 release and not a
pre-release. Versions published before this (`X.Y.<run>`, the CI run number
in the third place) are read as `X.Y.0-<run>` (`Version.parsePlugin`, decided 2026-09-29,
Droidtop/tracker#126): `1.0.57` is `1.0.0-57`, so a line's next patch bump
(`1.0.1-1`, `0.9.1-1`) orders above every legacy build and is offered as an update.
Every revision of a core's code bumps its declared version (owner,
2026-10-01: "We need to update the version string with every revision from
now on", after a crash could not be pinned to a build of the "v1.0" core):
a patch at minimum. The app shows the whole string (`1.0.1-3`) wherever it
names a core build. The `version` job of `sign-engine-bundle.yml` enforces
the rule for every core repository that calls it:
`scripts/check-declared-version.py` finds the newest release already
published for the bundle id and fails the build when the declared version is
below it, or equal to it while any file outside documentation and CI wiring
(`.github/`, `docs/`, `*.md`, `LICENSE`, the key and origin documents)
differs from the commit that release was built from. A re-run of the published
commit, and a declared version above everything published, pass.
Only core bundle versions and the `pluginVersions` allowlist use this reading;
engine and runtime versions do not. The one script
that emits this form is `scripts/build-engine-bundle.py`, run by the pinned
`sign-engine-bundle.yml` job: it reads the calling repository's releases and
numbers the build (highest published `-N` for that declared version on that
bundle id, plus one), so a CI rerun never repeats a number. In a game's `pluginVersions` allowlist a token that is
one version (`1.0.0-3`) is exact and a range joins two versions with a dash
(`1.2.0-1.4.0`). How updates are
discovered, gated and approved is docs/plugin-catalog.md's Updates section;
the invariant that matters here is that replacing bytes never carries over
approval, which is bound to the exact archive digest and signer.

Each capability declares the exact `runtimeVersion` bundled. Compatibility can
be advertised as exact `supportedVersions`, inclusive `supportedRanges`, and/or
`supportedSeries`. A series is a dotted component prefix: `"8.2"` matches
`8.2`, `8.2.1`, and `8.2.1.24030407`, but never `8.3`. This lets version-line
cores track the newest revision in a line while retaining the game's full
detected version for diagnostics and regression analysis.

`payloadSha256` is SHA-256 over each regular payload file in manifest/tar order:

```
UTF8(path) || 0x00 || ASCII(decimal_size) || 0x00 || raw_file_bytes
```

repeated without separators between records. Per-file hashes make individual
failures diagnosable; the aggregate binds the complete ordered payload.

Enginehost verifies the manifest's signature (BundleVerifier) before extracting payload, enforces
size and memory limits, extracts only into a private staging directory, checks
every signed property, writes a host-owned installation record, makes the tree
read-only, and atomically renames it into the installed-bundle registry. Every
payload byte is hashed once, on the way in; the installer then records each
file's inode, size and change time. Before loading dex or native libraries into
the `:runtime` process (a separate process under the app's own UID, not an
Android isolated process; docs/engine-sandbox.md says what it may do), every launch rechecks the signed manifest, its
signature up to its anchor, revocation, and the installation record's signer, refuses any file in
the bundle the manifest does not sign, and compares each payload file with its
recorded stamp. The kernel moves a file's change time on any write, truncate or
chmod and a replaced file has a new inode, so a file whose stamp matches holds
the bytes that were hashed; a file whose stamp differs is hashed again against
its signed digest and the launch is refused if it no longer matches. A bundle
installed before stamps existed is hashed in full on its first launch.

`resourceApks` is an optional array of signed payload paths. Enginehost attaches
each listed APK's compiled resources to the runtime before loading the core
entrypoint. A path may appear in both `dexFiles` and `resourceApks`; this lets a
single embedded runtime APK carry code, resources, assets, and JNI libraries
without being installed as a separate Android package. Native libraries remain
under `lib/<abi>/` in the bundle so the runtime class loader can resolve them.
The resources and assets are reachable from the application's `Resources`, the
runtime activity's, and every Activity the runtime process creates afterwards,
including a bundle's own Activity (`runtimeTransport: activity`), whose Context
Android builds before Enginehost can see it. From API 30 a `ResourcesLoader` is
added to each Activity before its `onCreate`. Below 30 the APK joins the
application's shared-library paths, which Android reads when it builds each
Activity's `Resources`, and a bundled Activity built before that is started once
more so its own `Resources` hold the APK from the start (its theme is resolved
before `onCreate`). A resource APK's name must therefore end in `.apk`. Below
API 29 the application class loader, through which layouts name their view
classes, is also given the bundle's classes (the platform's own hook for that,
`AppComponentFactory.instantiateClassLoader`, exists from 29).

A bundle carries **both 64-bit ABIs, in one archive**: `lib/arm64-v8a/` and
`lib/x86_64/`, and no others. The owner dropped 32-bit everywhere on 2026-10-24
(Droidtop/tracker#646): `armeabi-v7a` and `x86` are neither built nor shipped, even
where an engine's upstream provides them. Enginehost itself builds only these two.
Enginehost picks the directory at load time from
`Build.SUPPORTED_ABIS`, in the device's own order of preference, so there is no
per-architecture bundle, channel or download to choose between: one bundle
installs on the console and on an x86_64 device or emulator alike. A core whose
upstream cannot yet produce a second ABI says so in its metadata `notes` (which
the manifest passes through verbatim) and in its BRIEF, naming the prebuilt that
is missing and the upstream it would come from; it does not quietly ship one ABI
and leave the host to fail at `System.loadLibrary`. A core with no native code
at all carries no `lib/` directory and is architecture-agnostic.

Enginehost enforces its side of this at install: a bundle whose payload carries
`lib/<abi>/` directories for ABIs this device runs none of is refused with a
sentence naming both sides, instead of installing cleanly and dying at
`System.loadLibrary` with an `UnsatisfiedLinkError` on first launch — a bundle
picked from a file or a custom origin never passes through the catalog's
admission check, so the host is the last one that can say it. The device's half
of the comparison is `Build.SUPPORTED_ABIS`, the same list the runtime's own
native library path is built from (in-process, activity-transport and isolated
alike), so an install can never disagree with the load-time choice it mirrors;
and a device that runs an ABI through binary translation (an x86_64 emulator
listing `arm64-v8a`) is a device that runs it. The install check is deliberately
not the two-ABI minimum itself: a single-ABI bundle still installs anywhere it
does run, and the requirement that a *published* bundle carry both required ABIs
stays an admission rule, the catalog index's to enforce. A runtime-component
bundle ("Runtime components" below) keeps its libraries under the same
top-level `lib/<abi>/`, so the same refusal and the same admission rule apply
to it; the engine wrapper, not the host's library path, loads them.

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

Enginehost attaches each APK's loader to every `Resources` object a core
can reasonably resolve from — the runtime activity's and the application's.
An engine that keeps `context.applicationContext` therefore finds its own
resources without the bundle having to attach anything itself.

## Signatures

Signature format v1 (decided 2026-10-15, Droidtop/tracker#550; owner: "We do
NOT want to chain the signature past this key"). The same format droidtop
verifies; this section is Enginehost's side of it and what Enginehost adds.
Code: `CertificateSignatures.kt` (the JWS and chain checks), `BundleVerification.kt`
(which signature decides, at which moment), `BundleTrust.kt` (the pinned root,
revocation lists), `LegacyBundleSignatures.kt` (the bounded legacy path);
tooling: `scripts/bundle_jws.py`, `build-engine-bundle.py`,
`verify-engine-bundle.py`, `provision-signing-certificates.py`,
`make-publisher-certificate.py`, `sign-revocation-list.py`.

### Shared trust code (Droidtop/tracker#191 and #193)

The verifier and the container reader are not Enginehost's own code. droidtop and Enginehost each vendor
`Droidtop/droidtop-shared` and neither depends on the other: Enginehost pins it as the submodule
`vendor/droidtop-shared` and compiles its `version` and `trust` sources into the app (`app/build.gradle.kts`).
`CertificateSignatures` is Enginehost's names and types (a `TrustAnchor`, a `RevokedSet`, a `VerifiedJws`, and the
convention that a signature which does not verify throws `IllegalArgumentException` with the reason) over the library's
`ChainSignature`; `Version`, `VersionSeries` and `VersionConstraint` are the library's; the archive reader
(`EngineBundleInstaller.extractVerified`) streams entries through its `BundleTar`, which refuses an unsafe path, a link
or special file, a repeated path and an xz stream that asks for more than 512 MiB of decoder memory, and keeps what is
Enginehost's here: the manifest first, then the signatures, then exactly the signed payload in the signed order, with
the signer deciding before a payload byte is written. The library pins no key; the Enginehost root and the keys a
person pinned stay in this app. A certificate that marks an extension critical which the verifier does not read is
refused (as PKIX requires), and a signature's JWS header is read with `kotlinx-serialization-json`, a dependency this
app gained for it. The older root-certified origin-key document (`OriginKeyDocuments`) is not part of the library.

### The anchor

The hierarchy (owner, 2026-10-15: "enginehost gets its key separately derived
from the ca, then plugins are derived from that"):

```
Droidtop CA              the owner's CA key; never pinned or used by any app
  └ Enginehost signing root    P-256, derived from the CA by the owner; pinned here
      ├ one certificate per official repository   (purpose .1, its origin)
      ├ the developer certificate                 (purpose .1, urn:enginehost:developer)
      └ the revocation-list signer                (purpose .3, catalog enginehost)
```

droidtop and gamegrab have roots of their own, derived the same way; none of
them is trusted here.

- **The Enginehost signing root's public key** is the one anchor Enginehost
  pins for its own cores: `app/src/main/assets/trust/enginehost-root.pub`, a
  PEM `PUBLIC KEY` block (SubjectPublicKeyInfo), byte for byte the file
  published at Droidtop/tracker `coordination/signing/roots/enginehost-root.pub`.
  The anchor is the key, not the root's self-signed certificate (owner,
  2026-10-15: "The cert exists on the local machine. It will not be
  uploaded." / "Pubkeys will be committed, but allow the coordinator to do
  that."). The root is derived from the CA's private key, not certified by
  the CA, so nothing public links the two; that link is the owner's word.
- **The walk stops at the root.** A chain is the signing certificate, then at
  most two intermediates. Its last certificate must verify under the root's
  key, and no certificate in it may carry the root's key. With no pinned
  certificate there is no issuer name to compare; the signature decides.
  Nothing above the root is read, needed or trusted: a certificate the Droidtop
  CA signed directly is refused, as is one under droidtop's or gamegrab's
  root.
- **Extended key usage is read from the extension's DER**, not through
  `X509Certificate.getExtendedKeyUsage()`: Android's provider (Conscrypt,
  BoringSSL) throws on the purpose OIDs' UUID arc, which is wider than 64 bits.
  `ConscryptSignaturesTest` runs the verifier under Conscrypt.
- **A repository a person added** (by URL, or from the third-party list) is
  anchored at the key they pinned for it, the same pin a legacy signature was
  checked against; existing pins carry over with no new prompt. The chain's last
  certificate is signed by that key, or is the publisher's own certificate that
  carries it.
- **The signing root outranks a pin.** A bundle whose chain reaches the root
  for its origin is official, as a root-certified key was; a person's pin is
  tried only when it does not.
- **A build without the root's key** pins no root: no official v1 signature
  can be verified, and the legacy signature keeps working for new installs
  ("Legacy signatures").

### Certificates

- **Signing certificate** (the leaf): X.509 v3, signed with sha256/384/512WithRSA
  or ecdsa-with-SHA256/384; key EC P-256 (`ES256`, what provisioning issues) or
  RSA of 2048 bits or more (`RS256`); `basicConstraints` CA:FALSE or absent;
  `keyUsage` present with digitalSignature and without keyCertSign;
  `extendedKeyUsage` containing the purpose:
  `2.25.230248497294207031301727532441854392946.1` core bundles, `.2` catalog
  indexes, `.3` revocation lists.
- **Identity**, as `subjectAltName` URIs:
  `urn:droidtop:origin:<owner>/<repo>` (the bundle's GitHub origin, lower case;
  required on a bundle-signing certificate, and the manifest's `origin` must be
  one of them); optionally `urn:droidtop:plugin:<bundleId>` or
  `urn:droidtop:plugin:<prefix>.*`, which narrow the bundle IDs it may sign;
  `urn:droidtop:catalog:<id>` on a revocation-list signer.
- **Developer builds** (Enginehost's addition): a root-issued bundle-signing
  certificate whose URI is `urn:enginehost:developer` signs a locally rebuilt
  bundle for any origin. It is accepted only for a bundle file a person
  installs (and at its launches), never from a catalog, and every trust surface
  marks it Developer, so it cannot pass for an official release.
- **Intermediates** (optional, at most two): CA:TRUE, `keyUsage` keyCertSign,
  path length constraints honoured.
- Any other extension marked critical refuses the certificate.

### The JWS

`enginehost-bundle.jws` is a JWS (RFC 7515) in compact serialization with
detached content (Appendix F): `BASE64URL(header) "." "." BASE64URL(signature)`,
signed over `BASE64URL(header) "." BASE64URL(the exact manifest bytes)`. The
protected header is the only header: `alg` (`ES256`, signature R‖S, or
`RS256`, matching the key), `typ` (`droidtop-plugin+jws`), and `x5c` (standard
base64 DER, the signing certificate first, the root excluded). A `crit` parameter
refuses the signature; other unknown parameters are ignored. A release
envelope entry carries the same text as `jws`, beside or instead of the legacy
`signatureBase64`; the plugins index entry of an official repository carries
the repository's chain as `certificates` (x5c form), which is how an official
origin is learned without an app release.

### When each is checked

`BundleVerifier` decides at four moments: a catalog entry, a catalog download,
a file a person installs, and a launch. No certificate's validity period is
read at any of them (owner, 2026-10-15: "Can we ignore its expiration date? We
don't want older plugins to be invalidated" / "We don't use the certificate
itself's validity period"): an expired or not-yet-valid certificate verifies,
a JWS carries no trusted time to check one against, and a device's clock never
matters. Only the revocation list withdraws trust ("Revocation"). When the bundle's origin has an
anchor (the signing root, or the person's pin), the JWS alone decides: a v1 signature that
fails is never rescued by a legacy one beside it. The signer recorded at
install (`signer` in the installation record: its key, kind, format and anchor)
must match what each launch verifies. The signer's key (the signing
certificate's SubjectPublicKeyInfo SHA-256) is the identity approvals are kept
under, so an update under the same certificate key carries an approval
(PluginTrustStore) and one under a new key asks.

### Legacy signatures

Legacy keys are deprecated (owner, 2026-10-16: "Deprecate legacy keys. One
droidtop installation currently exists, and it's mine. Detect legacy plugins
if someone DOES have one installed, and instruct the user to delete the old
one and download the new one"). The legacy `enginehost-bundle.sig` is
accepted only while this build pins no root key, where nothing else could be
verified. With the root pinned it is refused at every moment:

- a new install, update or catalog entry needs a v1 signature;
- a core already installed with only the legacy signature does not start.
  Its card on Cores, and the launch's error, say to remove it and install it
  again (`BundleVerifier.LEGACY_INSTALLED`, `InstalledPlugin.legacySigned`).
  Every published official stable build has a v1-signed release to install
  instead, the same build re-signed where it predates v1.

The legacy verifier is `LegacyBundleSignatures.kt` and the official-root key
documents in `PluginOriginKeys.kt`. They are deleted, with the manifest's
`signing` block and the signing workflow's `ENGINEHOST_SIGNING_KEY_PEM`, in
the release after every official core has shipped a release signed in v1.
Until then a revocation list stops a legacy key by its SubjectPublicKeyInfo
SHA-256.

### Revocation

A signed list, carried where the catalog is, never CRL or OCSP (the device may
be offline; the catalog refresh is the moment it already has). `revocations.jws`
is a JWS with an attached payload, `typ` `droidtop-revocations+jws`, signed by
a revocation-list certificate (purpose `.3`) naming its catalog:

```json
{"type":"droidtop-revocations","version":1,"catalog":"enginehost","sequence":7,
 "issuedAt":1792000000,"certSha256":["<hex>"],"keySha256":["<hex>"]}
```

- **Enginehost's own list**, catalog `enginehost`, is `plugins/revocations.jws`
  in droidtop-platforms beside the plugins index, signed by the root's
  revocation-list certificate. It applies to every root-anchored chain and to
  legacy official and developer keys.
- **An added repository's list**, catalog `<owner>/<repo>`, is `revocations.jws`
  on its core branch, under the key the person pinned; read at most once a
  day per repository (GitHub API allowance). It applies only to that origin.
- A list replaces the one held only with a higher `sequence`, so an old list
  cannot be replayed to un-revoke, and its own signer must not be revoked by
  the list it replaces. A held list is verified again when first read in each
  process. A certificate or key named anywhere in a chain, or the anchor's key,
  refuses it at every moment, launches included.

### Signing, provisioning and the owner's steps

- CI signs in `.github/workflows/sign-engine-bundle.yml`: v1 when the core
  repository sets `ENGINEHOST_CERTIFICATE_KEY_PEM` and commits
  `enginehost-signing-certificate.pem`, legacy when it sets
  `ENGINEHOST_SIGNING_KEY_PEM`, both during the transition. The signed bundle
  is verified to the pinned root before it leaves the job.
- The owner derives the Enginehost signing root from the Droidtop CA, outside
  this repository. `scripts/provision-signing-certificates.py`, run by the
  owner where the root's key is (`--root-key`, `--root-certificate`), derives
  each repository's key deterministically (HKDF-SHA256 over the root's raw
  private value, salt `droidtop-ca-root-v1`, info
  `enginehost-plugin-root-master-seed`, then the existing per-origin
  derivation) and issues its certificate under the root. Deriving the root
  again reproduces every key. The Enginehost APK signing key is not rotated
  and not derived here.
- A third-party publisher signs v1 with the key people already pinned for its
  repository: `scripts/make-publisher-certificate.py` makes a self-signed
  certificate for that key naming the origin, and the same build options use
  it. With the root pinned, a third party's new releases need it too.
- **Re-signing a stable release** (owner, 2026-10-16): the September stable
  releases carry only the legacy signature, and their code was verified on
  hardware when promoted, so they are re-signed instead of rebuilt.
  `.github/workflows/resign-engine-release.yml` (dispatched per repository)
  runs `scripts/resign-engine-bundle.py`: it adds `enginehost-bundle.jws`, a
  JWS over the unchanged manifest bytes, after the legacy signature and
  writes every other member back unchanged. It then verifies the result both
  ways and publishes it as a new stable release, `<tag>-resigned`, beside the
  published one, which is left as it is. Code and build number are the same;
  only the archive digest differs.
- The root's key is pinned (2026-10-15). It replaced a direct pin of the
  Droidtop CA's key, which no release ever carried a certificate for. From the build
  that carries it, official cores install and update only with a v1
  signature (owner's choice, the strict rule as specified: no legacy gap
  window); installed ones keep running. Provisioned on 2026-10-15: every
  official repository's `ENGINEHOST_CERTIFICATE_KEY_PEM` secret, and the
  certificates at Droidtop/tracker `coordination/signing/enginehost/`, which
  each repository commits to plugin-core as `enginehost-signing-certificate.pem`.
  Left to the owner: sign and publish the first `plugins/revocations.jws` when
  something is revoked, and hand the developer certificate and its key to the
  developer; once every official core has a v1 release, drop
  `ENGINEHOST_SIGNING_KEY_PEM` and delete the legacy verifier.

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

## Runtime components (parts of a core installed on their own)

Some games need more than a stock engine: Goodbye Eternity is a Godot 4.5
game whose scenes are Spine animations, and its own desktop exports are
custom Godot builds with EsotericSoftware's `spine_godot` runtime compiled
in. A core must therefore be able to load extensions to its
engine. Spine is the first; the format treats it as a general concept so
the next one follows the same path.

### Component bundles (decided 2026-10-14)

A runtime component is **its own bundle, installed separately and
distributed under its own licence** (owner, 2026-10-14, Droidtop/tracker#291:
"This is part of the subplugin system that has been pending for a while").
The reason is licensing as much as size: the Spine Runtimes License is not
GPL-compatible, so an engine bundle with Spine compiled in (or carried in its
payload) cannot be distributed as the GPL-3.0 work every official bundle is.
A separate component bundle keeps the engine bundles GPL-3.0 and puts the
Spine runtime, under the Spine licence, in front of the person before they
choose to install it.

A component bundle is the same format as an engine bundle: the same
`.enginehost.tar.xz`, manifest, signature, repository key, installer,
payload verification, update check and trust screen. It is a second kind of
bundle, not a second trust mechanism. What differs is in the manifest:

```json
{
  "kind": "component",
  "bundleId": "dev.enginehost.godot.component.spine-godot.v42",
  "engine": "godot",
  "pluginVersion": "1.0.0",
  "apiVersion": 1,
  "component": {
    "engine": "godot",
    "name": "spine-godot",
    "version": "4.2",
    "supportedSeries": ["4.2", "4.3", "4.4", "4.5", "4.6"]
  },
  "license": { "name": "Spine Runtimes License", "file": "LICENSE-spine-runtimes.txt" }
}
```

- `kind` is `component` (absent means `engine`).
- `component` names the engine whose cores load it, the component's
  ecosystem-scoped name (`spine-godot`, not `spine`) and its version (the
  component's compatibility line as its own ecosystem defines it: for
  spine-godot, the Spine editor major.minor whose exported skeletons it
  loads), and the engine versions it carries a build for (`supportedSeries`).
- `license` is required: its name and the payload file holding its text.
  The trust screen shows that text, open, and the approve button reads
  "Accept licence and allow"; approving is accepting. An engine bundle may
  declare a `license` too, and is shown the same way. A bundle that declares
  a licence is never approved by default, even when official: the official
  default (`PluginTrustStore.effectiveState`) would install it with no accept,
  which the #291 rig check found (Droidtop/tracker#291). Installing it from the
  catalog leads to its trust screen, and a launch waiting on it resumes only
  after the accept.
- It has no `dexFiles`, no `entrypoint` and no `capabilities`: Enginehost runs
  no code of a component and no game runs on one. Its native code is loaded
  by the core, into the engine's own runtime process, so it carries
  the trust weight of engine code and is approved like it.
- Its payload layout is the loading core's business. Native libraries sit
  under the top-level `lib/<abi>/` so the installer's ABI check and the
  plugins index's both-ABIs rule apply to it as to any bundle.

A component's release envelope is named `enginehost-component.json` instead
of `enginehost-release.json` (same format). An Enginehost from before
component bundles reads only the latter, so it skips a component release
instead of failing to parse a manifest with no entry point, which would have
failed that repository's whole catalog. Enginehost now reads both, as do the
plugins index generator (droidtop-platforms) and the release tooling here.

### A game asks, and receives (decided 2026-10-23)

The owner, on how a component reaches a game: "spine works like all subplugins do: games asks, and
it receives". So a component is requested by the running game, not predicted before it starts:
the core's engine notices the game loading an extension it does not have, asks Enginehost for it
through the plugin API, and waits. Enginehost installs the component bundle if it is not
installed, shows its licence for acceptance if it is not approved, and hands the core its verified
root, and the engine then loads it and carries on. As an engine, it can wait without the game
noticing more than a pause (owner, earlier: "it should wait. Luckily, as the engine, we have more
control than if we were stalling a separate process").

**The request (built, 2026-10-23).** A core calls `EngineHost.requestComponent(name)` off its
UI thread (plugin API; the default on an older host answers null). Enginehost
(`ComponentRequests`):
1. reads the version the game's own files call for, at that moment (for `spine-godot`, its
   skeletons, `SpineSkeletonScan`); none said means the newest;
2. answers at once with the newest installed and approved component bundle of that name and
   version whose `supportedSeries` covers the engine's runtime, verified as every bundle a launch
   loads;
3. otherwise opens `ComponentRequestActivity` over the game: "This game needs the Spine 4.2
   runtime", with Install it (from a source that publishes it, after which the trust screen shows
   its licence to accept), Read and accept its licence (installed, waiting), or Not now. Its answer
   reaches the waiting call over binder, through the `ResultReceiver` the request carries, and wakes it
   at once (Droidtop/tracker#768; a file polled every 250 ms before), and the call returns
   the component's root, or null for not now, nothing published, or the game ending.

A locked-down core cannot be handed a component it did not have when it locked down (its broker's
rules are set then); that is for when a core with components is locked down.

This replaced deciding before the launch (2026-10-23): no component bundle is chosen, loaded or
installed before a game starts. Godot's engine asks from `4.5-components` 1.2.3 on (the
plugin's README, "A missing class is asked for").

### Which capabilities load which components

A capability declares the components it can load in `loadsComponents`, by
name:

```json
"capabilities": [{
  "id": "godot-4.5-standard-v2",
  "runtimeVersion": "4.5.1",
  "supportedSeries": ["4.5"],
  "loadsComponents": ["spine-godot"]
}]
```

A game's `enginehost.json` states what it needs in `runtimeRequirements`
(detection writes it; `SpineSkeletonScan` for Spine):

```json
{ "engine": "godot", "engineVersion": "4.5.1",
  "runtimeRequirements": { "spine-godot": "4.2" } }
```

Whether a capability may run the game (`EngineCapability.canServe`): each requirement is met by
the capability itself, when its `runtimeComponents` carries that exact version (Ruby for RPG
Maker; the Godot lines that still compile Spine in), or, for a name in `loadsComponents`, by its
engine asking for it while the game runs, **whatever the version**. The owner, 2026-10-23: "Part
of switching to subplugins is to make them version-independent". Which core runs a game does not
depend on the component's version or on what is installed; the version is the game's to say when
it asks (above). A capability that neither carries nor asks for a requirement is not eligible,
and the catalog says which component nothing here provides (`RuntimeRequirementReport.unmet`).

`SpineSkeletonScan` still reads a Godot game's skeletons. Detection writes `spine-godot` into
`runtimeRequirements`, which keeps a Spine game off a core that cannot provide Spine at all, and
`ComponentRequests` reads the version from it when the game asks.

Nothing is loaded at launch any more. `EnginePluginSession.runtimeComponents()` is what the
capability carries, and `runtimeComponentDirectory(name)` answers null (older cores call it and
load nothing). The launch's former detour (a component waiting for approval opened its trust
screen; otherwise the catalog offered the component bundle) is gone. The request screen
over the running game does that now.

Selection among capabilities that serve the game prefers the one that loads
the fewest carried components the game did not ask for
(`EngineCapability.unrequestedComponents`, after version specificity and
before core version).

### Godot: components are GDExtensions

How an engine finds a component is engine-specific. For Godot it is a
GDExtension the wrapper hands the engine, verified in the Godot sources from
4.2 on. During core registration, before any game script parses,
`register_core_types.cpp` runs `GDExtensionManager::load_extensions()`, which
ends by asking the OS for platform extensions;
`OS_Android::load_platform_gdextensions()` gets its list from the Java side,
which collects every registered Android `GodotPlugin`'s
`getPluginGDExtensionLibrariesPaths()`. A `.gdextension` config returned there
is loaded at `INITIALIZATION_LEVEL_CORE`, so the classes exist before the
game's resources name them, the same guarantee a compiled-in module gives.
spine-runtimes builds its extension flavor officially
(`spine_godot_extension.gdextension`, `build-extension.sh`, godot-cpp).

So the Godot core's wrapper (`GodotComponents`, plugin-core of
enginehost-godot-plugin) registers a host `GodotPlugin` whose
`getPluginGDExtensionLibrariesPaths()` returns, for each component of the
launch, `<component root>/godot-<series>/<name>.gdextension` for the engine's
own series. The spine-godot component bundle carries one build per Godot
series it supports, each compiled against godot-cpp at that series' release
tag, for arm64-v8a and x86_64:

```
LICENSE-spine-runtimes.txt
godot-4.5/spine-godot.gdextension          [libraries] android.arm64 / android.x86_64
lib/arm64-v8a/godot-4.5/libspine_godot.so
lib/x86_64/godot-4.5/libspine_godot.so
...one godot-<series> set per supported series
```

The Godot engine bundles are built with no extra module compiled in and are
GPL-3.0; their capabilities declare `loadsComponents: ["spine-godot"]`. The
compiled-in Spine bundles (lines 4.2 to 4.6 before 2026-10-14) and the
payload-carried `plugin/4.5-components` experiment are retired: their builds
are out of the official catalog, so there is one mechanism.

**Choosing a library under a presented platform.** The Godot lines can show a
game the desktop platform it was exported for (`ENGINEHOST_GODOT_PLATFORM`;
`OS.get_name()` answers "Windows" and the `pc`/`windows` feature tags are
set). GDExtension library selection must not follow that: a component's
`android.*` entry would never match and, on an x86_64 device, a game's
`windows.*.x86_64` DLL would. The engine therefore picks a `[libraries]` entry
with Android's own platform tags and every other tag as usual (an engine-side
patch on each line). That one rule serves both a game's own Android libraries
and the host's component configs.

### The module system: one mechanism for every Godot component (decided 2026-09-17)

Spine is one component, and shipping games use many more. The user's
direction: "we only support spine, but there are SO MANY other modules."

**Order of preference (owner, 2026-10-23).** "If games ship them, we just
need to make sure we can load them properly. That's actually very much
preferably to having it ourselves". This applies to every core, not only Godot:

1. **Load what the game ships, as shipped.** GDScript and C# addons, Ren'Py
   Python modules, MV/MZ JavaScript plugins, pure-Lua libraries, and Godot
   GDExtensions whose package already names an Android arm64 or x86_64
   library. The core finds and loads them correctly (paths, load order, the
   extension manifest), and a failure to load one is a core bug, not a missing
   component.
2. **Only where the game ships a native library for its desktop platform
   alone** (a GDExtension with only a Windows `.dll` or Linux `.so`, a KiriKiri
   DLL plugin, an AGS engine plugin): Enginehost provides an Android build of
   that same library, at the version the game ships, as a component. It is
   matched to what the game declares and stands in for the game's own file. It
   is never bundled by default.
3. **Spine.** Where a game ships spine-godot as a GDExtension, 1 or 2 applies.
   Where the game's Godot build had Spine compiled into its engine (every
   Spine game in the library today), the spine-godot component is the stand-in.

**Enginehost only runs native cores (owner, 2026-10-23).** "We will never run
a whole build in fex using enginehost. That defeats the PURPOSE of enginehost,
which is to streamline game execution". A game runs in Enginehost on an
Android core, or not at all. A native library the game ships for its desktop
platform alone is answered by preference 2 (Enginehost provides an Android
build of it). Where no Android build can be provided, the game is not
supported in Enginehost. Emulating a desktop build (FEX, Box64, Wine) is
droidtop's Wine/Linux runtime, never Enginehost.

**What a game can depend on.** Two things, resolved by one mechanism:

- **GDExtensions the game ships.** A game carries `*.gdextension` files
  (listed in `res://.godot/extension_list.cfg`) whose `[libraries]` table
  names one library per platform. A desktop export names no `android.*`
  library, so on Android the engine has nothing to load. The component
  Enginehost supplies is the **Android build of that same extension at that
  same version**, built from the extension's own source against godot-cpp
  for the engine line.
- **Engine modules the game's export template was built with.** A game
  built on a custom template (spine-godot's module flavor is the case in this
  library) references the module's classes from its resources
  (`SpineSkeletonDataResource`, ...). The component is the **extension
  flavor of the same module**, loaded at `INITIALIZATION_LEVEL_CORE`.

**Detection writes the requirement; nobody types it.** The Godot detector
reads the pack: every `*.gdextension` entry and, for module-built games, the
class names of imported resources against a table of known module classes,
and writes `runtimeRequirements` as `{ "<component>": "<version>" }`. A person
may still override it in `enginehost.json`; detection never overwrites a
hand-written value.

**The wrapper injects, and never edits the game.** For a game-shipped
extension whose own `.gdextension` names no Android library, the component
provides a `.gdextension` with the same `entry_symbol` pointing at its Android
libraries; the game's file in the pack is left as it is. A game whose
`.gdextension` already names an Android library needs no component.

**What stays out.** Extensions that exist only as Windows DLLs with no source
cannot be built for Android; detection records the requirement and core
selection fails before launch with the component named.

### Adding a runtime component

1. **Build it** as its own line of the engine's core repository (Godot:
   `enginehost/lines/component-<name>-<version>/` on plugin-core, composed like
   any line, Enginehost docs/plugin-lines.md). Its workflow builds the
   component from its upstream source at a pinned revision for every engine
   series it supports, for `arm64-v8a` and `x86_64`, checks each library
   exports its entry symbol, and packs the payload with the component's licence
   text. `component-spine-godot-4.2` is the pattern.
2. **Describe it** in the line's `enginehost/bundle-metadata.json`: `kind`,
   `component`, `license`, a bundle id of its own
   (`dev.enginehost.<engine>.component.<name>.v<version>`).
3. **Let engines load it:** add its name to `loadsComponents` on the engine
   capabilities whose wrapper can load it, and bump their `pluginVersion`.
4. **Make games ask for it.** The engine asks when the game needs it (for Godot, a missing
   class, mapped to the component in the plugin's `GodotComponents.PROVIDERS`). Detection
   writes the name into `runtimeRequirements` so the game goes to a core that can ask.

### Host services added after apiVersion 1 shipped

`EngineHost.emitText(event)` and `EngineHost.replaceText(event)` (2026-10-28): the game's text, in
the `ehtext/1` format, for Enginehost to read aloud and translate, and the translated text to
print instead (docs/text-services.md). `emitText` returns at once from any thread; the defaults
do nothing and answer null. On an older host a core catches `IncompatibleClassChangeError` and
stops sending.

`EngineHost.openLink(url)` (2026-10-24): a link the game asks to open, offered to the person
(docs/SPEC.md, "Links a game opens"). Returns at once; the default answers false. A core routes
its engine's own link calls here and never calls startActivity for a link. On an older host it
catches `IncompatibleClassChangeError` and opens nothing.
A core on the activity transport has no `EngineHost`; it calls
`dev.enginehost.ActivityLinks.offer(Activity, String)` by reflection (2026-10-24), and opens nothing
when that class is missing (an older Enginehost).

`EnginePluginSession.runtimeComponents()` (2026-10-02): what the launch
loads beside the engine, so a core that loads components knows which
(above). A core that may run on an older Enginehost catches
`NoSuchMethodError` and uses `runtimeRequirements()` instead, which the
capability was selected against.

`EnginePluginSession.runtimeComponentDirectory(name)` (2026-10-14): answered the
separately installed component bundle chosen for the launch. Since 2026-10-23 nothing is chosen
before a launch, and it answers null; a component comes from `EngineHost.requestComponent(name)`
while the game runs.

`EngineHost.fail(message)` (2026-09-25): ends the runtime because the engine
could not start, and the launch screen shows `message` with Report a problem,
the same end as a core whose `onCreate` throws. For an engine that learns of
the failure only after `onCreate` returned (Godot 4.0 and 4.1 start inside the
fragment's view) and would otherwise show its own alert over a black screen.
Same compatibility rule as `restart`: fall back to `finish()` on an older host.
Since 2026-10-23 it is also how an engine that cannot go on mid-game ends
(OpenBGI's script reaching an instruction it does not run yet, Droidtop/tracker#604),
rather than ending as if the game had quit: the person gets the reason and Report.
The isolated runtime says "The game stopped:" before the message once a frame
has reached the screen, "Core startup failed:" before that; the in-process
runtime cannot tell the two apart and always says the latter.

`EngineHost.restart(arguments)` and `EngineHost.restartArguments()`
(2026-09-18): ends the runtime and starts the same game again in a fresh
process, for an engine whose game asks to be restarted and which cannot be
de-initialised in place. Godot reaches it two ways: a game's
`OS.set_restart_on_exit(true, args)` ends in `Main::cleanup` calling
`create_instance(args)`, which arrives at `GodotHost.onNewGodotInstanceRequested`;
the engine's own restart arrives at `onGodotRestartRequested`. Upstream's app
answers both by killing its process and relaunching, the first with `args` as
the new instance's command line. So the arguments travel: the next run's
core reads them from `restartArguments()` (empty on any other launch). The
runtime finishes with a result the launch screen beneath it recognises, the
launch screen waits for the old runtime process to go, and plans the launch
again from the same intent. A core that may run on an older Enginehost
catches `IncompatibleClassChangeError` (the call fails to link as
`NoSuchMethodError` when the host's interface does not declare it) and falls
back to `finish()`: the game closes
instead of sitting on a dead screen. Cores that run as a bundled Activity
(`runtimeTransport: activity`) have no host object and are not covered by
this.

## Controller input for android-activity cores

Enginehost owns the controller map: a set of actions per engine, a global
binding for each, and per-engine overrides, all edited in Enginehost's
controller settings. A core never hardcodes what a pad button does.

The action ids are the engine's own, in the engine's own vocabulary, because
that is what a core has to translate and what a person has to recognise:
`rgss_c` and `rgss_b` for RPG Maker XP/VX/VX Ace, `easyrpg_decision` and
`easyrpg_shift` for 2000/2003, `mvmz_ok` and `mvmz_pageup` for MV/MZ, Ren'Py's
`pad_a` and `pad_leftx`, Godot's `joy_a` and `joy_axis_left_x`, CatSystem2's
`cs2_skip_mode` and `cs2_message_log`, CMVS's `cmvs_confirm` and
`cmvs_history_mode`, LÖVE's `love_a` and `love_leftx` (its `love.gamepad`
names). Adventure Game Studio has no pad model, but its inputs have names:
`ags_mouse_left` (`eMouseLeft`), `ags_wheel_north`, `ags_key_escape`
(`eKeyEscape`), with `ags_pointer_x`/`ags_pointer_y` steering the cursor, and
the core turns each into that click, key or movement. An engine with no input model of its own (KiriKiri,
Buriko, Flash, HTML) takes the shared fallback set: `up`, `down`, `left`,
`right`, `confirm`, `cancel`, `menu`, `skip`, `auto`, `history`, `quick_save`,
`quick_load`, `page_previous`, `page_next`, the stick axes and the triggers.

CMVS is the engine whose ids map one to one onto numbered functions in the
original, so they are listed in full. `KEY_FUNCTION_nn` is the section of the
game's own `key.cfg`; the defaults are the engine's factory bank
(`[KEY_FUNCTION_51..74]`, what its config tool restores).

| id | function | engine name |
| --- | --- | --- |
| `cmvs_confirm` | 01 | 決定 Confirm |
| `cmvs_cancel` | 02 | キャンセル Cancel |
| `cmvs_cursor_up` | 03 | カーソル↑ Cursor up |
| `cmvs_cursor_down` | 04 | カーソル↓ Cursor down |
| `cmvs_cursor_left` | 05 | カーソル← Cursor left |
| `cmvs_cursor_right` | 06 | カーソル→ Cursor right |
| `cmvs_hide_message_window` | 07 | メッセージウィンドウ消去 Hide message window |
| `cmvs_forced_skip` | 08 | 強制スキップ Forced skip (held) |
| `cmvs_auto_advance` | 10 | 自動送り Auto advance |
| `cmvs_history_mode` | 11 | 履歴モード History mode |
| `cmvs_replay_voice` | 12 | 音声再生 Replay voice |
| `cmvs_quick_save` | 13 | クイックセーブ Quick save |
| `cmvs_quick_load` | 14 | クイックロード Quick load |
| `cmvs_popup_menu` | 15 | ポップアップメニュー Popup menu |
| `cmvs_history_up` | 16 | 履歴アップ History up |
| `cmvs_history_down` | 17 | 履歴ダウン History down |
| `cmvs_config_screen` | 18 | 設定画面 Config screen |
| `cmvs_save_screen` | 19 | セーブ画面 Save screen |
| `cmvs_load_screen` | 20 | ロード画面 Load screen |
| `cmvs_extended_advance` | 21 | 拡張メッセージ送り Extended message advance |
| `left_x`, `left_y` | 03-06 | the analogue form, the engine's own joypad direction codes 8-11 |

Four functions are deliberately not ids. **09 既読スキップ** read-text skip
needs read flags the engine does not keep, so it could only behave as 08 and
one of the two is enough. **22 Outerの中央移動** re-centres a desktop window,
which does not exist here. **23/24 メニュー増減** are 03/04 in a value context
-- the original's own factory map binds all four to Up and Down -- so
`cmvs_cursor_up` and `cmvs_cursor_down` drive them, and a core should treat
them as one input, exactly as the engine does.

The set is chosen by engine family, except that `rpgmaker` names three
unrelated runtimes, so its sets are chosen by family and context together:
`rpgmaker/xp`, `rpgmaker/vx`, `rpgmaker/vxace` are RGSS; `rpgmaker/2000` and
`rpgmaker/2003` are EasyRPG; `rpgmaker/mv` and `rpgmaker/mz` are the web
runtime.

Because the ids differ per engine, a core and a host may disagree about the
set without either being broken, and both directions degrade the same way: an
id a core does not know is ignored, and an id a core expects but does not
receive is read exactly as `{"type":"none"}`. So an older host paired with a
newer core loses the new actions rather than misfiring them, and a newer host
paired with an older core sends actions the core drops.

Every action ships with a real default binding. `{"type":"none"}` is something
a person chooses, never something Enginehost hands them.

### The host menu shortcut is reserved

One button combination never reaches an engine: the host menu shortcut, Select
+ Start out of the box and changeable by the person in Settings, Controller.
Enginehost sees every pad event before the engine does, in both core shapes,
and takes that combination for its own in-game menu. This is not something a
core opts into or can refuse, and it holds whether or not bypass is on.

What a core sees: the press that completes the combination never arrives, nor
does anything done with those buttons until they are all released. If one of
them had already been pressed and delivered before the combination completed,
the host sends that button's release, so a core is never left holding a
button down. No core code changes; a core that binds Select or Start simply
finds that pressing both together belongs to the host.

### Engines that handle the controller themselves

Ren'Py, Godot, LÖVE and EasyRPG have controller support of their own worth using, so
their scopes carry a **Bypass controller mappings** toggle, on by default.
While it is on the runtime intent carries **no** `CONTROLLER_BINDINGS` extra at
all: the absence of the extra is the contract, and a core that finds no map
behaves exactly as it does on an older host that never sent one. Their default
maps are the engine's own defaults, so turning bypass off changes nothing until
the person actually remaps something.

A core never needs to read the toggle: it reads the extra, or finds it
missing.

Plugin-api cores receive the mapped actions through `onControllerEvent`.
Cores on the android-activity transport receive the resolved map as the
`dev.enginehost.runtime.CONTROLLER_BINDINGS` extra, a JSON object from action
id to binding, one of

- `{"type":"key","code":<KeyEvent code>}`
- `{"type":"axis","axis":<MotionEvent axis>,"direction":-1|0|1}`
- `{"type":"none"}`

Such a core matches incoming pad events against that map and translates each
action into whatever its engine understands (a keyboard key, an engine input, a
touch); the translation from action to engine input is the core's, the choice
of button is the person's.

A digital action bound to an axis with direction `-1` or `+1` fires when that
axis crosses the core's threshold in that sign and releases when it comes
back, so the four directions can live on a stick or on the hat
(`AXIS_HAT_X`/`AXIS_HAT_Y`) as easily as on the d-pad's keys. Direction `0` is
an analogue binding: the axis is read whole, as the stick axes are.

`{"type":"none"}` means the action is bound to nothing, and it is a binding
like any other rather than a missing entry -- every action appears in the map
whatever its binding. A core treats a `none` action as an input the person
will never send: an engine feature driven entirely by such actions (KiriKiri's
pointer emulation on `left_x`/`left_y`, say) is off, and nothing raw is read in
its place.

## `hostType` on v1 bundles (decided 2026-09-11, later work)

A v1 bundle is not Android by definition, only by habit: what makes today's
bundles Android is the wrapper (dex, Activities, `runtime.apk`). The manifest
gains an optional `hostType` field naming the host a bundle's wrapper is
written for -- `"android"` today; a future `"linux"` value would name a v1
bundle whose wrapper is a native or scripted process for a mobile-Linux host.

- **Unspecified:** the host detects it from what the bundle carries (`dexFiles`
  and an `entrypoint` class mean `android`; a payload under `native/linux-*`
  with the host-process entry the Linux host defines means `linux`), and
  records the detected value on the installed bundle.
- **Override:** the installer accepts an explicit `hostType` in the manifest
  over detection, and a per-install override for the odd bundle detection gets
  wrong; the trust screen shows the resolved value with its origin
  ("declared" / "detected" / "overridden").
- **Rule:** a host only offers and installs bundles whose `hostType` it can
  run; the catalog carries the field so a Linux host never downloads an
  Android bundle by mistake. v2 bundles have no `hostType`: their manifest
  lists platforms instead.

This is what lets v1 grow non-Android cores later without a second manifest
format; v2 below is the portable-core route for engines that do not need a
wrapper at all.

## Bundle format v2: portable engine bundles (later work, decided 2026-09-11)

Everything above describes **v1**: an Android bundle (dex, an entry class on the
Android core API, often a bundled Activity, `runtime.apk`, `lib/<abi>`). v1
stays. It is the right shape for engines that genuinely need Android glue of
their own, and nothing here deprecates it.

**v2 is a second core type, platform-neutral.** The direction (user,
2026-09-11): shift wrapper code into the Enginehost mainline whenever possible,
so a bundle can be an engine core and nothing else, and the same bundle can one
day run under a mobile-Linux host.

- **What a v2 bundle carries:** the engine core as native code with no
  platform API inside it, one payload directory per platform and architecture
  (`native/android-arm64-v8a/`, `native/android-x86_64/`, later
  `native/linux-x86_64/`, `native/linux-aarch64/`), engine data the core needs
  (fonts, shaders, default tables), and a manifest that describes the core
  once (`formatVersion: 2`, capabilities and contexts exactly as v1, the
  host-interface version it speaks, the platforms it ships).
- **What the host provides, per platform:** the glue that today lives in each
  v1 wrapper: a drawing surface and frame pacing, controller and touch input
  already translated through the host's controller map, audio output, the
  game folder as a read-only file tree with archive mounting helpers, the
  per-game save directory, config and options, logging, the in-game menu
  hotkey and the trust/verify path. The Android host implements this once;
  a Linux host would implement the same interface once.
- **The core-facing interface:** a small, versioned C ABI (the contract is the
  header, shipped with the host and vendored by cores): create/destroy,
  frame step, input events, audio callback, save/load hooks, and the
  capability/verdict reporting (SUPPORTED, quirks, UNSUPPORTED_*,
  REQUIRES_WINE) so a core says what it can run before it runs it.
- **Migration rule:** a v1 core becomes v2 by deleting its wrapper, not by
  rewriting its core. Where a v1 wrapper holds behaviour every engine needs
  (pointer emulation, backlog UI hooks, save-slot UI, font fallback,
  encoding conversion, archive mounts), that behaviour moves into the host
  mainline first, as a service any core type can use.
- **Sequencing:** later work. Nothing in v1 changes now. The prerequisites
  are: the core-vs-wrapper audit per core (which code is Android, which is
  engine), the host services listed above extracted from wrappers that
  already carry them (KiriKiri's pointer and navigation layer is the first
  candidate), and then the v2 manifest and loader beside the v1 ones. The
  catalog lists both types; the host picks the v2 payload for its platform
  and architecture the way it picks `lib/<abi>` today.

## Sandboxing and the core contract (2026-09-25, later work)

Engine sandbox layer 2 (`docs/engine-sandbox.md`) runs a v1 core's
`:runtime` under `android:isolatedProcess="true"` with the host brokering
all file access. That needs a bundle-manifest signal, since not every
core's native code has been audited or given the file-broker callback
seam layer 2 requires (`docs/engine-sandbox.md`'s per-plugin-shape audit):
a v1 bundle will gain an optional per-capability or per-bundle field
naming that the core's `:runtime` may be started isolated (the exact
name and shape, e.g. `"isolatable": true`, is decided when the first
core lands it — see `docs/engine-sandbox.md`'s "first milestone").
Additive and versioned the same way `hostType` is above: an older host
that does not understand the field ignores it and keeps using the
unisolated path, and a core that omits it is never asked to run
isolated. Not present in any shipped bundle yet; recorded here so the
field, when it lands, matches the shape decided in `docs/engine-sandbox.md`
rather than being improvised in the same change that adds it.


## `writesGameFolder` (2026-10-10)

Optional, boolean, default `false`, beside `isolatable`. `true` says the
engine writes into the game folder itself, as its desktop original does
(saves or settings beside the game). It only matters to an isolated launch:
the game folder's broker is writable for such a bundle and read-only for
every other (`docs/engine-sandbox.md`, "Saves beside the game"). The same holds
for a locked-down launch (`lockdown`, below), so a core that saves beside the
game declares it before it declares `lockdown`. An unconfined launch, and a host
older than this field, ignore it.

## `gameWrites` (2026-10-23)

Optional, array of strings, default empty. Each names a sub-folder of the game folder the
engine writes, relative to it (`"game/saves"`, `"game/cache"`). Under the lockdown only these
are writable, and the rest of the game folder stays read-only (owner 2026-10-23, in place of
`writesGameFolder` for the whole folder; `docs/engine-sandbox.md`, "Ren'Py under lockdown").
A path that is absolute, empty, or has `.`, `..` or an empty segment is refused, and so is
the bundle. `writesGameFolder: true` still makes the whole folder writable. A host older than
this field ignores it.

## `unpack` (2026-10-23)

Optional object: a runtime the core keeps packed in its resource APK, which Enginehost
unpacks for it. `{"apk": "runtime.apk", "asset": "assets/private.mp3", "stampFile":
"private.version", "stamp": "<the packed engine's identity>"}`. `apk` is one of the bundle's
`resourceApks`; `asset` is a tar.gz inside it (plain files and folders only: a link, device or a
path with `.` or `..` refuses it); `stamp` is written to `stampFile` in the unpacked folder,
which is how the core recognises the runtime as already unpacked (RAPT's `private.version`).

Enginehost unpacks it, before the core's code loads and before any lockdown, into
`files/core-runtime/runtime-<bundleId>--<archive digest>`, one folder per installed build. It
records the stamps of every file (size, inode, change and modification times) and compares them
on every launch: any file added, removed or written unpacks it again from the verified bundle.
The folder is passed to the core as `dev.enginehost.runtime.RUNTIME_FOLDER` and, under the
lockdown, is read-only to it (owner, 2026-10-23: the runtime is part of the core; the host
unpacks it and the core reads it). Removing the build removes the folder. A host older than this
field ignores it, and the core unpacks itself as before.

## `lockdown` (2026-10-17)

Optional, boolean, default `false`, on either transport. `true` says the core
runs correctly under the process lockdown: the sandbox library confining
`:runtime` before the core's code loads, with every file through Enginehost's
broker, no sockets and no other programs (`docs/engine-sandbox.md`, "The lockdown
is how a core that cannot be isolated is sandboxed"). A launch of such a core is
always locked down, and fails rather than run unconfined. Such a core counts as
sandboxed: `isolatable` or `lockdown` spares it the every-launch "Run
unsandboxed?" sheet and the install warning. A core sets it only after its rig
check shows no refused path. A host older than this field ignores it, and asks
as before.

## `runtimeTransport: android-activity` is deprecated for official cores (2026-09-27)

Sandbox layer 2 (`docs/engine-sandbox.md`, "Single transport") only fits
`runtimeTransport: plugin`: an isolated Android service must be a `<service>`,
never an `<activity>`, so a bundle whose own Activity is what the OS starts
and resumes cannot be isolated no matter what else is built. The owner's
decision is not a second isolation mechanism for this transport -- it is
that official cores stop using it. Each `android-activity` official core
(currently `enginehost-love2d-plugin`, `enginehost-kirikiri-plugin`,
`enginehost-nscripter-plugin`, `enginehost-ags-plugin`,
`enginehost-rpgmaker-mkxp-z-plugin`, and Godot, pending its own audit) is
migrated onto `runtimeTransport: plugin` via a per-engine-family adapter
(`docs/engine-sandbox.md`'s "The adapter: an `SdlEnginePlugin` shim" for the
SDL family) that presents the engine's existing native code with the
interface it already expects, backed by a host-owned Activity instead of its
own. `runtimeTransport: android-activity` itself is not removed from this
schema in this pass -- existing shipped bundles on it still load exactly as
before, and it is not rejected at install or launch time -- but no *new*
official core should be authored against it, and each existing one is
migrated in the order `docs/engine-sandbox.md`'s roadmap item 6 now gives.

**Sandbox support (`isolatable: true`) is a mandatory goal for every official
core**, not an opt-in some cores get and others skip -- it is what
"official plugin" means once each engine's migration and file-access audit
is rig-confirmed. The per-launch "Run unsandboxed?" warning
(`docs/engine-sandbox.md`, "The sandbox is on by default") is unchanged by
this decision and keeps showing, on every launch, for every core that has
not yet reached `isolatable: true` -- it is not weakened, delayed, or made
conditional on a migration being "in progress."

**Running unsandboxed for custom/third-party cores is an option the owner
raised, not a decision, and nothing in this pass builds it.** A third-party
core that cannot or does not migrate has no sandbox-off switch today, and
`EngineBundlePackage`'s existing validation
(`isolatable requires runtimeTransport: plugin`) is unchanged. If a
deliberate, user-facing way to run such a core unsandboxed is ever built,
it needs its own owner decision, its own pass of this document, and is out
of scope here.
