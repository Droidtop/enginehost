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

## Run Time Packages (decided 2026-09-03, not yet built)

XP, VX, VX Ace, 2000 and 2003 games commonly depend on the generation's Run
Time Package (RTP) for default graphics and audio; MV and MZ have none. The
RTPs are Gotcha Gotcha Games' copyrighted material under their own licence,
so enginehost neither bundles them nor downloads them on the user's behalf.

What enginehost does instead is own them once for the whole family:

- One **RTP store**, kept by the host, with one slot per generation
  (`2000`, `2003`, `xp`, `vx`, `vxace`). A game whose plugin reports a
  missing RTP, or the user from settings, is pointed at the official download
  page (`https://www.rpgmakerweb.com/run-time-package`) and asked to hand the
  downloaded file to enginehost.
- Enginehost **unpacks the file on the device** into the slot. The official
  downloads are installers, not archives: `RPGVXAce_RTP.zip` holds an Inno
  Setup `Setup.exe` plus `Setup-1.bin` (slice format), `xp_rtp104e.exe` is
  Inno Setup 5.2.3, `vx_rtp102e.zip` holds an Inno Setup `Setup.exe`, and
  `rpg2000_rtp_installer.exe` / `rpg2003_rtp_installer.zip` are NSIS 2.46.
  Unpacking therefore needs an Inno Setup extractor (innoextract, C++,
  buildable with the NDK) and an NSIS extractor (7-Zip's NSIS handler). A
  user who already has an extracted RTP folder can point at that instead.
- Every `rpgmaker` plugin receives the slot for its context as the
  `rtpPaths` option, filled in by the host below the game's own config and
  the caller's, the way detected `runtimeRequirements` are. No plugin knows
  where the store lives, and no plugin ships or fetches RTP content.

Until the store exists, a game that needs an RTP fails inside the engine with
the engine's own missing-file message; that is the plugin working correctly
on incomplete input, not a plugin bug.
