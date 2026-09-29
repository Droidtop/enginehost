# Enginehost design decisions

## Sandbox badge (2026-09-28)

The installed-plugins (`item_plugin_trust.xml`) and catalog-release (`item_release.xml`) cards
now show a second small badge beside the provenance badge, reading from the existing
`isolatable` flag: `InstalledPlugin.isolatable` (post-install) and
`EngineBundleManifest.isolatable` (pre-install). Two states only: "Sandboxed" when
`isolatable == true`, "Unsandboxed" when `false`. Positive state reuses
`eh_official_container` / `eh_on_official_container`; warning state reuses
`eh_caution_container` / `eh_on_caution_container`. No new color token added.

Refs Droidtop/tracker#23. Part of Droidtop/tracker#24 (Enginehost UI v2, docs/ui-v2.md §4).
