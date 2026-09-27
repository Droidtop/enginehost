# LaunchEntryActivity: closing the confused deputy without closing LAUNCH, 2026-09-27

Scope: `dev.enginehost.LAUNCH` (`LaunchEntryActivity`, `LaunchActivity`), the
gap named `docs/security/2026-09-24-launch-trust-sandbox.md` H2 and its
"LAUNCH stays open by the owner's rule" note, and the owner decision of
2026-09-24: "Any app can make enginehost run a game, that's intentional."

## The problem

`LaunchEntryActivity` is `exported="true"` with no permission. Any
zero-permission app on the device can fire `dev.enginehost.LAUNCH` with a
`path` extra naming any folder it can read (which, before the Layer 2
sandbox lands for a given plugin, is most of shared storage once the game
starts -- H2) and Enginehost will read that folder's config, resolve a
plugin for it and run it, with no indication to the person that anything
happened until a game they did not start is already on screen. That is a
classic confused-deputy shape: Enginehost has more authority over the
device (all-files access, in the unsandboxed case) than the calling app
does, and the calling app spends that authority through Enginehost without
the person's knowledge.

## Why a signature/knownSigner permission does not fit here

The obvious close is a custom permission on `LaunchEntryActivity`
(`android:permission`, `protectionLevel="signature|knownSigner"`,
`android:knownCerts` naming droidtop's certificate) so only droidtop can
start it. Two things rule it out:

1. **`knownSigner` needs API 31.** Enginehost's `minSdk` is 26
   (`app/build.gradle.kts:18`), and the BlueStacks rig -- the device this
   host is actually tested on today -- is Android 9
   (`docs/coordination` device README, "RIG ERA"). Below API 31 the
   `knownSigner` clause is simply not evaluated, which leaves plain
   `signature` (exact same signing certificate) as the enforced rule.
   droidtop and Enginehost are signed with different certificates by
   design (`docs/plugin-catalog.md`, "Official keys form one hierarchy" --
   each app gets its own operational subkey). A plain `signature`
   permission would therefore lock EVERY caller out, droidtop included, on
   every device below API 31 -- the opposite of "without breaking
   droidtop's legitimate LAUNCH intent."
2. **A manifest permission is enforced by the platform before any app code
   runs.** A caller that lacks the permission does not reach
   `LaunchEntryActivity.onCreate` at all; there is no way to fall back to a
   confirmation prompt for a caller the permission rejects. That would
   make `LAUNCH` closed to everyone but droidtop outright, which is a
   direct reversal of the owner's 2026-09-24 decision that any app can ask
   Enginehost to run a game. Reversing a recorded decision like that is
   the owner's call, not something to do silently inside a "close the
   confused deputy" task.

So a permission gate is out on both compatibility and design-authority
grounds, not just implementation cost.

## Why an ordinary caller-identity check does not work either

Android gives an exported `Activity` no reliable, unspoofable way to learn
who started it from a plain `startActivity`/`am start` call:
`getCallingPackage()` only populates for `startActivityForResult`, which
droidtop's caller does not use and this task cannot require it to (a
droidtop-side change is out of scope here: "Enginehost is separate from
droidtop"). `getReferrer()` looks tempting, but its own contract says it
prefers `Intent.EXTRA_REFERRER`/`EXTRA_REFERRER_NAME` when either is
present on the received intent -- and those are ordinary extras any caller
can set on the intent it sends. A malicious app can simply claim to be
`android-app://dev.droidtop.app` and `getReferrer()` will repeat that claim
back unchallenged.

## What actually ships

`LaunchEntryActivity` still accepts `LAUNCH` from any caller -- the door
stays open, nothing is rejected. What changed is what happens next:

- `LaunchEntryActivity.unverifiedCallerLabel()` reads the intent it
  received directly. If that intent itself carries `EXTRA_REFERRER` or
  `EXTRA_REFERRER_NAME`, the caller supplied its own claim and is treated
  as unverifiable regardless of what it claims (this is exactly the
  spoofing vector above, so it is never trusted). Only when NEITHER extra
  is present does it read `referrer`, which in that case can only have
  come from Android's own record of the real calling activity's package --
  not from anything the caller's intent said about itself.
- That package name is then checked against `TrustedCallers.isDroidtop`,
  which requires both the exact package (`dev.droidtop.app`) and a
  matching APK signing certificate read live from `PackageManager`
  (`GET_SIGNING_CERTIFICATES` on API 28+, `GET_SIGNATURES` below it) --
  not anything the caller asserts. The pinned certificate SHA-256
  (`38b1ef4bc47fa8cd62ffcbb43fa85d7f450e9c2d4a8b690776d2218b23f0749f`) was
  extracted 2026-09-27 from both assets of `Droidtop/droidtop`'s `latest`
  release (`droidtop-latest.apk` and `droidtop-latest-debug.apk`, which
  share one signer), by parsing the APK Signing Block v2 directly (no
  `apksigner` was available in this environment).
- A verified droidtop caller runs exactly as before: no new screen, no
  extra tap.
- Anything else -- a real referrer naming some other package, a caller
  that supplied its own (disbelieved) referrer extras, or no referrer at
  all (the common shape for `adb shell am start`, and for any caller not
  started from another activity's context) -- carries a label into
  `LaunchActivity`, which shows a new consent sheet
  (`offerExternalLaunchConsent`) naming the game and the caller (or "An
  app" when no real name is known) before the plugin resolves and runs.
  Cancel is first and focused, matching `offerSandboxConsent`'s existing
  "ask every time, remember nothing" shape; nothing is stored, so the next
  launch from the same unverified caller asks again.

## What this does and does not close

- **Closed:** an app dropping a folder somewhere Enginehost can read and
  silently making Enginehost run it, unnoticed, is no longer possible --
  the person sees a plain "launch this game?" prompt naming the caller
  before anything runs, for every caller this cannot verify as droidtop.
- **Not closed:** everything H2 already named. A person who taps Launch
  extends the same trust an approved bundle already gets (M3); this
  screen does not, and cannot, narrow what the plugin can do once running.
  It also does nothing for `autoinstallPlugin`, which can still start a
  catalog download before the person answers this prompt; approval to run
  still gates execution as before.
- **Open decision for the owner:** whether "any app can make Enginehost
  run a game" (2026-09-24) still means "silently" for non-droidtop
  callers, or whether this per-launch prompt is the intended shape going
  forward. This change assumes the latter (fail toward asking, not toward
  silent execution, for a gap this severity) but does not itself settle
  the standing decision -- flagged here for confirmation rather than
  decided unilaterally.

## Needs a rig check

Install the built debug APK on BlueStacks (RIG ERA / `device/wadb`) and,
with a droidtop build installed and already able to reach `LAUNCH` (the
existing dq-pluginui items exercise this path), confirm:

1. A game folder launched the same way droidtop already does it (an actual
   droidtop `LAUNCH` from its own UI, not `adb shell am start`) still
   starts straight through, no new screen, same as before this change.
2. `adb shell am start -a dev.enginehost.LAUNCH --es path "'<folder>'"`
   (the rig's own documented launch command, which has no real activity
   referrer) now stops at the new "Launch this game?" sheet naming "An
   app" and the game's title, with Cancel focused; tapping Launch runs the
   game exactly as before, tapping Cancel or Back returns to wherever the
   screen goes on cancel today.
3. Repeat step 2's `am start` launch a second time after Cancel: the sheet
   appears again (nothing was remembered).
