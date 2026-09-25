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
5. **New official repositories reach devices without an app release.** The
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
- Replace an official origin's key: **no.** Compiled-in keys win over the
  index, and a delivered key must carry the root's certificate for that
  origin.
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

For an official repository the registration pins the fingerprint the root
certified; a release signed by another key is left out of the index and
logged. For a third party, a new key is a reviewed change to their list
entry, and devices that pinned the old key refuse the new releases until the
person removes and re-adds the repository (there is no in-band rotation,
exactly as for community repositories). **Accepted; rotation is manual.**

### T8 — Single-ABI or unverifiable builds reaching devices

The generator verifies every bundle's manifest signature and key on every
run, and admits a release only when each bundle that carries native
libraries carries both `arm64-v8a` and `x86_64`. The ABI rule applies when a
release is admitted: a release already in the index with the same tag and
envelope stays until its tag is published again, so builds devices are
already on do not vanish. **Mitigated going forward.**

### T9 — A delivered official key for an origin the device knows otherwise

A certified key never replaces a compiled-in one. A person who had added an
official repository by URL (a Community pin) before it was registered has
that pin replaced by the certified key, since the root's certificate is a
stronger statement than first use; a Community pin that disagrees with the
certified key is dropped for the same reason. **Mitigated.**

### Not covered

- Revocation of a certified official key. Same as today for compiled-in
  keys: a new Enginehost build is the only way.
- Review of third-party code. Out of scope by design; listing is identity.
- GitHub itself (account takeover of a listed maintainer, raw.githubusercontent
  serving other bytes): the signatures still have to verify against keys the
  device pinned, which bounds what a takeover can do to what T3 describes.
