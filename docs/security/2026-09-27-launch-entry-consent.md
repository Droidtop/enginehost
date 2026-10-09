# LaunchEntryActivity: an allow/ask/block filter, not a caller gate, 2026-09-27

Scope: `dev.enginehost.LAUNCH` (`LaunchEntryActivity`, `LaunchActivity`), the
gap named `docs/security/2026-09-24-launch-trust-sandbox.md` H2's confused-
deputy half, and three owner decisions, in order:

1. 2026-09-24: "Any app can make enginehost run a game, that's intentional."
2. 2026-09-27 (morning): "Enginehost's primary focus is programmatic engine
   launches. The ENTIRE point is letting apps do that." -- overrode this
   document's first version, which added an unconditional per-launch
   consent sheet for any caller not verified as droidtop.
3. 2026-09-27 (revision, current): "A prompt is fine. It might be wise to
   have an allowlist/blocklist function to filter launches, that way a
   browser can't hijack it. Sane defaults, of course (blocklist filtering
   browsers and network tools, etc)." -- this is what ships.

## The model

Every caller of `LAUNCH` other than droidtop resolves to one of three
effective outcomes (`EffectiveAccess.forCaller`, `CallerAccess.kt`):

- **Allow**: the launch proceeds exactly as if it came from droidtop -- no
  screen, no delay.
- **Ask**: `LaunchActivity` shows a quick prompt (`offerCallerAccessPrompt`)
  naming the caller and the game, Cancel first and focused. "Always allow
  this app" and "Block this app" both persist (`CallerAccessStore`) and
  either continue the launch or cancel it; Cancel decides nothing and asks
  again next time.
- **Block**: the launch is refused with a plain reason
  (`showCallerBlocked`) -- "this looks like a browser", "this looks like a
  remote-access or automation tool", or nothing more specific for a
  person's own Block decision -- with "Allow this app" right there to
  undo it.

Resolution order: a person's own stored decision
(`CallerAccessStore.decisionFor`, ALLOW or BLOCK) always wins. With no
stored decision, `CallerDefaults` decides: a package that resolves as the
device's `ACTION_VIEW` `http`/`https` handler, or is on a short list of
major browsers' own package names (a fallback for one that is not
currently the default handler), or is a known terminal/SSH, remote-desktop
or device-automation package (Termux, TeamViewer, AnyDesk, VNC clients,
Tasker, MacroDroid, Automate -- there is no platform category for these,
so this is a maintained list, not a heuristic) starts **Blocked**.
Everything else starts **Ask**. `CallerAccessSettingsActivity` (Settings >
App launch access) lists every package with an explicit decision and lets
a person change or remove one, or add a decision for an app that has not
asked yet.

**Verified droidtop** (`TrustedCallers`, unchanged from this document's
first version) is never gated: `GameRunner.run`'s default `LaunchCaller`
is `Droidtop`, and every in-app launch (library, config editor test run,
trust-screen continuation) uses that default, so none of this applies to
anything already inside Enginehost.

**Caller identity** is exactly what the first version of this document
built and is unchanged: `LaunchEntryActivity` trusts only the referrer
Android itself supplied, never `Intent.EXTRA_REFERRER`/
`EXTRA_REFERRER_NAME` on the intent it received (either is an ordinary
extra any caller can set on its own intent, and `Activity.getReferrer()`
would otherwise repeat it back unchecked). No referrer at all -- the shape
`adb shell am start` takes, having no calling Activity to attribute -- and
an intent that supplies its own referrer extras both resolve to
`LaunchCaller.Unknown`, a single bucket keyed as `"(unrecognized caller)"`
that gets its own Allow/Ask/Block decision like any real package (default:
Ask, since neither browser/automation heuristic applies to it).

## Why not the two earlier shapes

- **A caller check with no filter (this document's first version)**: asked
  about every non-droidtop caller unconditionally, which the 2026-09-27
  override rejected outright -- programmatic launch from any app is the
  product, so gating on identity alone, even just to ask, was in the way
  for the overwhelmingly common case of a legitimate caller.
- **No gate at all (the override's own literal wording)**: closes nothing.
  A page loaded in any browser, or a script an automation app runs, could
  still trigger `LAUNCH` exactly as any other app can, with nothing in the
  way and nothing recorded. The revision restores a prompt specifically
  because that gap -- "a browser can't hijack it" -- was the point being
  protected against, not caller identity as such.

## What this does and does not close

- **Closed**: a browser or a known remote-access/automation tool cannot
  silently trigger `LAUNCH` -- it is blocked by default, with a reason,
  until a person explicitly allows it. A caller nobody has decided about
  yet gets one quick, dismissable prompt rather than silent execution.
- **Not closed**: this is app-identity policy, not the sandbox. A person
  who taps "Always allow" or a plugin that cannot run isolated (`Run
  unsandboxed?`, `offerSandboxConsent`, unchanged) still gets exactly the
  access `docs/security/2026-09-24-launch-trust-sandbox.md` H2 describes
  once the game is running. The real fix for that remains the sandbox
  itself (`docs/engine-sandbox.md`, Layer 2); this filter decides who may
  ask for a game to start, not what the game's own code can do once it
  has.
- **Not attempted**: distinguishing a browser's own navigation from a page
  script inside it, or verifying an automation app's specific action --
  both are the same caller identity either way, which is all `LAUNCH` (or
  any exported Android component) can ever see.

## Package visibility follow-up, build 234, 2026-09-28

The owner, updating to the build that shipped the model above: "most
applications don't appear on the list, and droidtop is not one of the four
that does, so it's now broken." Confirmed on the console: Settings > App
launch access > Add an app showed only four system packages (the launcher,
Music, Files, system Settings).

Cause: `AndroidManifest.xml` declared no `<queries>` and no
`QUERY_ALL_PACKAGES`. Since targetSdk 34 (Android 11+ package visibility,
enforced), `PackageManager` hides every app from Enginehost except itself,
a short always-visible system list, and anything it already interacts
with. Three things broke at once from that one gap:

- `CallerAccessSettingsActivity.offerAddCaller`'s
  `queryIntentActivities(LAUNCHER)` returned almost nothing to add.
- `CallerDefaults.resolvesHttpHandlerOn`'s `queryIntentActivities(ACTION_VIEW
  http)` returned nothing either, so no package could ever resolve as a
  browser by the platform check (the hardcoded package-name list still
  worked).
- `TrustedCallers.isDroidtop`'s `getPackageInfo(dev.droidtop.app,
  GET_SIGNING_CERTIFICATES)` threw `NameNotFoundException` for the same
  reason, so a real droidtop launch was never recognised as
  `LaunchCaller.Droidtop`. It fell to `LaunchCaller.App("dev.droidtop.app")`
  instead, which the caller-access model above resolves to **Ask** (never
  Block, matching `blockReason is never applied to the unrecognized-caller
  bucket`) -- but droidtop was also the one package `offerAddCaller`
  deliberately excludes from its own picker (it is meant to need no manual
  decision at all), so once a person cancelled or missed that prompt there
  was no way back into it from the list either.

Fix: a `<queries>` block declaring the `LAUNCHER` intent (what
`offerAddCaller` needs), the `VIEW`/`BROWSABLE` `http`/`https` intents
(what `resolvesHttpHandlerOn` needs), and an explicit `<package
android:name="dev.droidtop.app" />` -- the one that actually matters here,
since it guarantees droidtop's visibility regardless of whether the
`LAUNCHER` query would have caught it. With visibility restored,
`isDroidtop` succeeds again and a real droidtop launch goes straight
through as `LaunchCaller.Droidtop`, exactly as before this regression:
no picker entry needed, because it is never gated in the first place.

Also added, from the same report: `CallerSightingsStore` records every
real (non-droidtop, non-`Unknown`) caller `LaunchActivity` actually gates,
independent of what a `PackageManager` query can see. Settings > App
launch access now lists a caller it has seen and not yet decided (shown as
"Asked each time") alongside the ones with an explicit decision, so a
caller invisible to `offerAddCaller` for some other reason still shows up
once it has genuinely called, and a person is never stuck unable to find
it in the list.

## Order of checks, 2026-09-28

Caller access is the first thing `LaunchActivity` does, before anything about
the game is read (Droidtop/tracker#44). The access decision needs only the
caller key and, for a prompt, the folder's name as text; it never touches the
folder. Block refuses and Ask waits for the person's choice with no planning
done, so a blocked or unapproved caller cannot make Enginehost inspect the
folder it named, resolve plugins, persist a pending launch or open the setup,
catalog or trust screens. Only after Allow does `GameRunner.plan` run, and it
runs off the main thread with the launch screen showing its starting state
(Droidtop/tracker#45); the result is applied on the main thread and dropped if
the screen has gone. A retry or an engine restart on the same screen does not
ask again.

The already-running-game shortcut in `LaunchActivity.start` (which brings the
running game's task forward and carries no extras) is gated the same way
before it is taken: for a caller that is not droidtop, anything but Allow
leaves the running game untouched and says so in a toast, because a launch
screen started to ask would clear the game that is running. Allow the app
under Settings > App launch access and launch again. `GameRunner.run` no
longer logs the game folder's name with the caller.

## Needs a rig check

Install the built debug APK on BlueStacks (RIG ERA / `device/wadb`,
rig lock) and confirm, the user way:

1. `adb shell am start -a dev.enginehost.LAUNCH --es path "'<folder>'"`
   (an unrecognized caller) stops at "Launch this game?", Cancel focused,
   naming the caller as unrecognized and the game.
2. Picking "Always allow this app" runs the game; repeating the same `am
   start` afterwards runs it again with no prompt (Settings > App launch
   access now lists "An unrecognized caller" as Allowed).
3. A caller resolved as a browser (or a synthetic test package registered
   to handle `ACTION_VIEW` `http`) is refused outright with the browser
   message, no prompt, and "Allow this app" on that screen moves it to
   Allowed.

Package visibility follow-up (build 234 fix), both rigs (emulator-5560,
Android 14; BlueStacks, Android 9), with droidtop's latest debug APK
installed alongside:

4. Launch a game from droidtop's own library the ordinary way: it starts
   with no prompt at all (droidtop is `LaunchCaller.Droidtop`, never
   gated).
5. Settings > App launch access > Add an app lists real installed apps,
   not just system ones.
6. A caller resolved as a browser is still blocked by default and an
   ordinary unrecognized app still gets Ask, confirming the `<queries>`
   visibility fix did not also widen who gets Allow.

## `dev.enginehost.END_GAME`

A second action on the same exported door ends the running game and, unlike
LAUNCH, is answered only to droidtop (package plus signing certificate,
`TrustedCallers.isDroidtop`; an intent that supplies its own referrer extras is
untrusted). Any other caller gets RESULT_CANCELED with `error=untrusted_caller`
and nothing is touched. See docs/SPEC.md "Ending the running game".

Needs a rig check: from droidtop with a game running, Kill: the game ends, droidtop
reads `ended=true`. With no game running: `ended=false`. `adb shell am start -a
dev.enginehost.END_GAME` (no trusted caller) leaves a running game alone.
