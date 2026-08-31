# RPG Maker plugin-family layout

RPG Maker is one Enginehost plugin family, not one interpreter. Every engine
bundle below declares `engine: rpgmaker`; Enginehost's normal capability
resolver combines all installed bundles into that family. No dispatcher bundle
or umbrella source repository is required.

## Runtime repositories

| Repository | Upstream/source ownership | Contexts | Runtime-version meaning |
| --- | --- | --- | --- |
| `enginehost-rpgmaker-mkxp-z-plugin` | fork of `mkxp-z/mkxp-z` | `xp`, `vx`, `vxace` | RGSS generation/revision and Ruby ABI line |
| `enginehost-rpgmaker-easyrpg-plugin` | fork of `EasyRPG/Player` | `2000`, `2003` | RPG_RT/Player compatibility line |
| `enginehost-rpgmaker-mv-mz-plugin` | enginehost WebView/Chromium shell; game supplies its deployed JS engine | `mv`, `mz` | bundled web-shell compatibility plus explicitly tested RPG Maker runtime versions |

RPG Maker Unite is not part of the initial family. It is Unity-based and does
not share the portable data/runtime boundary of the generations above. It must
not be advertised until a credible in-place native host exists.

Each repository is independently installable and uses the standard engine-fork
branch model where an upstream exists:

- the upstream-facing branch follows the engine project;
- `plugin-core` contains only the portable Android/enginehost changeset;
- `plugin/<compatibility-line>` starts at a selected upstream revision and
  merges `plugin-core`;
- shared fixes originate in `plugin-core` and are merged into applicable
  compatibility lines;
- runtime-specific compatibility fixes stay on their version line or are
  backported deliberately.

The MV/MZ shell has no single upstream RPG Maker engine repository because the
deployed engine JavaScript ships with each game. Its main branch is therefore
the enginehost implementation, while version branches pin the Android web
runtime and compatibility shims used for declared MV/MZ spans.

## Configuration contract

Examples use numeric `engineVersion` values because the host version grammar is
dotted numeric. The product generation belongs in `engineContext`.

```json
{
  "engine": "rpgmaker",
  "engineContext": "vxace",
  "engineVersion": "3.01",
  "options": {
    "rubyVersion": "1.9.2",
    "compatibilityProfile": "mgq-paradox-3.06-v1"
  }
}
```

`engineVersion` identifies the game engine/runtime target. It does not select
a bundle by ID. Installed bundles declare exact versions and explicit
support spans, and enginehost resolves the best allowed capability. The
optional top-level `pluginVersion` remains only a plugin-release allowlist.

Runtime-owned options may include Ruby ABI, RTP search paths, entry point,
renderer, audio, fonts, script preload/postload, input, and named compatibility
profiles. Plugins must validate their own options and must not allow them to
change `engine`, `engineContext`, `engineVersion`, the resolved capability, or
the authoritative game path.

## Initial compatibility lines

- mkxp-z: `plugin/vxace-rgss3-ruby31`, followed by a Ruby 1.9-compatible RGSS3
  line for games such as MGQ Paradox; XP and VX receive explicit lines as their
  Ruby/engine behavior is verified.
- EasyRPG: one current Player line for 2000/2003, then older compatibility spans
  only where real games require them.
- MV/MZ: separate co-installable bundles when their WebView/Chromium or shim
  requirements diverge. A single bundle may declare both contexts only when it
  genuinely implements and tests both.

Capability declarations must describe observed compatibility, not the broadest
generation an underlying project nominally recognizes.
