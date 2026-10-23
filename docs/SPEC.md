# Enginehost UI decisions

## Core line selection

Engine version detection may select an installed core line only when its
major and minor components match the detected version. Missing or malformed
detection, and a detected line with no installed core, must not silently
fall back to a nearby line. The host can present nearest installed lines as
explicit alternatives, but choosing one requires a person to select it.

## Controller focus on text links

Focusable text links use the shared row background and explicitly opt into
focus and click handling in `Widget.Enginehost.Link`. That keeps the Details
link's D-pad focus on the same raised fill and accent ring used by Enginehost's
other focusable controls. The wide-window trust card uses the same
style on its Details link (`layout-w600dp/item_plugin_trust.xml`).

## An update that is not carried over says why

An approval carries to an update signed by the same key from the same
repository (`PluginTrustStore.carryApproval`). When a build of a bundle the
person approved before is waiting for approval instead, its Trust line says
which of the two changed: "a different key signed this build" or "this build
comes from a different repository" (`PluginTrustStore.changeSinceApproval`; the
last approved repository is kept per bundle for that). A first install says
nothing extra.

## Several builds of a line, side by side (decided 2026-10-16)

Owner: "Users should not be tied to only one plugin version. This is one of the
benefits of not using apks." / "Several builds can be installed. That's why
there's a plugin version requirement in the enginehost spec. If a newer version
has a regression, or certain versions have bugs, we can blacklist or filter
them".

- **An install adds a build.** Each build has its own directory
  (`<bundleId>--<archive digest>`) and its own trust decision. The installer
  refuses only the archive already installed, and a build of the same bundle ID
  from another repository. It replaces only the same build under another
  signature: the same line and build number, for example a stable build
  re-signed in format v1 (`PluginUpdates.isSameBuild`).
- **Updates:** a build newer than the newest one installed in that line
  (`PluginUpdates.newestPerLine`). Installing it adds it; the older builds stay
  until the person removes them, so a regression never strands anyone.
- **Catalog:** every build is installable, older ones included. Only the
  archive already installed reads "Installed".
- **Cores screen:** one card per build, a line's builds together and newest
  first, each with its own Approve, Deny and Remove.
  Everything a card says (trust, approval state, licence text) is worked out
  off the main thread with the list. With every line's old and new builds
  installed after Update all (about sixty cards), working it out card by card
  on the main thread, while the installs kept the disk busy, ended in "isn't
  responding" (rig, 2026-10-23, Droidtop/tracker#604).
- **Home's update notice** ("N core updates are available", and an app
  update) sits in the header beside Add games, as tall as it. The check
  answers seconds after launch; as a row above the list it pushed the list
  down under a tap meant for a game (same rig run). It is a tonal button, so
  Add games stays the header's one primary button; styled as a primary with
  accent text it read as a blank blue button (review, Droidtop/tracker#618).
- **Launch:** `PluginResolver.ranked` picks the best approved build the game's
  `pluginVersion` allowlist allows, which is how a game avoids a bad build.
  The launch carries that build's archive digest
  (`RuntimeActivity.EXTRA_PLUGIN_ARCHIVE`), so the runtime loads exactly the
  build that was resolved and approved.
- **Blacklisting a build is the person's, per game (decided 2026-10-16).**
  Owner: "That's not a thing. Users can blacklist builds using the enginehost.json
  file". There is no list that withdraws one build for everyone, and none is
  planned. The signed revocation list withdraws certificates and keys only. A
  person leaves a bad build out through the game's `pluginVersion` allowlist,
  set in the config creator and saved to the game's `enginehost.json`
  (`contract.md`). The allowlist names what is allowed and has no exclusion or
  open-ended range, so leaving out build 1.0.191 is written as ranges either
  side of it with an upper end, for example `1.0.0-1.0.190,1.0.192-1.0.999`.

## Layout follows the window, and Home keeps its place

A layout decision reads the window's own width and height in dp through
`SizeClass`, never the device kind and not orientation alone: compact under
600dp, medium to 839dp, expanded from 840dp; a window under 480dp tall is
short. The library grid fits as many columns of at least `eh_card_min_width`
(320dp) as the width it really has allows, redrawn on every layout change
(`LibraryBrowser`), so a phone upright shows one, a handheld two and a tablet
more, and a split-screen half behaves as the narrow window it is.

Turning the device or resizing the window recreates Home. The search text,
filters, sort and the place in the list are saved with the instance state and
put back (`LibraryBrowser.saveState` / `restoreState`); nothing a person set up
on Home is lost to a rotation.

## Three destinations: Library, Cores, Settings

The app is organised around three destinations, always the same three
(`Destination`): the Library (Home), Cores (the installed cores, with
updates and Add) and Settings (which now also holds Controller). Adding games
is the Library's one primary action, in its header, not a place. The Library,
Cores and Settings screens draw a `DestinationBar` around their content: a bar
along the bottom of a narrow window, a rail at the left edge of a wide (600dp and
up) or short (under 480dp) one (`SizeClass.usesRail`). Home has one layout for
every window; the old landscape fork with its button sidebar is gone.

The pad changes destination with L1 and R1 (previous and next, wrapping); no
destination item takes focus, so the D-pad only ever moves within a screen's
content. The hint row names L1/R1 where it has the room (windows 480dp wide and
up) and the buttons answer everywhere a bar is drawn. Opening another destination
from Cores or Settings replaces that screen, so back always lands on the
Library. "Cores" is the destination's name and its heading, with the line that
cores are the cores that run a game engine; trust, update and error text keep
"plugin" for the signed bundle. A Cores screen showing one just-installed core
(`EXTRA_BUNDLE`) is opened inside a flow and draws no bar.

## An empty library leads to a next step

A library with no games at all (not a filter that matches none) says so and
offers what to do: "Add a folder of games" opens the scan flow, and the line
under it says how many cores are installed, with an "Install a core" button
when there are none (`MainActivity.showEmptyState`; the installed cores are read
off the main thread). With games but none matching, only "No games match." shows.
The first-run step is the pad's first selection.

## The game page has one state-aware primary button

A game's screen has one primary button, and the game's state picks it
(`GamePrimary`, decided off the main thread by `GameStatus.of`): Play; Get the
core when no installed core fits; Approve the core when one fits but is not
approved; Set up when detection left a question open or the game's own config is
unusable; and, disabled with the reason as the label, Folder not available and
Not supported here. Get the core and Approve the core run the same launch as
Play, because its plan already detours to the catalog or the trust screen, so
there is one path to each. Under the status line a game that has a core says which
build will run it and whether it is sandboxed ("Core 1.0.1-2 · Sandboxed").

## Home shelves and favourites

While nothing narrows the list, Home shows two shelves above it: Continue
playing (the games played last, from `played_at`) and Favourites. Each is a row of
at most `LibraryBrowser.SHELF_SIZE` game cards; a shelf with no games is not
drawn, and a window under 480dp tall shows only the first that has games, so a
handheld held sideways keeps its list. A card opens the game's screen as a
library row does, Y plays it, and the pad reaches the shelves by pressing up from
the list. A shelf is a saved query plus a limit, not a second taxonomy: Favourites
is the `favourite` column (database version 2) that a game's screen toggles, and
the Filters sheet can show only favourites. Engine shelves are the existing engine
filter; there is no separate list of them.

## Ending the running game: `dev.enginehost.END_GAME`

droidtop's Kill cannot confirm that an Enginehost game ended when Shizuku is not
running, so Enginehost ends it itself. The action is a second action on the one
exported door, `LaunchEntryActivity`, next to `dev.enginehost.LAUNCH`; there is no
second mechanism. It is answered to droidtop alone, checked by package and
signing certificate (`TrustedCallers.isDroidtop`, never by the intent's own
referrer extras), because LAUNCH is open to any app by design but ending a game
someone is playing is not. The caller starts it with `startActivityForResult`
(the calling package is then Android's own record); the result is RESULT_OK with
boolean extra `ended` (true: a game was running and its runtime process was ended,
without a crash report; false: none was running), or RESULT_CANCELED with string
extra `error` = `untrusted_caller`, touching nothing. It takes no other extras.

## Games do not get to refuse the wrapper (decided 2026-10-22)

The owner, 2026-10-22: "If the test game refuses android, we need to fix our
wrapper to fix that. Games don't GET to say no to us." A game that checks what
it runs on and declines Android (NekoParadise 0.18 raised "Android not
supported!" from its own script on the Ren'Py 7.5 core) is Enginehost's bug,
not the game's, on every core. A core presents the game the platform it was
shipped for, as a desktop build would see it, without changing a game file:

- **Ren'Py, every line:** the game's script sees the shipped desktop
  platform in `renpy.exports` and no platform variant (`android`, `mobile`...)
  in `config.variants`, with `pc` added; the engine keeps its own Android
  flags, and the layout variants (`touch`, `small`, `phone`, `tablet`...) stay
  so screens fit the device (owner, 2026-10-23). The core's ENGINEHOST.md has
  the details.
- **Godot:** `OS.get_name()` and the feature tags answer the platform the game
  was exported for (`ENGINEHOST_GODOT_PLATFORM`, docs/engine-bundle-format.md).
- **Any other core** whose game refuses Android gets the same treatment when
  it is found.

A game's options can still choose Android: `"platform": "android"`.

## A game that shows nothing, stops responding, or closes unseen (decided 2026-10-22)

A crash was the only failure Enginehost noticed; a game that sat on a black
screen, froze, or closed before drawing left the person with nothing said.
The owner, 2026-10-22: the person is asked and the game keeps running (no
failure ends a game for them); a black screen is 45 seconds without a drawn
frame and a hang 15 seconds without the main thread answering. "Remember that
renpy throws a splash. The black screen was a unity game". That game, Clover
Days, is a Windows Unity build that runs through droidtop's own Wine, not
Enginehost, so it cannot exercise this check; droidtop detects its own black
screens. A game that tests it here is an Enginehost game that draws nothing,
such as a LÖVE game whose `love.draw` is empty.

- **Black screen.** The loading notice (`RuntimeLoadingNotice`) already reads
  the game's SurfaceView every 750 ms until something other than black is
  drawn. Still black at 45 s with the game in front, it asks once
  (`GameTroubleActivity`): Keep waiting, End game, Report a problem. An engine
  with no SurfaceView is not watched, and a game whose surface cannot be read
  is never called black.
- **Hang.** In the runtime, the main thread beats every second and a watcher
  thread that sees no beat for 15 s, with the game in front, asks the same
  question; a stall that goes on is asked about again every 30 s
  (`RuntimeWatchdog`). Android's own ANR only fires when input is waiting, so a
  game that froze untouched went unnoticed, and the in-game menu, drawn on the
  same thread, could not open. The question is drawn in the main process, over
  the game in its task, because a hung runtime cannot draw.
  Its rig check is a debug switch (owner, 2026-10-22, chosen over a test
  core): `adb shell setprop debug.enginehost.stall 20` blocks the runtime's
  main thread for 20 s (at most 120), once, 2 s after any game is in front;
  `setprop debug.enginehost.stall 0` turns it off. Only adb or root can set a
  `debug.` property.
- **End game** kills the runtime; the launch screen then shows the reason
  ("Showed only a black screen", "Stopped responding") with Try again and
  Report, from the session note (`CrashWatch.recordFailure`).
- **Closed unseen.** A game whose screen was readable, never drew, and ended
  without the person quitting from the in-game menu or `END_GAME` is reported
  on the launch screen as "Closed before it showed anything", with Try again
  and Report, instead of returning silently. A Back press that closes such a
  game is reported the same way.
- **Report a problem** is also in the in-game menu (review findings 170 and
  179), and the report can be saved as a text file, besides Share, Copy and
  posting it on GitHub; nothing leaves the device unless the person picks one.
- **Reports go to the tracker.** The GitHub button opens a new issue on
  Droidtop/tracker (owner, 2026-10-22: "always the tracker"), no longer the
  separate enginehost-reports repository. Only a title (engine and symptom)
  is in the address; the report itself is copied for the person to paste.

## Reporting whether anything is open: the `state` call

droidtop keeps a "Playing" session for the game it started and needs to know when
it is over without a privileged helper (Droidtop/tracker#289: Cancel on the
no-sandbox warning, or Back on a startup-error screen, left it standing for ever).
`CapabilityProvider` answers `call("state")` on `content://dev.enginehost.capabilities`
to droidtop alone (Android's record of the caller, `TrustedCallers.isDroidtop`);
anyone else, and an unknown method, gets null. The Bundle holds `tasks` (the number
of Enginehost's own tasks across all its processes that are running, from
`ActivityManager.getAppTasks`: a launch's warning, a running game and an error
screen each hold one, so it is 0 once a launch was cancelled, a game ended, or its
error screen was left. A finished task Android keeps in Recents is not counted:
`getAppTasks` lists it too, and counting it kept droidtop's session standing after
Cancel, rig 2026-10-15; `TaskInfo.isRunning`, or `numActivities` before API 29) and
`runtime` (whether the `:runtime` process is alive). It is read-only and touches
nothing; END_GAME above remains the only way to end a game.

## Per-game choices live in the library database

What a person sets for one game on this device (the core it runs on, an engine
option) is kept in Enginehost's library database, one JSON document per game keyed
by its path (`game_overrides`, `GameLibraryStore.overridesFor` / `setOverride`,
`GameOverrides`), never as a file in the game folder (decided 2026-10-09,
tracker#235). `EngineConfigReader.resolve` layers it above everything else: the
folder's `enginehost.json` stays the game author's own file, and the person's value
for this game wins over it, nested `options` merged key by key. The planner
resolves it once and hands it to the runtime as an extra
(`RuntimeActivity.EXTRA_OVERRIDES`) so both resolve the same config.

## The game page's sections

Under its status line a game's page shows rows, read off the main thread after the
page itself (`GameSectionsLoader`, drawn by `GameSectionsView`):

- **Controls** (first, so it is never under the fold at 1920x1080; review,
  Droidtop/tracker#618): whether the game has its own buttons or follows the
  engine's; it opens the controller screen on "This game". Shown when a core resolves.
- **Core**: the build that runs the game and whether it is "newest that fits" or
  "fixed for this game". Its sheet lists "Newest that fits" and every installed
  build that could run the game (`PluginResolver.ranked` without an allowlist), with
  its sandbox state. A fixed build is the per-game override `pluginVersion`.
- **Update the core**: shown when the catalogs already on the device hold a newer
  build of the core and the game is not fixed to one; it installs through the quiet
  path Cores' Update uses, and the same-key approval carries over. It is a row,
  not the primary button, so Play stays Play while an update waits.
- **Options**: one row per option the core declares (`DeclaredOptionsReader`),
  showing the value this game runs on and its source: engine default, the game's own
  setting (its `enginehost.json`), or this game (an override). Boolean, choice,
  number and string options are edited here and written as overrides (`options.<name>`);
  a "Back to the default" choice takes the override back. Path, file and repeated options are
  set in Game setup, which edits the game's own file.
- **Saves**: where the saves are, when they last changed and how big they are
  (a bounded look at the save folder). For an engine that saves beside the game the
  place is the game's folder and nothing is measured.
- **History**: time played, starts, when it last started and how the last session ended.

Play history (`launches`, `play_ms`, `last_exit` on the library row, database version 4) is
written by the launch screen: a start counts a launch; the session's length is
added when the runtime ends, unless it lasted under five seconds (a start that failed);
a clean close, a crash and a runtime that never drew are told apart. Time played is also a
sort order on Home.

## Controls have a per-game scope, and every level says where a button comes from

A game can have buttons of its own (Droidtop/tracker#239). The controller screen
offers "This game" first when it was opened for a game (from the game page's Controls
row, or from the in-game menu); its map sits on top of the engine's. A binding resolves
game, then engine, then all engines, then the action's default
(`ControllerBindingStore`; game keys are `game|<path>|<action>` in the same bindings
file, so the runtime reads them the way it reads the rest and a change applies at
once). The actions on offer are always the engine's own. Every row that the edited
scope does not set itself says where its button comes from: "(from the engine)",
"(from all engines)" or "(default)", so the first level that has one wins visibly;
a long press on a row the scope does set takes it back, and "Reset this game" clears
the game's level only. Bypass stays an engine-level choice. The game page's Controls row
says whether the game has its own buttons or follows the engine's.

## Wide windows: Home's list and the game page side by side

From 840dp wide (`SizeClass.EXPANDED_MIN_DP`) Home shows the library column and a
pane for the selected game's page. Choosing a game (tap, A, a shelf card) moves the
pane and puts the pad on its Play button; nothing is pushed, and before a choice the
pane says to choose one. The pane and the narrow window's `GameActivity` are the same
`GamePage` filling different view trees, and they inflate the same one layout
(`activity_game`; the pane hides its back button), so a game's page is one piece of code
and one layout, with no landscape fork: it is a single scrolling column whose cover plate takes the
height that is free (`eh_plate_min` floor), in the pane or full width. The pane's game survives a rotation.
Narrower windows keep opening the page as its own screen.

The other screens follow the same rule through Android's width qualifier
(owner, 2026-10-17: "If something is always forced into a single mode, it isn't
really following the window size, is it?"). Their multi-column layouts are in
`layout-w600dp`, chosen when the window is at least 600dp wide, the same line
`SizeClass` draws between compact and medium: Cores puts two cards per row,
Settings puts saves beside updates, and Add a core, Game setup, Add games and the
two controller screens split into columns. Under 600dp each keeps one column,
whichever way the device is turned. They were `layout-land` forks, so a
landscape phone in split screen got two columns squeezed into a narrow window and
a tablet held upright got one (review, 2026-10-16). Still on orientation: the
landscape spacing tokens (`values-land/dimens.xml`, for a short window) and the
sheet's side placement (`Sheet.place`).

## The Add-a-plugin screen reads its data off the main thread

`PluginCatalogActivity.render` draws what it has at once and reads everything else together on
a worker (`readSnapshot`): the stored catalogs, the plugins index, the installed bundles, each
repository's description and which catalogs exist or are stale. Reading the stored catalogs
verifies every cached manifest's signature, and the screen did that on the main thread while
opening, per redraw and once more per card for the installed bundles, which ANRed it on every
open (rig, Enginehost dev-289: input dispatching timed out waiting for the focus event; the ANR
is filed against the screen that was in front, PluginTrustActivity, because the new window had
not taken focus yet). A newer redraw drops an older read.

## Add a core has Install all

After the catalog's cards, when the chosen channel offers two or more cores that are not
installed, "Install all (N)" installs each one's newest build through the same path as its
card's button, then opens Cores. It approves nothing (each new core waits for its own
approval there) and asks first, with the red warning, when any of them cannot run sandboxed.
The channel stays Stable unless the person changes it, so the one-press path is the stable
set. It is not offered while the screen picks an engine for one game (tracker#23, the shape
droidtop's per-catalog Install all has, tracker#458).

## Updating Enginehost itself: a missed check retries, a stuck hand-over ends

The automatic pass (`PluginUpdateCheck`) runs when its interval has passed since the last pass
that reached the network, which its fetch of the release info stands for; a pass that did not
reach it is tried again an hour later rather than a whole interval later. Settings' Install
button hands the downloaded APK to the Android installer and then waits. A successful install
replaces the process, so Settings coming back to the front, or a minute with Settings still in
front, means the install did not happen: the button returns, with a line saying so. Before,
the "install unknown apps" round-trip came back with the button stuck on "Handing over to the
Android installer" (rig, Enginehost dev-269 to 289, tracker#401).
