# Bug reports: closing the host-crash gap, 2026-09-28

Scope: `ProblemReportActivity`, `ProblemReport`, `CrashWatch`, the new
`HostCrashWatch`, and the owner's requirement (2026-09-28): "The bug
submission process needs to be as simple as possible, and critically, it
needs to provide as much information as it can to help us troubleshoot
(keeping the privacy settings we previously talked about in mind)."

## What was actually wrong

Testing the settings-screen crash (`docs` note below) against the report
flow found two real bugs, not just polish:

1. **There was no route into the report flow for a crash in Enginehost's
   own process at all.** `CrashWatch` is armed only in a game's runtime
   process (`CrashWatch.arm`, called from the launch path as a game
   starts) and read back in `EnginehostActivity.onResume`. A crash in
   Home, Settings, or any other host screen has no runtime session to
   read: Android just returns the person to the launcher with nothing
   said, and pressing "Enginehost" again reopens a normal Home with no
   memory of what happened. The "file a report about this crash" step the
   owner asked to test had nowhere to start from.
2. **The game field blocked sending when there was no game.**
   `ProblemReportActivity.send()` refused to submit with a blank
   `reportGame` field. `ProblemReportActivity.intent()` already documented
   support for a report about the host itself (`gameFolder == null`), but
   taking that path left the one required field permanently unfillable
   from data the screen had -- a report about Enginehost could never
   actually be sent.

## The fix

**`HostCrashWatch`** (new) is `CrashWatch`'s shape, scoped to the default
process instead of a game's: `EnginehostApplication.onCreate` installs a
`Thread.setDefaultUncaughtExceptionHandler` wrapper (default process only
-- a game runtime crash is `CrashWatch`'s job, and the isolated/plugin
processes are sandboxed on purpose) that writes reason + a trimmed stack
trace to `host-crash.json` before chaining to whatever handler was there.
`EnginehostActivity.onResume` reads it back unconditionally (not gated on
`reportsRuntimeCrashes`, since a host crash is not the launch screen's
crash to own the way a game's is) and offers "Report a problem" the same
way a game crash already did, landing in `ProblemReportActivity` with
`gameFolder = null`.

**`ProblemReportActivity`** no longer requires the game field. It is
still prefilled -- from the game name when there is one, else the engine
and version, else just "Enginehost" -- but editable and never blocks
Send. `primaryAction()` simplifies to the Send button unconditionally.

**`ProblemReport.scrub`** gained a few more redactions beyond the
existing storage/app path replacement, on the same principle (keep the
shape a reader needs, drop the value): email addresses, IPv4 addresses,
`/home/<user>` paths, `Bearer <token>` headers, and GitHub token shapes
(`gh[pousr]_...`). None of this is a promise of completeness for
arbitrary plugin log text; it is the same best-effort scrub the path
redaction already was, extended to a few more patterns that showed up in
practice.

**`ProblemReport.environment()`**'s plugin line now also names the
plugin's origin, signer identity, and whether it is running sandboxed
(`InstalledPlugin.isolatable`) -- all already computed by
`PluginRegistry.resolve` and simply not being surfaced before.

## What is still true from before (kept, not reopened)

- Nothing leaves the device until the person presses Send in
  `ProblemReportFormActivity`'s embedded GitHub form (or explicitly opens
  it in their own browser) -- the report is a prefilled URL, not an
  automatic upload.
- The log switch (`includeLogSwitch`) is off by default is unchanged by
  this pass; only its content is more consistently useful now that a host
  crash's own trace is what leads the log field instead of an unrelated
  game log tail.
- No GitHub token or credential is collected anywhere in `ProblemReport`;
  the token-shaped redaction above is a defense against a plugin log
  happening to contain one that was never Enginehost's to have, not
  evidence one normally would be.

## What is not covered here (recorded, not built this pass)

The owner's fuller ask (GPU renderer/driver, RAM, refresh rate, launch
caller/transport, last host events such as "config loaded" / "surface
created" / "audio init", a trimmed multi-tag logcat capture, a native
tombstone summary, non-default settings) is a real expansion of
`ProblemReport.gather` beyond what this pass touched. The plugin/signer/
sandbox and redaction work above are the cheap, load-bearing parts of
that list; the device/GPU facts and an actual "last N host events" ring
buffer are a second pass, not attempted here to keep this change coherent
and reviewable as one piece: the crash-route and required-field bugs are
what made the flow non-functional at all, and were the priority.
