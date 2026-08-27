# Unified RPG Maker plugin

The pinned patch in this directory adapts thehatkid's mkxp-z Android port at
commit `468fe8128a40cc7d2cba4ac7fbe21a82787de255` into one separately installable
`engine: "rpgmaker"` plugin. XP, VX, and VX Ace contexts use native mkxp-z;
MV and MZ contexts use an in-place Android WebView runtime.

CI clones the upstream source, applies the reviewable patch, fetches the exact
dependencies selected by upstream, and builds the APK. No game is copied.

mkxp-z is GPL-2.0-or-later. The resulting plugin and integration patch are
distributed under GPL-2.0-or-later, with upstream copyright and `COPYING`
preserved. See the patch's `ENGINEHOST.md` for detailed provenance.
