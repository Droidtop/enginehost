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
