# Launch, trust chain and engine sandbox review, 2026-09-24

Scope: Enginehost `main`. The exported entry points (`dev.enginehost.LAUNCH`
and the other exported components), plugin signing and the trust chain
(pinned origin keys, the official root, the developer key), bundle install
and launch-time verification, the release/debug split, and the sandbox the
owner asked for: engine code gets no network and no arbitrary file access.
The morning audit (droidtop `docs/audit-2026-09-24/enginehost-host.md`, S-1
to S-8) was read first and each finding checked against the code as it
stood today; its IDs are cited where they apply. The sandbox design is
`docs/engine-sandbox.md`.

Nothing here was run on a device. Each fix commit carries the rig steps
that would show it working.

Severity is what an attacker gets if the finding is used, not how likely
that is. "Engine code" is everything in the `:runtime` process: the
bundle's code and the game it runs (Ren'Py Python, MV/MZ JavaScript,
GDScript, RGSS Ruby).

## Findings

### H1: engine code had the app's network (High, fixed in part)

`:runtime` is the app's UID, so it held INTERNET. Any game script could
open connections, including a game a zero-permission app dropped into
shared storage and started through `LAUNCH`, which is open to every app by
design. Together with H2 that is read-anything, send-anywhere.

Fix, commit `f8ae94b`: `RuntimeSandbox` installs a seccomp filter in
`:runtime` before any engine code loads, refusing IP sockets, `io_uring`
and foreign-architecture system calls to every thread (TSYNC) and every
child process. Tested off-device only (the C file under gcc on x86_64
Linux). What it cannot stop is M4.

### H2: engine code has all-files access (High, open)

MANAGE_EXTERNAL_STORAGE belongs to the UID, and a seccomp filter cannot
read paths, so nothing in-process can take it from `:runtime`. A game
started through `LAUNCH` can read all of shared storage and write into it,
including into files the calling app created and can read back (morning
audit S-2). `LAUNCH` stays open by the owner's rule; the fix is the engine
under an isolated-process UID with Enginehost serving the game's files,
designed in `docs/engine-sandbox.md` (layer 2) and due with the v2 host
interface. Until then this is the largest open exposure.

Still open. `docs/engine-sandbox.md` now carries the per-plugin-shape audit
(what breaks under `isolatedProcess` for activity-transport plugins,
plugin-api-transport plugins, native `open`/`fopen` engines, WebView, and
saves) and the host file-broker design (`ParcelFileDescriptor`s over a
per-launch AIDL service, plus a native callback seam in each engine's own
file-access layer rather than an `LD_PRELOAD`/PLT hook), with a first
milestone scoped to CatSystem2. None of it is implemented yet -- this pass
is the design, not the fix.

### H3: a caller's inline config was written into the game folder (High, fixed)

A `LAUNCH` for a folder without `enginehost.json` writes one when detection
completes it, and it started from the caller's inline JSON, `options`
included. One call from any app could leave, for example, mkxp-z's
`customScript` in a person's game config, to run on every later launch,
including launches the person starts from Enginehost (audit S-2).

Fix, commit `2c6f98e`: only `engine`, `engineContext`, `engineVersion`,
`runtimeRequirements` and `title` are ever written from a caller, which is
all droidtop sends. The inline config still applies to its own launch.
Covered by `DetectedConfigTest`.

### M1: the debug build's install harness was open to every app (Medium, fixed)

The debug APK is published beside the release APK. In it,
`DebugBundleInstallActivity` was exported with no permission and
auto-approves what it installs, so any app on a device running it could
install and approve an official bundle and overwrite a person's Deny on
it (audit S-1). `BundleInstallActivity` sat in the main source set and was
exported in release too, refusing at runtime.

Fix, commit `bcfd8f3`: both are debug-only and require
`android.permission.DUMP`, which the adb shell holds and no installable app
can. The harness no longer overrides a Deny. droidtop uses neither action.

### M2: the developer key was good for every origin, over the network (Medium, fixed in part)

The developer key is root-certified, ships in every APK and matched every
origin, so a bundle signed with it could arrive in any repository's
release: listed in the catalog and offered as an update that replaces an
official build of the same ID (audit S-3).

Fix, commit `fef3a0c`: the catalog, its cache and every catalog-driven
install (including the background update) require the origin's own key.
The developer key is still accepted for a bundle a person installs from a
file, on release builds too, because that is the local-rebuild workflow,
and the trust screen still marks such builds. Remaining choice for the
owner: accept it on debug builds only, which would also stop a person
being talked into picking a developer-signed file on a release install.
Not done here because it changes a workflow deliberately built around the
key.

### M3: an approved bundle, or a game, can rewrite host trust state (Medium, open)

Engine code runs as the app's UID, so it can write
`shared_prefs/plugin-trust-v1.xml` (approve anything), the origin-key
store, the bundle stamps in `no_backup`, other bundles' files, and every
game's saves (audit S-4). Launch-time verification catches payload edits
but not these. There is no in-process fix; layer 2 of the sandbox removes
it, because an isolated UID cannot open this app's data at all. Until
then, approving a bundle means trusting it with the whole host, and a game
run through it gets the same.

### M4: what the network filter leaves open (Medium, open)

- DNS goes through netd over a Unix socket, so engine code can still
  resolve made-up names and leak data in them.
- Engine code holds a Context: it can ask DownloadManager to fetch a URL
  (the system checks the app's INTERNET, which it has), send an Intent with
  a URL to a browser, or call other apps' exported services.
- If the filter cannot be installed (32-bit x86, a refusing kernel), the
  game runs without it and the log says so. This fallback was chosen
  because it could not be checked on a device; `docs/engine-sandbox.md` says
  where to change it.

All three go away under layer 2 (no Context with the app's permissions, no
`inet` group).

### L1: the report window's host check matched suffixes (Low, fixed)

`ProblemReportFormActivity` kept any host ending in `github.com` inside the
WebView where the person signs in, so `evilgithub.com` passed (audit S-6).
Fix, commit `83073fa`: https on `github.com`, `githubusercontent.com` and
their subdomains only.

### L2: one seed, no key rotation (Low for this review, open)

Checked against the code: `PluginOriginKeyStore.importCustom` refuses a
changed key and nothing defines a rotation; `official_plugin_root_key.json`
holds one root; built-in keys cannot be replaced (audit S-3). This is a
recovery problem more than an exposure: a compromised repository key
cannot be replaced without an APK release that changes
`default_plugin_keys.json`. Needs a rotation record and room for more than
one root; not attempted here.

### L3: a denied bundle still resolves; one pending launch for all (Low, open)

`PluginResolver.resolve` ignores trust, so a denied or pending bundle can
win over an approved one and the launch detours to the trust screen.
`PendingPluginLaunchStore.consumeFor` returns a pending launch with no
bundle ID to whichever bundle is approved next, so approving an unrelated
bundle can start a game another app asked for earlier (audit R-4). The
person is on the trust screen either way; the effect is a surprising
launch, not a bypass.

### L4: the published debug build is debuggable (Low, open)

The debug APK is signed with the same key as the release APK, so either
installs over the other, and a debuggable install gives anyone with adb
`run-as` over the trust store and the bundles. That is the point of a
rig build; the exposure is a person installing it by mistake. Keeping it a
CI artifact, not a release asset, would close that; it is published on
purpose for the rigs, so it stays the owner's call.

### Informational

- `CapabilityProvider` lists installed bundles to any app. Advisory by
  design; it carries nothing a bundle's own release does not.
- `autoinstallPlugin` lets any `LAUNCH` caller start a catalog download
  and install. Approval still gates execution.
- Payload file modes (audit S-8): the installed tree ends with no write
  bits for anyone and read/execute for all, inside a 0700 data directory;
  no exposure.
- The MV/MZ plugin's `allowNetwork` option can no longer do anything under
  the network filter. It belongs removed in that plugin's repository.

## Checked and sound

- Install: the manifest signature is verified before any payload is
  extracted; links, devices, absolute paths, `..`, duplicates, unsigned
  entries and out-of-order entries are refused; sizes, modes and each
  file's SHA-256 match the signed manifest, and the aggregate digest binds
  the ordered payload; the tree is staged privately and renamed in whole;
  the signer must match the origin's pinned key; a same-ID bundle replaces
  another only as a strictly newer build from the same origin.
- Approval is bound to bundle ID, archive digest and signer, so a
  replacement arrives unapproved.
- Launch rechecks the manifest signature, the pin, the install record and
  every payload file's stamp (rehashing on any change), and refuses files
  the manifest does not sign, before any bundle code loads. Both runtime
  shapes (`RuntimeActivity`, `EnginehostComponentFactory`) check approval
  first.
- Built-in keys and the developer key must be certified by the official
  root before they are trusted.
