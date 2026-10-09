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
