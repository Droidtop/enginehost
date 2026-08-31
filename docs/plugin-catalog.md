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

Every applicable release contains one or more `*.enginehost.tar.xz` engine
bundles, one `enginehost-release.json` browsing envelope conforming to
[`plugin-release.schema.json`](plugin-release.schema.json), and normal release
notes, source revisions, license notices, and upstream attribution.

The envelope contains base64 copies of each bundle's internally signed manifest
and signature. Enginehost verifies those copies against the pinned repository
key before showing compatibility information. The downloaded archive is then
verified independently; the envelope is never sufficient to install code.

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
