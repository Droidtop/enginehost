# Engine bundle catalogs and repository identity

Every engine repository uses its complete GitHub Releases history as its
catalog. Enginehost paginates published releases and caches their signed bundle
headers for offline browsing. Drafts are ignored and prereleases are opt-in.

Each repository must publish `enginehost-public-key.json` at its root:

```json
{
  "formatVersion": 1,
  "origin": "https://github.com/owner/enginehost-example-plugin",
  "algorithm": "SHA256withECDSA",
  "publicKeySpki": "BASE64_X509_SUBJECT_PUBLIC_KEY_INFO",
  "keySha256": "SHA256_OF_THE_DER_PUBLIC_KEY"
}
```

The key must be an ECDSA P-256 key. Adding a custom repository fetches this
file, verifies that its declared origin matches the repository being added,
and pins the fingerprint before reading any release. A different key for an
already-pinned origin is rejected. Default repositories and their keys are
compiled into Enginehost, so their network copy cannot replace host policy.

Official keys form one hierarchy. The offline Enginehost primary seed derives
an origin-scoped operational subkey for each repository, and the primary key
certifies that subkey's public identity. The primary key never signs engine
bundles directly. The Enginehost APK is likewise signed by its own
application-scoped operational subkey from the same primary seed.

Every applicable release contains one or more `*.enginehost.tar.xz` engine
bundles, one `enginehost-release.json` browsing envelope conforming to
[`plugin-release.schema.json`](plugin-release.schema.json), and normal release
notes, source revisions, license notices, and upstream attribution.

The envelope contains base64 copies of each bundle's internally signed manifest
and signature. Enginehost verifies those copies against the pinned repository
key before showing compatibility information. The downloaded archive is then
verified independently; the envelope is never sufficient to install code.

## Publishing: how a bundle becomes a release

CI builds and signs a bundle set on every push to a `plugin/**` branch, but a
workflow artifact is not a release, and promotion is deliberately not part of
the build. The gate is evidence, not a green run: a bundle is published only
once that exact build has installed and booted a real game on hardware.
Compiling, packaging and even verifying signatures prove nothing about
whether the runtime starts.

`scripts/promote-plugin-release.py` is the promotion step. It takes the
successful run, re-verifies every bundle offline against the key document
committed at that run's own commit, requires an `--evidence` statement of
what was seen on hardware (written verbatim into the release notes -- never
invented), assembles the release as a draft, and publishes it only when all
assets are up. Anything not yet proven stays a CI artifact.

## Updates

Within one bundle ID, `pluginVersion` orders builds: a release carrying the
same `bundleId` from the same origin with a strictly higher `pluginVersion`
is an update, and installing it replaces the older build in place. A
different bundle ID -- a new engine series, or a deliberate `-vN` bump -- is
a different bundle and coexists, exactly as before. A different origin
publishing an already-installed bundle ID is never an update; its signature
would not match the pinned key anyway.

Enginehost checks for updates at most once a day (on app open), by listing
the published releases of exactly those repositories that have a bundle
installed from them -- the same unauthenticated request as the catalog's
Refresh button. Nothing about the device, library or installed bundles is
sent. Offline or failed checks are silent and simply retried after the next
interval. The check has an off switch in settings, and turning it off stops
all automatic update traffic.

Optionally ("Install plugin updates automatically", off by default),
Enginehost downloads and installs such an update itself. This replaces
bytes, never trust: execution approval is bound to the exact archive digest
and signer, so an automatically installed update is unapproved until the
user approves that exact new archive -- the trust prompt appears before it
runs anything, exactly as for a manual install. Approval is never inherited
across an update, and there is no path that skips it.

The Enginehost APK itself follows the same pattern one level up: CI
publishes a rolling `latest` release whose `release-info.json` carries the
build's monotonic `versionCode` and APK digest, the app compares that number
with its own at most daily (plus a manual check in settings), and an update
is downloaded, digest-checked, and handed to the Android package installer.
Android enforces signing-key continuity and asks the user to confirm; a
normally-installed app cannot silently replace itself, and Enginehost does
not pretend otherwise.

## Retention and coexistence

Releases are assembled as drafts and published only after all assets are ready.
The project policy is never to delete a published engine-bundle release because
older games may require it. GitHub itself permits deletion, so this is a project
retention promise, not a property of GitHub.

Every bundle has a unique `bundleId`. Any number of compatible or historical
bundles can coexist in Enginehost-private storage, and the user can uninstall
ones they do not need. Installing a newer wrapper build does not silently
replace another bundle.

## Provenance, official status, and approval

- **Verified provenance** means the internal bundle signature matches the key
  pinned for the bundle's declared GitHub origin and every payload byte matches
  the signed manifest.
- **Official** means that origin/key pair is built into Enginehost.
- **Approved** means the user has allowed that exact bundle ID and signing-key
  identity to execute inside Enginehost's runtime process.

A valid signature proves who published bytes; it does not grant those bytes
Enginehost's permissions.
