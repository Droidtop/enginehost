# Enginehost UI decisions

## Controller focus on text links

Focusable text links use the shared row background and explicitly opt into
focus and click handling in `Widget.Enginehost.Link`. That keeps the Details
link's D-pad focus on the same raised fill and accent ring used by Enginehost's
other focusable controls. The landscape plugin-trust card uses the same
style on its Details link (`layout-land/item_plugin_trust.xml`).
