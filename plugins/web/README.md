# enginehost Web runtime plugins

One Android package exposes two independent enginehost plugin activities:

- `engine: "twine"` runs compiled Twine HTML stories in place. Capability
  `twine-2.12-browser` covers Twine 2.x through 2.12.0.
- `engine: "flash_air"` runs a selected SWF, or the `<content>` SWF from an
  Adobe AIR captive-runtime descriptor, through Ruffle 0.4.1.

Neither activity imports or copies a game. `execFile`, when supplied, is
canonicalized and rejected if it escapes the game folder.

## Attribution

The Flash runtime bundles the official Ruffle 0.4.1 self-hosted Web release at
CI build time. Ruffle is copyright its contributors and dual-licensed under
MIT or Apache-2.0; its upstream distribution and notices are preserved in the
APK assets. See <https://github.com/ruffle-rs/ruffle>.

Twine exports contain their own selected story-format runtime. This plugin does
not redistribute Twine or any story format; it supplies Android's system
WebView around the caller's compiled story.

Android builds run only in GitHub Actions. Normal Android filesystem sandboxing
still applies until a storage-access policy is explicitly approved.
