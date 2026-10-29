# The core test harness

`tools/core-harness/core_harness.py` launches real games on a real device and
reports, per game, whether the core got them going. The owner (2026-10-22):
"We need automated error catching and log submission and stuff ... and some more
extensive testing of the cores." Game testing happens only where the games are,
on BlueStacks or the console, never in the cloud; the coordinator runs the
harness and posts its report on the tracker.

It needs Python 3.8 or later and nothing else, on the machine the device is
attached to (the droidtop-dev distro). It talks to the device through adb only.

## What it does with a game

Each game folder is a path **on the device**, launched where it is, through
Enginehost's normal entry (`dev.enginehost.LAUNCH`). The harness never copies,
moves or writes anything in a game folder.

| check | passes when | otherwise |
| --- | --- | --- |
| first frame | the runtime is in front and the screen shows more than one flat colour, within `--first-frame-seconds` (90) | fails, and says whether the launch screen never gave way, the runtime showed one flat colour (a black screen), or no runtime ran; the screen's UI text is saved |
| input | after pad keys and a tap, the screen changes more than it does by itself | fails |
| stays alive | the runtime still runs `--play-seconds` (20) later, with nothing in the crash buffer | fails |
| save | the engine's save keys (from the games file) make a file in its save folder newer | not tested without keys |
| load | the runtime survives the engine's load keys | not tested without keys |
| exit | the engine's quit keys end the runtime without a crash | not tested without keys |

A check the games file gives no keys for is reported "not tested", never passed.
Input is a heuristic: a title screen that animates by itself and ignores input
can pass it; read the screenshots.

Per game it saves `first-frame.png` and the other screenshots, `logcat.txt`,
`crash.txt`, `host-events.txt` (Enginehost's own milestones, logged under
`enginehost-events`: the plan, the runtime starting, how it ended), and
`result.json`. The run writes `report.md` and `report.json`.

The same harness also runs in CI, on an emulator, against the MIT test games in `test-games/`
only: conformance layer 1b (docs/conformance-test-games.md, "Layer 1b").

## The games file

```json
{
  "renpy": {
    "games": ["/storage/emulated/0/Games/RenPy/Some Game"],
    "steps": {
      "save": { "keys": ["KEYCODE_ESCAPE", "KEYCODE_ENTER"], "folder": "/storage/emulated/0/Enginehost/saves" },
      "load": { "keys": ["KEYCODE_ESCAPE", "KEYCODE_ENTER"] },
      "exit": { "keys": ["KEYCODE_ESCAPE", "KEYCODE_ENTER"] },
      "input_keys": ["KEYCODE_BUTTON_A", "KEYCODE_ENTER"]
    }
  },
  "godot": ["/storage/emulated/0/Games/Godot/A Godot Game"]
}
```

An engine maps to a list of game folders, or to `games` plus optional `steps`.
Keys are Android key codes as `adb shell input keyevent` takes them. Each
step's `delay` (seconds between keys) defaults to 1. `tools/core-harness/games.example.json`
is a starting point.

## Running it

One-time device setup: in Enginehost, Settings > App launch access, allow
"An unrecognized caller (e.g. adb)". adb has no package name for Enginehost to
show, so its launches fall in that one bucket.

```sh
# BlueStacks, from the droidtop-dev distro
python3 tools/core-harness/core_harness.py --adb HD-Adb --serial 127.0.0.1:5555 \
    --games games.json --out results/$(date +%Y%m%d-%H%M)

# The console, through its guarded adb (the whole command line in --adb)
python3 tools/core-harness/core_harness.py --adb "<guarded adb command>" \
    --games games.json --out results/console

# One engine only
python3 tools/core-harness/core_harness.py --games games.json --out results/ --engine renpy

# adb at a path with spaces: quote it inside the argument, since --adb is split like a shell line
python3 tools/core-harness/core_harness.py --adb "'/mnt/c/Program Files/BlueStacks_nxt/HD-Adb.exe'" ...
```

**Unsandboxed cores.** A core with no sandbox makes Enginehost ask "Run
unsandboxed?" on every launch, and the choice is the person's. The harness
does not answer it unless its operator says so: without `--run-unsandboxed`
such a game is **not run**, and the report lists it under "Not run" with the
reason, rather than failing its first frame (rig, 2026-10-22: 0 of 5 games
passed only because nothing answered the sheet). With `--run-unsandboxed` the
harness presses Run unsandboxed (found by its text in the UI dump), marks the
game "(unsandboxed)" in the table, and names every such game under "Ran
unsandboxed".

**Training the sandbox** (`--learn`). Every core runs in learning mode
(docs/engine-sandbox.md, "Learning mode"): nothing is refused, and no game has
a sandbox. The harness sets `debug.enginehost.learn all` before the first game
and sets it back to `0` afterwards, even after an error. It then pulls each
core build's profile into `sandbox-learned/` in the output folder and writes
`sandbox-proposal.md` with `tools/sandbox-learn/propose.py`, for a person to
review. report.md says at the top that the run had no sandbox. An unsandboxed
core still needs `--run-unsandboxed` as well.

It exits 0 when every game run passed every check that ran, and 1 otherwise. Its
own tests run anywhere: `python3 -m unittest discover -s tools/core-harness`.
