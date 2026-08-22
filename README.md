# enginehost

A straightforward multi-engine host for VN/RPG-Maker-family games on
Android (KiriKiri, RPG Maker XP/VX/VX Ace via mkxp-z, Ren'Py — more as
they're wired up).

This is **not** a JoiPlay replacement or competitor — JoiPlay is a good
project doing the user-friendly, polished version of this well. enginehost
exists for a different purpose: it's built to be driven *programmatically*,
by another app that already knows what game it wants run and where it
lives on disk, not by a person browsing a catalog.

## The contract

Fire an Intent:

```
action: dev.enginehost.LAUNCH
extra "path": absolute path to the game's folder
```

That's the whole interface. No catalog, no import step, no metadata to
pass beyond the path — enginehost reads a small `enginehost.json` file at
the root of that folder to figure out everything else:

```json
{
  "engine": "kirikiri2",
  "execFile": "startup.tjs"
}
```

`engine` is required (must match a registered engine id). `execFile` is
optional — the specific file to run within the folder, for engines that
need one.

Nothing about this file's contents, or the folder it lives in, is ever
copied or moved by enginehost. It reads the folder in place and runs the
game from there.

## The basic UI

There's also a plain pick-a-folder-and-launch screen, and a (currently
unimplemented) controller config screen, for when there's no caller —
just someone holding the device. The Intent contract above is the
primary, intended way in.

## Status

Early scaffold. The Intent contract, config format, and basic UI shell are
real and build; actual engine backends (KiriKiri via a vendored
Kirikiroid2Yuri fork, RPG Maker via mkxp-z, Ren'Py) are in progress.
