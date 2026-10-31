# Enginehost UI decisions

## 64-bit only: arm64-v8a and x86_64 (decided 2026-10-24, Droidtop/tracker#646)

Enginehost, its sandbox library and every core build for `arm64-v8a` and `x86_64`
and nothing else. Owner: "why do we have to care about 32-bit devices? The last
32 bit arm or x86 cpu was a decade and a half ago". The app's `abiFilters` name
the two. The native sandbox stops at an `#error` on any other architecture, so
there are no stubs left that only let a 32-bit build link. A bundle carries
exactly the two `lib/` directories (engine-bundle-format.md). This is about
Android ABIs only. Enginehost still recognises a PC game's 32-bit Windows or
Linux binaries ("32-bit PCs (x86)") when it says which games it cannot host.

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
- **Which build a launch uses:** per line, the newest build the game's `pluginVersion`
  allowlist accepts and the person has not denied, an approved build before one still waiting
  for approval (`PluginResolver.currentBuilds`); then lines are ranked as before. An older build
  runs only when the allowlist picks it, the newer ones are denied, or the newer one waits for
  approval. It never runs because the newest cannot serve the game: when the newest needs a
  component not approved yet, another line serves the game, or the launch offers the component
  with its licence. (#604: Goodbye Eternity ran `godot.v45.v2` 1.1.4, built before the pack-read
  fix, beside a fixed 1.2.2 that needed the unapproved Spine component.) Change core still lists
  every build that could run the game.
- **Catalog:** every build is installable, older ones included. Only the
  archive already installed reads "Installed".
- **Cores screen:** one card per line, showing its newest build; the line's
  older builds fold beneath it ("Older builds (N)"), one row each, newest first,
  with their own Approve (while waiting) and Remove (#620 E5). A card carries
  only the allowed badges: Official or Unofficial, and Full access where the core
  runs unsandboxed ("Sandboxed" is the absence of Full access, `TrustBadges`, the
  same on Add a core and the game page's build choice). It says its state only
  when that is not the normal one (waiting for approval, denied, old signature).
  Its buttons are only those that apply: Approve while the build waits, Remove
  always. Bundle id, origin, signer and Deny are in the Details sheet. Cards are
  added a few per frame (`RowBatches`), as on Add a core (#620 E1).
  Everything a card says (trust, approval state, licence text) is worked out
  off the main thread with the list. With every line's old and new builds
  installed after Update all (about sixty cards), working it out card by card
  on the main thread, while the installs kept the disk busy, ended in "isn't
  responding" (rig, 2026-10-23, Droidtop/tracker#604).
- **Home's update notices** sit in the header beside Add games, as tall as it:
  "N core updates" (a primary button, opening Cores) and, when there is one,
  "Enginehost <version>" (a secondary chip, opening Settings' update). The check
  answers seconds after launch; as a row above the list it pushed the list
  down under a tap meant for a game (same rig run). When one appears on a Home
  the pad has not moved on yet, it takes focus, so it is the first stop; once
  the pad has moved, it never takes focus away (#620 E2, which also found the
  notice's blue text on a blue button, and a white status-bar clock on the
  light page: the status bar now takes the page's colour and readable icons).
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
  set in the config creator and kept as the game's setup in Enginehost, or in its
  `enginehost.json` when the person saves it there (`contract.md`, "Where a game's
  config lives"). The allowlist names what is allowed and has no exclusion or
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

## Four destinations: Library, Cores, Controls, Settings

The app is organised around four destinations, always the same four
(`Destination`): the Library (Home), Cores (the installed cores, with updates
and Add), Controls (the button maps every engine reads, pad profiles and the
in-game menu button) and Settings. Controls was a row inside Settings; it is a
place of its own since Droidtop/tracker#644 (owner, 2026-10-24: "Four places:
Library, Cores, Controls, Settings"). Opened for one game or one engine (its
page, the in-game menu) the Controls screen is inside that flow and draws no
bar. Adding games is the Library's one primary action, in its header, not a
place. The destination screens draw a `DestinationBar` along the top of their
content, on every window size: the four tabs in a row between an L1 glyph at the
left end and an R1 glyph at the right, so the bar runs the way the shoulder
buttons move (owner, Droidtop/tracker#644, 2026-10-30: "move the enginehost tab
bar to the top or bottom, so it's more consistent with how it actually moves,
mapped to l1 and r1"; top was the preferred place). It replaced a bar along the
bottom of narrow windows and a rail down the left of wide or short ones. Home has
one layout for every window; the old landscape fork with its button sidebar is gone.

The pad changes destination with L1 and R1 (previous and next, wrapping); no
destination item takes focus, so the D-pad only ever moves within a screen's
content. Touch picks a tab, or presses the L1 or R1 glyph. The hint row does not
name L1/R1, because the bar prints them. Opening another destination
from Cores, Controls or Settings replaces that screen, so back always lands on the
Library. "Cores" is the destination's name and its heading, with the line that
cores are the cores that run a game engine; trust, update and error text keep
"plugin" for the signed bundle. A Cores screen showing one just-installed core
(`EXTRA_BUNDLE`) is opened inside a flow and draws no bar.

## A game's picture, from local sources only (Droidtop/tracker#240)

Every screen that shows a game's picture asks `GameArt`: a picture the person chose for the
game (Manage > Choose a picture, kept scaled down in Enginehost's own files), else the game's
own icon (`GameIcon`: the window icon its engine configures, or its executable's icon). Nothing
is fetched and nothing is made up: a game with neither wears a plate in its engine's colour with
its initial. Library rows show it at the start of the row and Home's shelf cards as a cover.
A row's picture is resolved once off the main thread and kept as a small thumbnail in the
app's cache, keyed by the game's path and the modification times of its folder and of the
chosen picture, with the newest in memory, so scrolling a long library reads no file on the
main thread. Manage > Use the game's own picture removes the chosen one.

## Settings > Appearance: the accent colour

Settings has an Appearance section with one row, Accent colour. Its sheet offers
Enginehost blue (the default; azure, droidtop's own fallback), Match my device (shown from
Android 12, the system's dynamic accent) and My own colour (a hue slider with the colour it
comes out as beside it). Every choice keeps its hue and is moved in lightness until it reads
at 4.5:1 on the ground, in light and in dark. A launch that names an `accent`
(docs/contract.md) draws that session in it instead, and the row says so. A change redraws
every open screen when it is next shown. How it is drawn is docs/ui-v2.md, "The accent at run
time".

## A launch shows only the game and what it needs

A launch, from another app (`dev.enginehost.LAUNCH`) or from Play, shows the game and the
screens its plan needs on the way: the launch screen, the catalog filtered to the game,
the trust screen for one core, Game setup, the unsandboxed question. None of them is one
of the four places, and none leads into one, so a game started from droidtop never
leaves the person in Enginehost's library (owner, 2026-10-24, Droidtop/tracker#644:
"Launched from droidtop: only the game, with sheets as needed"). A catalog a launch
opened installs a newer build of an installed core on its own card ("Update"), where a
catalog opened from Cores sends updates to Cores. The missing-core detour to the catalog
stays as it is (owner: "that redirect is valid and as expected").

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

While nothing narrows the list and the library holds more games than one shelf,
Home shows two shelves above it: Continue playing (the games played last, from
`played_at`) and Favourites. A library a shelf could hold whole is the list alone,
and the list is in name order, so the shelf is not a copy of the list's top
(owner's test, 2026-10-24: "the library lists a game twice"). Each is a row of
at most `LibraryBrowser.SHELF_SIZE` game cards; a shelf with no games is not
drawn, and a window under 480dp tall shows only the first that has games, so a
handheld held sideways keeps its list. A card opens the game's screen as a
library row does, Y plays it, and the pad reaches the shelves by pressing up from
the list. A shelf is a saved query plus a limit, not a second taxonomy: Favourites
is the `favourite` column (database version 2) that a game's screen toggles, and
the Filters sheet can show only favourites. Engine shelves are the existing engine
filter; there is no separate list of them.

**Add games** sits beside Home's title and answers Start from anywhere on Home, and the
hint row names it: at the header's far right it was hard to find (owner's test,
2026-10-24).

## Core cards and installs (owner's test, 2026-10-24)

- **A core card names engines, not runtimes.** A line whose name says which engine it is
  (RPG Maker VX Ace, KiriKiri 2, CMVS PS3) shows no version: those numbers are the
  runtime's own (RGSS 1.0/2.0/3.1). The language runtimes a core embeds (Ruby, Python,
  liblcf) are not named; a component a game's author chose (Spine) is. Lines named only by
  family (Godot, Ren'Py) keep their versions. `EngineNames` decides this for every screen
  that lists a core.
- **A catalog a launch opened says why:** "<game> needs one of these cores to play", and a
  refresh that worked does not replace that line with "every core is up to date".
- **Install shows its size** ("Install · 106 MB") when the release lists one, says
  "Installing" only once the unsandboxed warning (if any) is answered, and fills the card's
  bar with the download's share done while its size is known (`InstallStatus`).

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
  in `config.variants`, and the screen-size variant a desktop build has
  (`large`, with `pc`) instead of the device's (`small`/`phone`,
  `medium`/`tablet`, `tv`); `touch` stays (owner, 2026-10-23: "Desktop flags,
  keep touch layouts"). The engine keeps its own Android flags. The size
  variants stayed too until 2026-10-31, and on the RP5's 5.5-inch screen they
  ran Ren'Py's phone overrides of a desktop game's GUI template, which no
  desktop game lays out. The text was larger and the menus narrower; labels
  wrapped under their buttons (Love & Sex: Second Base). The core's
  ENGINEHOST.md has the details.
- **Godot:** `OS.get_name()` and the feature tags answer the platform the game
  was exported for (`ENGINEHOST_GODOT_PLATFORM`, docs/engine-bundle-format.md).
- **Any other core** whose game refuses Android gets the same treatment when
  it is found.

A game's options can still choose Android: `"platform": "android"`.

## Links a game opens (decided 2026-10-23)

The owner, 2026-10-23: "Enginehost should parse the link type (safely), and then OFFER to open
it. A discord link gets offered to discord, etc" (Droidtop/tracker#635).

A core never opens a link itself. Every engine's own route goes to `EngineHost.openLink(url)`:
SDL `openURL`, Godot `OS.shell_open`, Ren'Py `webbrowser` and `renpy.open_url`, KiriKiri
`shellExecute`, Ruffle `navigateToURL`, and a WebView's external navigation and `window.open`.
That route also keeps working under the clamp: an isolated core asks through the host's
callback, and Enginehost's own code does the opening.

A core on the activity transport (Ren'Py, KiriKiri, NScripter, AGS, mkxp-z) runs its own Activity
and has no `EngineHost`. It calls `dev.enginehost.ActivityLinks.offer(Activity, String)` by name
instead: the same check and offer. The owner chose this bridge on 2026-10-24 as that transport's
route until each core moves onto the plugin transport, where `openLink` replaces it.

**Checked strictly (`GameLinks`).**
- A length cap, no control characters or backslashes, and never `Intent.parseUri`.
- A web link must name a host, with no user name in front of it and no bracketed IP. The host
  must convert cleanly to ASCII; an internationalized one is shown decoded and in ASCII, so a
  lookalike shows as one.
- Only `https`, `http`, `mailto` and the app schemes `assets/link-kinds.json` lists pass. Anything
  else is refused, and the person is told so: `intent:`, `file:`, `content:`, `javascript:`,
  `data:`, `steam://run/...`.

**Named by data.** `assets/link-kinds.json` says what a link is, by scheme or by domain and path:
Discord invites and channels, YouTube, Steam, itch.io, Patreon, X, Reddit. Any other web link is
a web page; `mailto` is an email.

**Offered every time (`GameLinkOffer`).** A sheet says what the game wants to open and shows the
link. Its choices, in order:
1. **Cancel**, first, as the safe choice on every warning sheet;
2. **Open in Discord** (or YouTube, ...), when an installed app of that kind opens the link;
3. **Open in browser** (a browser, not whichever app claims the link), or **Open in email**;
4. **Copy link**.

There is no "always". One offer is shown at a time; a link asked for while one is showing is
dropped. On a device with a second screen the chosen app opens on the screen the game is not on.

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
  (`GameTroubleActivity`): Keep waiting, End game, Report a problem. The rule
  is `BlackScreenRule`, about what the person sees rather than the engine's
  surface (2026-10-30, Droidtop/tracker#821: Ren'Py was asked about with its
  presplash on screen, and ran 55 seconds later):
  - the game's own loading picture (`LoadingCover`) is not a black screen;
  - every progress report the engine sends (`LoadingProgress`) starts the 45
    seconds again;
  - the first launch of a core build, which unpacks its runtime and compiles
    the game's scripts first, has 150 seconds (`CoreLaunches`, decided in the
    main process and passed in the launch);
  - the notice stops watching after 180 seconds without a frame or a report,
    asking once first when it never asked.

  An engine
  with no SurfaceView is not watched, and a game whose surface cannot be read
  is never called black. Both steps are host events ("watching the game's
  screen", "the game drew its first frame"), which the core harness waits on
  instead of a screenshot: the loading screen passed for the Ren'Py test
  game's first frame while the game was still black behind it.
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
- **Android stopped the game (owner, 2026-10-29, Droidtop/tracker#27).** A game
  whose process Android ended from outside is a failure, not a clean close: the
  launch screen says "Android stopped the game: it needed the memory." (low
  memory), "...: a system service it was using stopped." (a provider it used
  died, as Google Play services' font provider did to WebView on an emulator) or
  "...: it was using too much of the device." (excessive resource use), with Try
  again and Report, and `GAME_ENDED` reports `crashed`. It was "game ended
  (exited)" and a silent return to the library. The person swiping it away and
  the game's own exit stay clean (`CrashWatch.stoppedByAndroid`).
- **Report a problem** is also in the in-game menu (review findings 170 and
  179), and the report can be saved as a text file, besides Share, Copy and
  posting it on GitHub; nothing leaves the device unless the person picks one.
- **A game's report is that game's (#620 E4).** Engine, version and core come
  from the config the game's launch runs on, detected or its own. The host's
  events are only those of the game's last session (from shortly before its last
  start until the next game started; the ring names no game, so time ties a line
  to it), and the engine's own events only when the last session was this game's.
  Share, Copy, Save and the GitHub button come first; what the report includes
  folds under "What the report includes".
- **Reports go to the tracker.** The GitHub button opens a new issue on
  Droidtop/tracker (owner, 2026-10-22: "always the tracker"), no longer the
  separate enginehost-reports repository. Only a title (engine and symptom)
  is in the address; the report itself is copied for the person to paste.

## A game's own loading screen (decided 2026-10-24, Droidtop/tracker#640)

The owner: "the RenPy loading screens would be a nice thing to implement, too. Right now, we just
use the android splash". The runtime's window used to open black with a "Loading <game>" bar until
the engine drew; it now opens on the game's own loading picture.

- **The host draws it, before the core loads.** `RuntimeActivity` reads and decodes the picture
  before the sandbox closes (after the lockdown nothing opens a file except through the broker,
  the host included) and puts it over the window before any core code runs
  (`LoadingCover`). The core never sees the file and needs no access to it.
- **One lookup, one table (`LoadingImages`): each engine family names where its picture is.**
  Plain files in the game folder only; nothing inside a pack is read.
  - Ren'Py: `android-presplash.*` beside the game, else `game/presplash.*`. With a background and a
    foreground picture in `game/`, a progress fill as Ren'Py draws it: the background whole, the
    foreground over it from the left up to the fraction loaded. Ren'Py's source names them
    `presplash_background` and `presplash_foreground`; the hyphenated spelling is read too. The
    fill is Ren'Py's own progress (2026-10-30, Droidtop/tracker#821): the core reports the
    fraction its desktop presplash would draw (each `presplash.pump_window` call over the script
    files plus Ren'Py's fixed count, as each Ren'Py version counts it) through the named bridge
    `dev.enginehost.ActivityLoading.progress`, and the host draws exactly that. Nothing is
    guessed: until the core reports, the bar is empty, as Ren'Py draws it before its first pump.
    The estimate that eased towards the end showed a 176-second start already filled. The first
    frame, not the bar, ends it.
  - Godot: the project's `application/boot_splash/image` (and `boot_splash/bg_color`), when
    `project.godot` and the image are plain files. A game shipped as a `.pck`, and one with
    `boot_splash/show_image=false`, are skipped.
  - RPG Maker MV and MZ: `img/system/Loading.png` (under `www/` when deployed so). Encrypted
    `.rpgmvp` is skipped.
  - KiriKiri: skipped. Its pictures are inside `.xp3` archives and it has no plain-file location.
- **Fallback.** No such picture, or one that cannot be decoded: the game's box art (the game's icon,
  `GameIcon`) over a dark background; never a screenshot. A game with neither keeps the plain dark
  window and the "Loading" bar. The bar is not drawn over a game's own picture.
- **It goes at the core's first frame.** `RuntimeLoadingNotice`, which already watches the game's
  surface, lifts it when any frame, black or not, has been posted. A window with no surface to
  watch (a web view, an isolated runtime) loses it after 5 seconds, since nothing could say when to
  lift it and it must not hide a game for good.
- **An engine with an Activity of its own gets the same cover** (2026-10-28). Ren'Py and KiriKiri run on
  the activity transport and never show `RuntimeActivity`, and Ren'Py drew RAPT's generic presplash from
  its runtime APK. `EnginehostComponentFactory` reads the picture from the launch's intent before it locks
  the process down (`LoadingCover.prepareForEngineActivity`), and `RuntimeInputInstaller` puts it over the
  core's Activity when it starts, before the loading notice, which lifts it at the first frame as before.
  One table, one cover, two places it goes up. KiriKiri, which has no plain-file picture, gets the box art.

## A game's text reaches the host in one format (decided 2026-10-28, Droidtop/tracker#490)

Reading aloud and translating are Enginehost's, once, for every core. A core only reports what
its engine shows (docs/text-services.md).

- **One format, one call.** `ehtext/1` events through `EngineHost.emitText(json)`. An isolated
  runtime sends them over a oneway binder call, so the engine never waits on the host. There is
  no second transport: a native or WebView engine forwards to the same call.
- **The engine prints the translation.** `EngineHost.replaceText(event)` answers synchronously,
  from what the host already holds, and never waits on a network (owner, 2026-10-14). It
  answers null, meaning print the original, until a translator is chosen.
- **Untrusted, capped, never logged.** Each event is rate-capped at 120 a second (bursts of
  240), size-capped at 16 KiB, read whole or dropped, and stripped of control characters. Game
  text never goes into logs or host events.
- **Read aloud, with the device's voices, when the person turns it on** (2026-10-28). Settings,
  "Read text aloud", off by default: a voice per character, picked from a hash of the speaker so it
  stays the same; the game's own voiced lines are not read; moving on stops the line. The planner hands
  the choice to the runtime with the launch. The first emitter is CatSystem2, an isolated core, so it
  proves the binder path.
- **Voices on the device, a voice of their own per character, female and male** (2026-10-29). The
  owner: "Remember we also want per-character voices (at least male and female)", and "I think the
  engine should still be in the build, that way they can supply their own voices". So:
  - sherpa-onnx is in the APK (v1.13.8, pinned by SHA-256, 64-bit only).
  - Voice models are downloads on request, pinned by SHA-256 (Kokoro English, Kokoro in many
    languages), or archives the person picks through the system picker. Both are installed the same way.
  - A model's voices and their genders are read from its own ONNX metadata, never from a list kept in
    Enginehost, so a person's own model works the same as a download.
  - Each character gets a stable voice for the language being read, by default. Settings, "Character
    voices", sets any character to a female voice, a male voice, or one voice by name.
  - Synthesis runs in the main process (`TextServicesService`), not the game's: the runtime's sandbox
    cannot open the model, and the model's memory stays out of the game's.
  - "This device's voices" stays as the choice that needs no download (docs/text-services.md, "Voices
    on the device").
- **Translated, with the person's own service, when they turn it on** (2026-10-28). Settings,
  "Translate games" and "Translator": an OpenAI-compatible endpoint, model, language and key (Keystore).
  The runtime has no network, so lines go to `TextServicesService` in the main process; answers are kept
  per game and line (`TranslationCache`) so a line is sent once. An engine that asks `replaceText` prints
  the translation; one that never asks gets it in a band at the bottom, the fallback.
- **For the rig.** `debug.enginehost.text 1` draws the newest six events over the game.
- **A core on the activity transport sends through `ActivityText`** (owner, 2026-10-28, option 1
  on #490: "1, yeah"). Ren'Py runs its own Activity and has no `EngineHost`, so it calls
  `dev.enginehost.ActivityText.emit(Activity, String)` and `replace(String)` by name: the same
  `GameText` as `emitText` and `replaceText`, the same kind of named bridge as `ActivityLinks`.
  Ren'Py's injected script reports every line from 7.3 to 8.5 through pyjnius, and prints the
  translation in its own text box through `config.say_menu_text_filter`. The bridge is deleted
  with `ActivityLinks` when Ren'Py moves onto the plugin transport. KiriKiri, also on the
  activity transport, has no emitter yet.

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

## A row says what is unusual, else when it was played (decided 2026-10-28, Droidtop/tracker#620 E10)

Every row on Home said "Android-runnable · Runs here", 22 identical lines. A line that is the same on every
row tells the reader nothing, so a row's second line now names only what stands out: the builds and
"Needs a core" or "Not supported here" for a game that does not run here, and "unconfirmed" for a guessed
engine. Otherwise it says when the game was last played, or "Not played yet". The size shows only while
the list is sorted by size. The Filters button counts the filters in use ("Filters (2)") and leaves out the
search, which has its own field, and the sort. The sheet keeps its Search row, because the pad never
reaches Home's search field. The game page drops its "Ready" line; the status shows only when something
stands in the way of Play. Not done: the review's Engine and Platform chips in place of their own sheets.

## Settings: one row for engines' save folders; callers named plainly (decided 2026-10-28, Droidtop/tracker#620 E9)

Settings listed every installed engine under Saves, each reading "Shared folder" (15 rows on the rig).
Saves now has the save folder, one row "Engines that save elsewhere" (it names the engines with a folder of
their own, or "None"), and "Browse from". That row's sheet lists every engine with its folder; picking one
offers Choose a folder and, for an engine with its own, Use the save folder. App launch access, the launch
screen and the scan screen name a caller through `CallerLabels.of`, the one place that says how: adb's
"Shell" package reads "adb or a script", and a caller with no package reads "An unrecognized caller".

## Game setup opens on what was detected (decided 2026-10-28, Droidtop/tracker#620 E13)

Game setup showed engine, variant, version, core builds and entry file for a game detected correctly,
eight fields before any option. When detection fills the engine and version, those fields fold behind
"Change what was detected", and the line above says "Nothing to change: Play runs it as detected". The
fold stays open when the version was not found, after a core is chosen by hand, and it opens when Save or
Test refuses the setup, so the field at fault is in view. Focus moves to the link when the fold closes
and to the first field when it opens. Not done: Add option as short titles with the description on Y.

A folder chosen in Game setup is read by its path, the way every launch reads a game (decided
2026-10-29, Droidtop/tracker#768). The picker's choice is resolved to a path once. A folder on a
provider with no path, such as a cloud drive, is refused, and all-files access is asked for when it is
missing. Loading the setup, listing the folder for the entry file, and saving all run off the main
thread. Before this, Game setup kept a second set of branches for the picker's tree, which queried
the provider on the main thread.

## The game page's sections

A game's page reads, top to bottom, in the owner's order (Droidtop/tracker#644,
2026-10-24: "Play, Run with, Controls, Saves, Game setup, Manage"): Play, then rows read
off the main thread after the page itself (`GameSectionsLoader`, drawn by
`GameSectionsView`), then Game setup and Manage.

- **Run with**: the build that runs the game and whether it is "newest that fits" or
  "fixed for this game". Its sheet lists "Newest that fits" and every installed
  build that could run the game (`PluginResolver.ranked` without an allowlist), with
  its sandbox state. A fixed build is the per-game override `pluginVersion`.
- **Update the core**: shown when the catalogs already on the device hold a newer
  build of the core and the game is not fixed to one; it installs through the quiet
  path Cores' Update uses, and the same-key approval carries over. It is a row,
  not the primary button, so Play stays Play while an update waits.
- **Controls**: whether the game has its own buttons or follows the engine's; it opens
  the Controls screen on "This game". Shown when a core resolves.
- **Saves**: where the saves are, when they last changed and how big they are
  (a bounded look at the save folder). For an engine that saves beside the game the
  place is the game's folder and nothing is measured. Its sheet manages them
  (Droidtop/tracker#238, `SaveBackups`, `SavesSheet`):
  - **Back up now** copies the save folders into a new folder named for the moment,
    under `<save location>/save-backups/<game>-<digest>/`. It never writes into a backup
    that exists.
  - **Restore a backup** lists them newest first with their sizes. It asks first, backs
    the current saves up, then copies the backup's files over them. Saves the backup
    doesn't have are kept, and nothing is deleted.
  - **Copy to a folder** puts a copy in a new "<game> saves" folder inside a folder the
    person picks.
  - Symbolic links are neither followed nor copied, and every copy runs off the main
    thread with a running file count.
  - What counts as saves: the game's save folder, or, for an engine that saves beside the
    game, only the folders its core declares it writes there (`gameWrites`, such as
    KiriKiri's `savedata`). With none declared the sheet says where the saves are and
    offers no copy, because the game's own files are never copied.
- **Options**: one row per option the core declares (`DeclaredOptionsReader`),
  showing the value this game runs on and its source: engine default, the game's own
  setting (its `enginehost.json`), or this game (an override). Boolean, choice,
  number and string options are edited here and written as overrides (`options.<name>`);
  a "Back to the default" choice takes the override back. Path, file and repeated options are
  set in Game setup, which edits the game's own file. They come after Saves so that a
  core with many of them never pushes Controls under the fold at 1920x1080, the reason
  Controls once came first (Droidtop/tracker#618).
- **History**: time played, starts, when it last started and how the last session ended.
- **Manage**, a button under Game setup: one sheet with the game's favourite mark, Report a
  problem, and Remove from list. They were three buttons of their own.

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

Controls are named as the pad prints them: "A", "L2", "Start", "D-pad ↑", "Left stick ←",
never the platform's constants (`BUTTON_A`, `AXIS_LTRIGGER +`). `PadNames` is the one place
that names them, for the controller screen, the in-game Controls sheet, the host menu shortcut
and the profile page; a keyboard key keeps its own name, made readable (#620 E8). "Bypass" is
not a word the person sees: the switch reads "Let the game read the pad itself".

Controls are drawn as glyphs, mapped to the controller's profile (owner, 2026-10-23: "Drawn
button glyphs, mapped to the controller profiles"). A pad's profile says which family's labels it
prints (Xbox ABXY, PlayStation cross/circle/square/triangle, Nintendo with A on the right), from
what the pad reports or as the person sets it, and every control surface draws that family's
glyph for the button that does the job. `PadNames` stays the text for keyboard keys and for
screen readers.
The family comes from the pad's USB or Bluetooth id through SDL's controller list
(`PadFamilies`, `SdlControllerIds`, third_party/sdl_controller_list), and the profile page's
"Button labels" sets it for a pad the list does not know or gets wrong (as the pad says, Xbox,
PlayStation, Nintendo). A pad nobody has said anything about is drawn as Xbox, and the page says
so. Glyphs are drawn in the text colour (`PadGlyphDrawable`), and each sits over its name in the
text, so a screen reader reads the name.

Which map is being edited is one short list with the chosen one filled and checked. Opened for
a game it is "This game", "This engine: <name>" and "Other maps" (a sheet with All engines and
the other engines); from Settings it is All engines and each installed engine. Engines that read
the pad themselves are not offered, since nothing here reaches them; one line names them. While
a button or the shortcut is being captured, a Cancel button shows and Back cancels the capture
instead of leaving the screen. The shortcut, profile and "read the pad itself" rows are settings,
so they use the secondary button style; a disabled filled button's label uses the secondary text
colour, readable on its grey fill.

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

## Release channels (decided 2026-10-31)

The owner (Droidtop/tracker#28): "unstable is for branches that have never had a stable core, or
for major rewrites/etc. Testing is for revisions to branches that have had stable cores (before
they're tested). Stable is for tested cores". CI publishes every green build of a line to unstable
until the line has a stable release and to testing after; `scripts/publish-engine-release.sh` makes
that choice for every plugin repository, and `scripts/promote-plugin-release.py` refuses an
unstable tag for a line with a stable release. A core reaches stable once a game loads a save with
it on the console. docs/plugin-catalog.md, "Publishing: how a bundle becomes a release", has the
tags and the rest.

## Add a core has Install all

After the catalog's cards, when the chosen channel offers two or more cores that are not
installed, "Install all (N)" installs each one's newest build through the same path as its
card's button, then opens Cores. It approves nothing (each new core waits for its own
approval there) and asks first, with the red warning, when any of them cannot run sandboxed.
The channel stays Stable unless the person changes it, so the one-press path is the stable
set. It is not offered while the screen picks an engine for one game (tracker#23, the shape
droidtop's per-catalog Install all has, tracker#458).

Add a core lists only cores that are not installed (#620 E6). Updates live in one place, Cores
with its Update all: a refresh here says how many are waiting there, and a newer build of an
installed line reads "Update in Cores" and opens it. The installed lines fold under "Other builds
of your cores (N)", built when opened, so a second build of a line is still a tap away. Sources
and Channel are one "Where cores come from" row. While the screen picks an engine for one game,
or shows one game's needs, every matching card shows as before.

## Updating Enginehost itself: a missed check retries, a stuck hand-over ends

The automatic pass (`PluginUpdateCheck`) runs when its interval has passed since the last pass
that reached the network, which its fetch of the release info stands for; a pass that did not
reach it is tried again an hour later rather than a whole interval later. Settings' Install
button hands the downloaded APK to the Android installer and then waits. A successful install
replaces the process, so Settings coming back to the front, or a minute with Settings still in
front, means the install did not happen: the button returns, with a line saying so. Before,
the "install unknown apps" round-trip came back with the button stuck on "Handing over to the
Android installer" (rig, Enginehost dev-269 to 289, tracker#401).

## Anonymous model ratings: built, switched off (decided 2026-10-28, Droidtop/tracker#491)

The owner, 2026-10-28: "build the system, but don't enable it yet. That way there's less to build
later". The whole opt-in system is built (`SharedStats`, `StatsReport`, `StatsAggregate`,
`StatsSheets`), with an empty endpoint and aggregate URL: while the endpoint is empty, no row of it
shows and nothing is sent. `debug.enginehost.stats 1` shows it for the rig, and then a report goes
only to the local log, marked unsent. What a report holds is docs/stats-schema.md.

- **Asked once, safe answer first.** The first time the person sets up a translator or turns on
  read aloud: "Share how you rate models?", with Don't share leading. Settings > Privacy changes it.
  Nothing is queued while it is off.
- **One report per rating, never on a timer.** Rating is offered only to someone who chose to share.
- **The service is a class, never an address.** Known services by name, otherwise
  `local-network` or `other-service`: the address of someone's own server can identify them.
  Purpose is a fixed list, so no free text names a game except the comment the person writes.
- **What went is shown.** Settings > Privacy > What was shared lists every report as it went.
- **Aggregates need 10 ratings.** Below that, the picker shows nothing for the model.
- **Waiting on the server (#364):** where the endpoint runs and how long raw reports are kept.

## droidtop, suggested once (decided 2026-10-29, Droidtop/tracker#755)

The owner, 2026-10-29: "Can also cross-advertise droidtop in enginehost". droidtop offers
Enginehost from its side (#754); Enginehost offers droidtop where it helps, without nagging
(`DroidtopOffer`).

- **Where:** one line, in two places only. The empty library's first-run steps show it under
  "Add a folder of games" and "Install a core", and Settings > About has a row for it. The line
  says what droidtop does for the person ("droidtop keeps every game on this device in one
  library, these engine games among them, and starts each in the right app"). Its action names
  the source: "Get droidtop from GitHub" opens droidtop's releases page in the browser.
  Sources droidtop-platforms lists for droidtop under #754 join it there.
- **Installed:** when droidtop is installed and signed with its pinned certificate
  (`DroidtopLink.isInstalled`, the check droidtop's calls already pass), the action is "Open
  droidtop" and opens it. An app that only took droidtop's package name gets "Get" instead.
  Both screens check again whenever they come to the front.
- **Never** a pop-up, never at launch, never during a game.
- **For good:** Settings > About's "Suggest droidtop" switch, on until turned off, removes the
  line from both places. While it is off nothing runs for it, not even the check for droidtop
  (DESIGN-LANGUAGE.md: a disabled feature runs no code), and turning it back on is the only way
  it returns.

## Work off the main thread goes through `Async` (decided 2026-10-29, Droidtop/tracker#768)

File, database, network and SAF work a screen needs runs through `Async`, the one mechanism for it.
`Async.run` is for work with nothing to show. `Async.load(owner, work, failed) { show }` is for work
whose result a screen draws. `Async.onMain(owner)` is for a callback on another thread that has to
touch views. All three share one pool. The part that touches views runs only while the owner's
screen is alive (not destroyed or finishing), so a result that arrives after the person has left is
dropped. Before this, about a hundred hand-rolled `Thread { ... runOnUiThread { ... } }` blocks each
made their own check, and many made none (code quality review, #768).

Workers that block for a whole run keep their own named threads or single-thread executors, where
their order matters. These are:
- the isolated runtime's audio pump and frame loop;
- the watchdog;
- the scan;
- the library, art, voice, translation and host-event queues;
- the bounded wait for droidtop's answer about saves (`DroidtopSaves.withDeadline`).

## Rig switches exist only in the debug build (decided 2026-10-29, Droidtop/tracker#768)

The `debug.enginehost.*` properties the rig sets with adb (`lockdown`, `learn`, `audit`,
`npeprobe`, `stall`, `text`, `stats`) are read by `DebugProperty`, and only the debug build has
one that reads them (`app/src/debug`). The release build's (`app/src/release`) answers every
name as unset, so a release build carries no switch: none can force a learning run, which lets
a core run with its filter only recording, or loosen the sandbox in any other way, whatever is
set on the device. The code quality review found them shipping in release builds. A rig check
that needs a switch runs on `enginehost-latest-debug.apk`, which every build publishes beside
the release APK.

## One HTTP helper for servers that are not GitHub (decided 2026-10-29, Droidtop/tracker#768)

Voice model downloads, the translator and the stats endpoint go through `Http`, and GitHub traffic stays
on the shared client (`GithubClients`). Before this, each built its own connection, with its own
timeouts and User-Agent and a catch that made every failure null. `Http` works as follows:
- It follows redirects by hand, at most five, and only to https. Voice downloads used to follow any
  redirect, and the pinned digest does not stop a request from going to a plain-http host.
- It refuses plain http unless the caller allows it. Only the translator does, for a server on the
  person's own network.
- It never sends an Authorization header over plain http.
- Every read has a bound.
- A failure is an `Http.Failure` that says what went wrong.

## Only v1 signatures are verified (decided 2026-10-29, Droidtop/tracker#768)

The legacy raw-key bundle signature has no verifier any more. Every shipped build pins the
signing root, so the legacy path was already unreachable there. `LegacyBundleSignatures.kt`, the
root-certified repository key documents and the developer's key went with it (code quality
review, #768).
- A bundle or catalog entry that carries only the legacy signature is refused, and is not offered.
- A core installed with it does not start, and the launch says to remove it and install it again.
  Its card on Cores offers **Reinstall** when the cached catalogs hold a build of its line (its
  newest, the same version re-signed included). Reinstall installs that build first and then
  removes the old one, so a failed download leaves what was there. With no such build the card
  offers Remove alone (Droidtop/tracker#774: every core on the AVD said "old signature" with no
  way forward).
- A `.sig` beside a JWS, and a manifest's `signing` block, are passed over.

What a repository a person added is verified to is unchanged: the key they pinned for it.
docs/engine-bundle-format.md, "Legacy signatures".
## TyranoScript runs on the HTML core (decided 2026-10-28, Droidtop/tracker#637)

A TyranoScript game is a web page: `index.html`, the `tyrano/` engine folder and `data/`. The
shared database's `tyranoscript` row maps it to the HTML core (`html`, `compiled-html`), so no
core of its own is built. In the HTML core's WebView there is no Node, so Tyrano takes its
browser path, and its `configSave` of `webstorage` (its template's default) or
`webstorage_compress` saves through `localStorage`, which the HTML core keeps in the game's save
folder. The detector reads `data/system/Config.tjs` the way Tyrano's own `compileConfig` does:
`System.title` names the game and its save folder.

A game whose `configSave` is `file` writes `.sav` files through its PC build's
`window.studio_api.fs`, which no WebView has: it runs, but Tyrano says saving does not work. The
detection says so before it is played. Answering that API from the HTML core is later work, not
a reason to hold back the games that save through `localStorage`.
