# enginehost Ren'Py plugin

This repository builds one installable Ren'Py runtime plugin for
[`enginehost`](https://github.com/bi0shacker001/enginehost). The first plugin
build bundles Ren'Py 8.5.3 and advertises exact compatibility with games that
declare `engineVersion: "8.5.3"`.

The plugin is a programmatic runtime, not a game importer or launcher. It
receives `dev.enginehost.plugin.RUN` with a live game-folder path and forwards
that folder into Ren'Py's normal bootstrap. It does not copy the game.

## Provenance

The runtime and Android packaging system remain Ren'Py projects, downloaded
from the official 8.5.3 release and patched through narrowly scoped forks:

- `renpy` tag `8.5.3.26051504`, integration commit
  `76f9b91fa444e01f1a4adf586297e7eff17ad1ca`
- `renpy-build` tag `renpy-8.5.3.26051504`, integration commit
  `030369768dbf4568eddf38ee76a5241a4223c566`

Ren'Py's licenses and copyright notices are preserved in the downloaded SDK
and resulting distribution. The integration deliberately changes only the
external-path bootstrap seam, exported enginehost activity, and capability
metadata.

## Storage boundary

The plugin uses normal Android app filesystem access. It intentionally does
not request `MANAGE_EXTERNAL_STORAGE`; paths outside the plugin's accessible
storage will fail until enginehost adopts a user-approved storage grant or a
different path transport.

Android builds run only in GitHub Actions.
