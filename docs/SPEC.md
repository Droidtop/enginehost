# Enginehost UI decisions

## Engine plugin line selection

Engine version detection may select an installed plugin line only when its
major and minor components match the detected version. Missing or malformed
detection, and a detected line with no installed plugin, must not silently
fall back to a nearby line. The host can present nearest installed lines as
explicit alternatives, but choosing one requires a person to select it.

## Controller focus on text links

Focusable text links use the shared row background and explicitly opt into
focus and click handling in `Widget.Enginehost.Link`. That keeps the Details
link's D-pad focus on the same raised fill and accent ring used by Enginehost's
other focusable controls. The landscape plugin-trust card uses the same
style on its Details link (`layout-land/item_plugin_trust.xml`).

## An update that is not carried over says why

An approval carries to an update signed by the same key from the same
repository (`PluginTrustStore.carryApproval`). When a build of a bundle the
person approved before is waiting for approval instead, its Trust line says
which of the two changed: "a different key signed this build" or "this build
comes from a different repository" (`PluginTrustStore.changeSinceApproval`; the
last approved repository is kept per bundle for that). A first install says
nothing extra. In the catalog's older-builds list a build that is not newer
than the installed one is disabled and reads "Installed" or "Older than
installed", because the installer refuses a downgrade and a press that can only
fail is not offered.

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
(`Destination`): the Library (Home), Cores (the installed plugins, with
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
cores are the plugins that run a game engine; trust, update and error text keep
"plugin" for the signed bundle. A Cores screen showing one just-installed plugin
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

- **Core**: the build that runs the game and whether it is "newest that fits" or
  "fixed for this game". Its sheet lists "Newest that fits" and every installed
  build that could run the game (`PluginResolver.ranked` without an allowlist), with
  its sandbox state. A fixed build is the per-game override `pluginVersion`.
- **Update the core**: shown when the catalogs already on the device hold a newer
  build of the core and the game is not fixed to one; it installs through the quiet
  path Plugins' Update uses, and the same-key approval carries over. It is a row,
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
`GamePage` filling different view trees (`view_game_pane` is the page without its back
button), so a game's page is one piece of code. The pane's game survives a rotation.
Narrower windows keep opening the page as its own screen. Cores, Settings and the
controller screen keep their single column.
