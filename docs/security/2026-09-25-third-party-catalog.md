# Third-party repositories in the plugin catalog: threat model, 2026-09-25

Scope: how a plugin repository gets into droidtop-platforms' `plugins/index.json`
(the file every Enginehost reads before it asks GitHub anything), how
repositories that Droidtop does not run get listed there, and how Enginehost
offers those to a person and marks what they install. Written before the
implementation, which follows it; the documents that describe the result are
droidtop-platforms `plugins/README.md` and `docs/plugin-catalog.md`.

## What changes

Until now the index covered a hand-edited list (`plugins/origins.json`) that
had to equal Enginehost's compiled-in `DEFAULT_ORIGINS`. A new repository
meant editing both, and a new build reached devices only on the index's
six-hourly schedule, because the `plugin-published` dispatch existed in one
repository and the token it needs did not exist.

1. **Every Droidtop plugin repository announces each publish.** The last step
   of both publishing jobs (`publish`, `unstable`) sends a `plugin-published`
   repository dispatch to droidtop-platforms with the organisation secret
   `PLATFORMS_DISPATCH_TOKEN`. Without the secret the step logs that and
   succeeds.
2. **An announcement is a hint, never data.** The index generator reads only
   the repository name out of it, and only to decide what to look at. What
   enters the index is what the generator verified itself from the
   repository's releases: the signed manifests, the key that signed them, and
   the ABIs the bundles carry. A repository that validates and is not yet in
   `plugins/origins.json` is registered there by the same run; nobody edits
   the index or the registration list by hand.
3. **Third parties are listed, not trusted.** `plugins/third-party.json` names
   each third-party maintainer, the repositories they may register, and the
   public key(s) their bundles and registrations are signed with. Listing is
   a reviewed pull request to droidtop-platforms. A third party never holds a
   Droidtop token.
4. **Enginehost offers listed repositories in one step** ("Quick add
   third-party repositories" on the Plugins screen), behind a trust prompt
   that shows the maintainer and the key fingerprint, and marks their bundles
   **Third party** everywhere trust is shown. They are never Official.
5. **New official repositories, and new keys for existing ones, reach devices
   without an app release** (key changes: T7). The
   index carries each official origin's key document, including the
   certificate the offline Enginehost root key made for it. Enginehost adds an
   origin whose certificate verifies against the root compiled into the APK,
   exactly as if it had been compiled in.

## Assets

- **The official root key** (offline, droidtop-dev only). It certifies each
  official repository's signing key for exactly one origin. Its public half
  is compiled into Enginehost and copied into droidtop-platforms
  (`plugins/official-root-key.json`) so the generator can check the same
  certificates.
- **Each repository's signing key.** Official ones live in the repository's
  `ENGINEHOST_SIGNING_KEY_PEM` secret and are used only by the pinned
  reusable signing workflow. A third party's key is theirs.
- **droidtop-platforms `main`**: the index, the registration list, the
  third-party list, the root key copy.
- **`PLATFORMS_DISPATCH_TOKEN`**: fine-grained, `contents: write` on
  droidtop-platforms only (a repository dispatch needs `contents: write` on
  its target). Only in publish jobs, which run no build code.
- **A device's pins and approvals**: which key each origin is pinned to, and
  which exact archives the person approved to run.

## Trust levels on a device

| Level | What makes it | What the person sees | Removable |
| --- | --- | --- | --- |
| Official | Origin key certified by the Enginehost root for that exact origin, verified on the device (compiled in, or delivered in the index) | Official badge | No |
| Third party | Added through Quick add: the key listed in `third-party.json` AND the key the repository itself publishes agree, and the person accepted that fingerprint | Third party badge with the maintainer's name | Yes |
| Community | A repository URL the person typed (trust on first use of its published key) | Community badge | Yes |
| Developer build | Signed with the primary developer's key; files put on the device only | Its own warning badge | - |

Every level still needs the person to approve each exact archive before it
runs. Nothing in this design approves anything, and no level is inherited by
another: a third-party repository cannot become Official by being listed, by
anything in the index, or by a key document that claims an issuer, because
official status is only ever the device's own verification of the root's
signature.

## Threats

### T1 — A forged or replayed `plugin-published` dispatch

Anyone holding the token can send any payload. The generator uses the named
repository only to decide which repository to validate now; a repository it
would not accept on its schedule it does not accept on a dispatch, and a
dispatch naming a repository that has nothing new costs one index run. A
flood of dispatches is serialised by the workflow's concurrency group and
costs API allowance only. **Mitigated by design.**

### T2 — The dispatch token leaks

The token can push to droidtop-platforms directly, so its holder can rewrite
the index, the registration list and the third-party list. What that buys:

- Hide or delay releases, or point the index at stale ones. Devices still
  verify each envelope against the hash in the index and each manifest
  against the key pinned for its origin, so the index can make the catalog
  stale but never make it offer bytes the origin's key did not sign
  (unchanged from `2026-09-24-ci-supply-chain.md` L3).
- Make an origin Official: **no.** That needs the offline root key.
- Replace an official origin's key: **no.** A new official key needs the
  root's certificate for that origin with a higher serial than the one devices
  hold (T7, T10).
- Add a third-party maintainer of their own choosing to the list. Devices
  would then offer that repository in Quick add under the name the attacker
  wrote. The person must still accept the prompt (which says the repository
  is not Droidtop's), and then approve each plugin, which is marked Third
  party. This is the same exposure as the person typing the URL themselves.
  **Accepted residual risk**, and the reason the token must stay a
  fine-grained token on this one repository, only in publish jobs.
- Change a listed third party's key to their own. A device adding the
  repository afterwards compares the listed key with the key the repository
  itself publishes and refuses a mismatch; a device that already pinned the
  real key rejects every release signed otherwise. **Mitigated** by requiring
  two independent sources to agree.

### T3 — A listed third party publishes something malicious

Listing records who maintains a repository and which key signs it; it is not
a review of their code. Their bundles run with Enginehost's permissions once
approved (`2026-09-24-launch-trust-sandbox.md` says what that is). The
prompt, the badge and the per-archive approval exist so the person decides
with that in front of them. Delisting stops new devices being offered the
repository; devices that added it keep it until the person removes it, and
Enginehost shows such an origin as "no longer listed" in Quick add.
**Accepted; stated in the prompt.**

### T4 — A third party impersonates an official plugin

- **Origin**: every manifest names its origin and must be signed by the key
  pinned for that origin; a third party cannot sign for a Droidtop origin.
- **Organisation**: the generator refuses to list or register a repository
  owned by the Droidtop organisation as third party, and refuses a
  maintainer whose id or name claims to be Droidtop or Enginehost.
- **Bundle IDs**: `dev.enginehost.*` is the official namespace. The generator
  leaves out any third-party release whose bundle ID is in it, so a
  third-party build can never present itself as, or sit beside, an update to
  an official bundle. (A different origin publishing an installed bundle ID
  is never an update on the device either; that rule is unchanged.)

### T5 — Someone registers a repository they do not control

A third-party repository enters the index only when it publishes
`enginehost-registration.json` signed by one of its maintainer's listed keys
and naming that repository and that maintainer, and when its releases are
signed by that same key. Listing a repository under a maintainer without the
maintainer's key achieves nothing; a registration copied to another
repository names the wrong origin. **Mitigated.**

### T6 — Abuse of the third-party request path

Third parties cannot send a dispatch. Their repositories are looked at on
every scheduled run; for a faster pickup they open an issue with the
"Register a third-party plugin repository" form. The workflow reads the
issue from the event file (never by interpolating it into a shell), acts only
when the issue's author is the GitHub account listed for that repository's
maintainer, and then does exactly what the scheduled run does. Anyone else's
issue gets a comment and is closed. The cost of abuse is API allowance.
**Mitigated.**

### T7 — A repository changes its key

Added 2026-09-25, after the first implementation: keys change, and a new
official repository has to get a key in the first place. The rules:

- **Official keys carry a serial.** An origin's first key is serial 0 (the
  certificates made before serials existed are read as 0); a replacement is
  derived from the same seed at the next *generation* and certified with that
  generation as its serial. The serial is inside what the root signs
  (`enginehost-origin-key-v2` certificates), so nobody without the root can
  raise it.
- **The index takes a new official key only when it is root-certified for the
  origin and its serial is higher than the pinned one.** The dispatch or the
  repository's own releases only say "look"; the certificate decides. Every
  accepted key, the first registration included, is appended to
  `plugins/key-history.json` with its full certified document, the key it
  replaced and when; the generator's `--check` re-verifies every certificate
  in that file offline and that serials only rise, so the trail cannot be
  rewritten quietly into something the root did not sign.
- **Enginehost moves an official origin to a new key only on the same
  proof**: a root certificate for that origin with a higher serial than the
  key it holds (compiled in or learned). The key it replaces is kept as a
  superseded official key of that origin: plugins already installed and
  approved under it keep running, keep their Official badge and their
  approval, and are not prompted for again; updates signed by the new key are
  offered as updates in the usual way, each one approved as always. New
  installs and catalog entries must carry the current key.
- **Anything else is a different origin.** A key for an official origin that
  is not certified, or certified with a serial that is not higher, is refused
  in the index and on the device. For a Community or Third party origin, a
  repository that now publishes another key than the one pinned is shown as
  "key changed"; the person may review it, and accepting pins the new key as
  if the repository were added anew: plugins installed under the old key stop
  running until they are installed again under the new key and approved.
- **Third-party keys change only by an edit to `third-party.json`**, made by
  the user in a reviewed pull request. A third party's registration can only
  name a key their list entry already carries; a key change is recorded in
  the key history like any other. Devices re-pin a third-party origin only
  through the prompt above, and only to a key the list names.

**Mitigated**, with the residual below (T10).

### T8 — Single-ABI or unverifiable builds reaching devices

The generator verifies every bundle's manifest signature and key on every
run, and admits a release only when each bundle that carries native
libraries carries both `arm64-v8a` and `x86_64`. The ABI rule applies when a
release is admitted: a release already in the index with the same tag and
envelope stays until its tag is published again, so builds devices are
already on do not vanish. **Mitigated going forward.**

### T9 — A delivered official key for an origin the device knows otherwise

A certified key replaces a compiled-in or learned one only as a rotation
(T7: a higher serial). A person who had added an
official repository by URL (a Community pin) before it was registered has
that pin replaced by the certified key, since the root's certificate is a
stronger statement than first use; a Community pin that disagrees with the
certified key is dropped for the same reason. **Mitigated.**

### T10 — Rolling an official origin back to an old key

Someone who can write droidtop-platforms could put an older certified key
document back into the index, perhaps one whose private half has leaked.
The index generator refuses a serial that is not higher than the pinned one,
and Enginehost does too, independently of what the index says; a device that
has seen serial 2 never goes back to serial 1. A device that never saw the
newer key (a fresh install, or one that was offline through the rotation)
starts from its compiled-in key and accepts only higher serials from there.
**Mitigated**; a leaked key with the highest serial is T11.

### T11 — A leaked current key

The holder can sign bundles that verify as Official until the origin rotates.
Rotating (next generation, higher serial) moves every device that refreshes to
the new key, and releases signed by the old key stop being offered as new
installs; bundles already installed under the old key keep running, because
Enginehost cannot tell a leaked key's signature from a legitimate one.
**Accepted**; there is no revocation list (below).

### Not covered

- Revocation of a certified official key. A rotation stops a superseded key
  from signing anything new that devices accept, but bundles installed under
  it keep running; revoking those needs a new Enginehost build.
- Review of third-party code. Out of scope by design; listing is identity.
- GitHub itself (account takeover of a listed maintainer, raw.githubusercontent
  serving other bytes): the signatures still have to verify against keys the
  device pinned, which bounds what a takeover can do to what T3 describes.
