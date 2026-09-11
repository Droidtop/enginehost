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

## Where the catalog comes from, and what it says when it cannot

Listing eleven repositories through GitHub's API costs eleven API requests
plus a download per release, and GitHub allows an unauthenticated address 60
requests an hour. A few refreshes from one address spend that, and every
origin then fails at once. So there are two paths to the same catalog, and
one mechanism (`CatalogRefresh`) chooses between them for every origin, for
the Refresh button and the scheduled update pass alike:

1. **The plugins index.** `plugins/index.json` in droidtop-platforms names
   every default origin's releases -- tag, stream, publication time, and each
   asset's name, URL, size and sha256 -- and is regenerated there by a
   workflow with a token, on a schedule and on a `plugin-published` dispatch
   from a plugin repository. Enginehost fetches that one file from
   `raw.githubusercontent.com`, which has no API allowance. Only a release
   envelope it does not already hold is downloaded afterwards, from the
   release asset host, which is not the API either; the envelope is checked
   against the sha256 the index published and its signed manifest against the
   key pinned for that origin, so the index can make the catalog stale but
   never wrong.
2. **The GitHub API**, as before, for any origin the index does not name
   (every custom repository) and for all of them when the index is missing or
   more than three days old. That path now sends `If-None-Match` with the
   ETag stored beside each cached catalog; GitHub's 304 costs no allowance at
   all, and "unchanged" is a real outcome rather than a re-download.

An origin's cached catalog is replaced only once a fetch has parsed and
verified, so a failed refresh leaves what was already there on screen. What a
failure does change is what the screen says. The reason is kept per origin
and shown instead of "Nothing published yet": the rate limit with the time
GitHub's `X-RateLimit-Reset` names ("GitHub rate limit, try again after
14:35."), any other HTTP status as itself, and an unreachable network as
itself. A 403 with allowance left is not a rate limit and does not claim to
be.

## Publishing: how a bundle becomes a release

CI builds and signs a bundle set on every push to a `plugin/**` branch, but a
workflow artifact is not a release, and promotion is deliberately not part of
the build. The gate is evidence, not a green run: a bundle is published only
once that exact build has installed and booted a real game on hardware.
Compiling, packaging and even verifying signatures prove nothing about
whether the runtime starts.

Every repository publishes on three streams, and the release envelope
(`enginehost-release.json`) names the stream in its `channel` field:

- `unstable`: every green build of a `plugin/**` branch, published by CI
  itself to the rolling pre-release `<line>-unstable`, replaced on each build.
  Nothing is claimed about it beyond "it built and signed".
- `testing`: a build a maintainer promoted (manual dispatch with
  `channel=testing`) because it runs real games but has not been lived with.
  Tag `<line>-testing`, marked pre-release.
- `stable`: promoted with `channel=stable` once the build has been used with
  real games on hardware. Tag `<line>`, a full release.

The person chooses the most adventurous stream they want in enginehost's
Settings (stable by default); the catalog and the update check offer that
stream and every steadier one, and the newest `pluginVersion` among them
wins. Envelopes without a `channel` are read as stable, or testing when
GitHub's pre-release flag is set, so releases from before streams existed
keep their meaning.

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
