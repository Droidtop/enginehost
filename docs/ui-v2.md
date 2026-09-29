# Enginehost UI v2: task-first for an end user

Written 2026-09-28 by the UI/UX review pass (ehv2ui). Enginehost only; droidtop has
its own separate review and design doc, sharing cues (coordination/design/DESIGN-LANGUAGE.md)
but never settings or screens.

## Where this comes from

The owner, 2026-09-28: "Our current UI is INFINITELY better than what we started with,
but it's also likely an accumulation of stuff... this very much doesn't feel like software
an end user should be touching." Enginehost's actual end user rarely opens the app on
purpose: their main contact with it is "a game launched from my frontend" (droidtop, or
another caller). The exceptions are occasional: approving a new plugin, picking up an
update, reporting a game that misbehaves. Everything in this app should read from that
person's side, not from the side of whoever is verifying a signing key.

This review is grounded in the current source (git rev 8413ad6, `app/src/main/kotlin/dev/enginehost/`),
not a fresh redesign from a blank page — the existing screens already carry real craft (folded
detail panels, typed failure messages, per-card progress). v2 keeps that and fixes the navigation,
vocabulary and coverage gaps below.

## Landed: the updater rework (Droidtop/tracker#23)

Home > Plugins now opens the installed list (`PluginTrustActivity`, titled Plugins). Add in its
header opens the catalog (titled Add a plugin), which no longer has a button back to the list. Updates
found in the cached catalogs sit at the top with one Update per plugin and Update all; they install
through `PluginInstaller.installQuietly`, the same path the automatic pass uses. An approval carries to
an update signed by the same key from the same repository (`PluginTrustStore.carryApproval`); a
different key, a different repository or a denied build still asks, and the installer still refuses
anything but a strictly newer build. Each card says whether the plugin runs in a sandbox.

## 0. Raw internal engine ids in the configurator (rig-confirmed, highest priority)

**On the rig (BlueStacks Pie64, 127.0.0.1:5555, build 234, `dev.enginehost.LaunchActivity` ->
game detail -> Game setup, screenshot `eh9-configurator.png`):** opening Game setup for an
installed RPG Maker VX Ace game shows, as the actual field values a person reads:

- Engine: `rpgmaker` (the internal engine id, not "RPG Maker")
- Variant: `vxace` (the internal variant id, not "VX Ace")
- Embedded runtime: `ruby 1.9.2` — a true fact about the game, phrased for someone who knows
  what an embedded Ruby runtime is

This is the exact "raw ids... shown without need" pattern this review was asked to find, and it
sits on the one screen a person reaches for when a game will not start — exactly when they are
least equipped to read `vxace` as "VX Ace". `EngineNames.kt` already exists and is already used
elsewhere (`EngineNames.family`, `EngineNames.compatibility` in the Settings and Trust screens) to
turn these same ids into "RPG Maker", "VX Ace" and so on; the configurator's own read-only summary
fields do not route through it.

**After:** Engine and Variant display through `EngineNames` like every other screen does; the
internal id stays available (e.g. in a details fold, for a bug report) but is not the first thing
read. "Embedded runtime" either gets a one-line gloss ("the game's own script engine") or moves
behind the same disclosure.

Task: `tasks/p1-v2-eh-configurator-plain-names.md`.

## 1. The Plugins entry is backwards

**Today:** Home's "Plugins" button (`MainActivity.kt:52-54`, `pluginCatalogButton`) opens
`PluginCatalogActivity` directly — a browse-every-release, manage-sources, quick-add-third-party-repo
screen. The list of what is actually *installed*, and the approve/deny/uninstall decisions, sit one
tap further in, behind a secondary "Manage plugins" button (`PluginCatalogActivity.kt:88-90`,
`installedPluginsButton` → `PluginTrustActivity`).

For someone who almost never adds a plugin, this means the app's main plugin door opens onto a
release catalog, source list and Quick add panel before they ever see what they have.

**Before:** Home → Plugins → catalog (browse/sources/quick-add) → *(button)* → installed list (trust,
approve, uninstall).

**After:** Home → Plugins → installed list, with badges and update state on each card, and one
**Add** action that opens the catalog. The catalog becomes the secondary screen, reached only when
someone actually wants to browse or add — exactly the owner's 2026-09-27 direction
(`/root/coordination/opencode/tasks/p1-eh-updater-rework.md`, point 1), not yet built.

Task: `tasks/p1-v2-eh-plugins-installed-first.md`.

## 2. No place that says "you have updates" except a banner sentence

**Today:** `PluginUpdateCheck.pending()` and `PluginUpdates.updatesFor` compute exactly this, and
`MainActivity.onResume` (`MainActivity.kt:66-92`) shows a one-line home banner
("N plugin updates are available") that deep-links into the catalog — where an update is just
one more state a release card can be in (`PluginCatalogActivity.kt:571-604`, the button reads
"Update to build N"), mixed in among every other release, official or not, installed or not.
There is no "Updates" list, and no "Update all". Updating three plugins means finding each one's
card in the full catalog and tapping it three times.

**After:** a dedicated Updates section on the (now-primary) installed-plugins screen: every
plugin with a newer signed build from its own origin, "Update all" plus per-row update, nothing
else mixed in. The full catalog stays reachable from Add for anyone who wants to browse past what
they already run.

Task: `tasks/p1-v2-eh-updates-list.md`.

## 3. Trust does not carry over to an update from the same signer

**Today:** `PluginUpdates.kt`'s own doc comment says it plainly: "Replacing a bundle never
inherits its approval: the trust store binds a decision to the exact archive digest and signer...
so the replacement is unapproved until the user approves that exact new archive." Confirmed in
`PluginTrustActivity.addPlugin` — the Approve/Deny state is per installed archive, so every
update, even a patch release from the exact origin and key someone already trusted, is a fresh
approval prompt.

The owner's spec (updater-rework task, point 3) asks for the opposite: approving a plugin trusts
future updates signed with the *same* key; a key or origin change still prompts. That is a real
security property (verified signature continuity), not a weakening — it removes a prompt that
teaches "tap approve" reflexively without changing what is actually being verified.

**After:** an update whose signer key and origin match the currently-approved build installs and
runs without a new prompt; a key or origin change (or a downgrade — see below) still stops and
asks, with the two fingerprints shown side by side (the pattern `reviewChangedKey` in
`PluginCatalogActivity.kt` already uses for an origin's key change).

Task: `tasks/p1-v2-eh-trust-carryover.md`, with unit tests: same key → no prompt; different key →
prompt; downgrade → warn.

## 4. Sandboxing is a real property with no badge

**Today:** `InstalledPlugin.isolatable` and `EngineBundlePackage.isolatable`
(`PluginRegistry.kt:42`, `EngineBundlePackage.kt:38-39`) already carry whether a plugin runs
isolated, and `LaunchActivity.kt:153` already gates the extra unsandboxed-launch warning on it.
But `PluginTrustActivity`'s own card — the one screen whose whole job is "should I trust this
plugin" — shows only the trust-provenance badge (Official / Third-party / Community / Ultimate)
and never says whether the code inside runs isolated. Deciding to approve a plugin is exactly
where "does this run in its own process" belongs.

**After:** a second, small "Supports sandboxing" (or "Runs unsandboxed") badge on every installed
and catalog card, read straight from the existing `isolatable` flag — no new data, just surfaced.
The existing unsandboxed-launch warning in `LaunchActivity` stays as is.

Task: `tasks/p1-v2-eh-sandbox-badge.md`.

## 5. The launch-access filter's "Ask" (rig-checked: already there, wording could be tighter)

**Today, corrected after the rig walk (`eh5-caller.png`, `eh6-addapp.png`, `eh7-decide.png`):**
`CallerAccess.kt:42` stores only `ALLOW`/`BLOCK` (`enum class CallerDecision { ALLOW, BLOCK }`),
but the actual per-caller sheet (`CallerAccessSettingsActivity.offerChange`) offers three choices,
and the third reads as "Ask" in plain language: **Allow**, **Block**, **Remove (ask each time)**.
Picking Remove clears the stored decision, which is exactly "go back to being asked" from the
person's side. The screen's own intro line already says this in prose ("which apps may do that
with no prompt, which are refused outright, and which are asked about each time"). So the
owner's "Allow/Ask/Block" requirement is functionally met; there is no missing state, only an
enum that models it as absence-of-a-decision rather than a third named value, which is a
reasonable implementation and not something to change for its own sake.

Two real gaps sit next to it, both visible on the rig:
- **The "Add an app" picker is unfiltered** (`eh6-addapp.png`): it lists every launchable package
  on the device by raw package id — Camera, Clock, Contacts, Gallery, Google Play Games, Home,
  alongside BlueStacks — for a decision that is only ever meaningful for a handful of them. A
  security-relevant allow/block list buried in irrelevant system apps, named by package id, is
  exactly the "internals exposed rather than tasks" pattern, on the one screen where getting the
  right app matters most.
- **No plugin is approved by default, even Official ones** (`eh3-trust.png`): every bundled,
  Official-badged plugin on a fresh install shows "Trust: Waiting for your approval", so the
  first visit to Plugins is a wall of identical Approve/Deny/Uninstall decisions before anything
  can run. Deciding to trust Official releases as a group (a "Trust all Official" action, or
  auto-approving Official on install, which is a straightforward provenance check the app already
  makes) would remove that wall without touching third-party or Community trust, which should
  stay exactly as deliberate as it is now.

Task: `tasks/p2-v2-eh-caller-picker-filter.md` (Add an app), `tasks/p2-v2-eh-official-trust-default.md`
(bulk/default trust for Official plugins). No task for the Allow/Ask/Block state itself — it
already does what was asked.

## 6. Raw internals in plain settings rows

**Today, two concrete instances:**
- `EnginehostSettingsActivity.kt:100-107`: the App row always shows
  `versionName (versionCode)` *and* the bundled droidtop-platforms git commit
  (`PlatformSnapshot.shortCommit`) — e.g. "1.4.2 (118) · c74eba3" — in the same row a person taps
  to check for an update. The commit hash answers "which platforms snapshot shipped in this
  build", a question only someone debugging a classification dispute asks.
- `PluginTrustActivity`'s build line (`PluginTrustActivity.kt:63-67`) always shows
  `owner/repo` for the origin next to the version, even for Official plugins where the badge
  already says "Official" — the repo slug adds nothing for that read, only for Community/Third-party.

Both are minor next to items 1-5, but they are exactly the "accumulation" pattern the owner named:
information that was useful to whoever was debugging the thing at the time it was added, left in
the everyday path instead of behind an Advanced disclosure.

**After:** the commit hash moves behind an "Advanced" / long-press disclosure on the App row (the
version stays, since that is what "check for update" is about); the trust build line only adds the
repo slug when the badge is not already Official.

Task: `tasks/p2-v2-eh-plain-language-pass.md`.

## 7. The bug-report flow's one hard step is a GitHub sign-in

**Today (docs/security/2026-09-28-bug-report-flow.md, `ProblemReportActivity.kt`,
`ProblemReportFormActivity.kt`):** this flow is genuinely well built — every field is prefilled,
none are required, Send is reachable immediately, log scrubbing exists (`ProblemReport.scrub`),
and Copy report is a real fallback. But "Send" opens a GitHub issue form in an in-app WebView, and
filing that issue needs a GitHub account. For the actual audience — someone whose games run
through a frontend and who has never had a reason to make a GitHub account — that is the one place
this flow stops being simple. The "Copy report" button already produces something a person could
paste into a support channel that is not GitHub, which is the honest fallback today, but it is a
same-weight secondary button next to Send, not offered as the answer to "I don't have a GitHub
account."

**After:** no infrastructure change (there is nowhere else for a report to go without owner
decisions this review will not make), but the copy-and-paste path gets a one-line explanation
next to it ("No GitHub account? Copy this and paste it wherever you got Enginehost from") instead
of reading as a plain alternative button with no stated reason to prefer it.

Task: `tasks/p2-v2-eh-bugreport-noaccount-path.md`.

## What is already right and should not be touched

- `HostMenu` (in-game menu): five flat choices, no nesting, save-location and controller-legend
  answers phrased for the game currently running, not for the engine. Matches the design language's
  "settings are for configuration, actions live in-context" rule already.
- `PluginTrustActivity`'s card: identifiers (bundle id, verified origin, signer fingerprint) are
  already folded behind a "Details" disclosure (`item_plugin_trust.xml`), with only the plain badge,
  title and state showing by default. Keep this pattern; item 4 above adds to it, not around it.
- `PluginCatalogActivity`'s release cards: install/update is a direct button on the card, not a
  nested menu — "flatten the update/trust flow" (owner's updater task, point 4) is largely already
  true at the per-release level; the gap is entry order (item 1) and bulk update (item 2), not
  nesting depth.
- The problem-report form's optional, prefilled, never-blocking fields are the right shape; only
  the account requirement on Send needs the note in item 7.

## Method note

This pass is both source-grounded (file:line citations) and rig-confirmed. The rig's adb bridge
(`/root/coordination/device/wadb`) failed early in this session with a WSL interop error
(`cannot execute binary file: Exec format error` calling `powershell.exe`); the coordinator fixed
the underlying WSL interop registration mid-session, and a walkthrough followed on BlueStacks
(Pie64, Android 9, `127.0.0.1:5555`, Enginehost build 234 / `0.1.5-dev-234`, already current,
no reinstall needed). Findings 0, 1, 2 and 5 are rig-confirmed with screenshots
(`eh1-home.png` through `eh9-configurator.png`, staged at `C:/RSL/tmp/` during the session; not
committed anywhere, they are working artifacts, references only). Findings 3, 4, 6 and 7 remain
source-grounded (file:line citations above) — the rig walk did not reach an actual game launch
(the Ask prompt and unsandboxed warning), an update from an old build (trust carryover), or the
bug-report form, for lack of remaining session time; those four are lower-confidence than 0/1/2/5
and worth a rig re-check by whoever picks up their tasks. Finding 5's original framing (a missing
Ask state) turned out to be wrong on inspection — the rig showed the state already exists, just
under different wording — which is why it reads as a correction above rather than a plain finding;
worth remembering that this pass, like any single review, can overclaim before seeing the actual
screen.
