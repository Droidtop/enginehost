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
