#!/usr/bin/env python3
"""Enginehost core test harness: launch real games on a real device and report.

Runs on the machine the device is attached to (the droidtop-dev distro against
BlueStacks' HD-Adb, or the console's guarded adb). Never in the cloud: game
testing happens only where the games are. Python 3.8+, standard library only.

For each game folder given (paths ON THE DEVICE, launched where they are --
nothing is copied, moved or written into a game folder), it:

  1. launches it through Enginehost's normal entry, dev.enginehost.LAUNCH;
  2. waits for the first frame: the runtime in front and the screen showing
     something other than one flat colour (a black screen fails here);
  3. checks input: sends pad and touch input and looks for the screen to change;
  4. checks the game stays alive for a while, with no crash on record;
  5. save and load, and exit: only when the games file gives the keys for that
     engine (see docs/core-harness.md); otherwise "not tested", never assumed;
  6. collects logcat, the crash buffer, Enginehost's host events, screenshots
     and the screen's UI text into the output folder, and writes report.md and
     report.json.

One-time device setup: allow "An unrecognized caller (e.g. adb)" in Enginehost's
Settings > App launch access, since adb has no package for Enginehost to name.

    python3 core_harness.py --games games.json --out results/
    python3 core_harness.py --adb HD-Adb --serial 127.0.0.1:5555 --games games.json --out results/
"""

import argparse
import json
import re
import os
import shlex
import struct
import subprocess
import sys
import time
import zlib

PACKAGE = "dev.enginehost"
RUNTIME_PROCESS = "dev.enginehost:runtime"
LAUNCH_ACTION = "dev.enginehost.LAUNCH"
EVENTS_TAG = "enginehost-events"
# Enginehost's own word on the first frame (RuntimeLoadingNotice): once it is watching the
# game's screen, its "drew" event is the first frame, so its loading screen is never taken for
# the game. An engine it does not watch falls back to what the screenshot shows.
SCREEN_WATCHED = "watching the game's screen"
FIRST_FRAME = "the game drew its first frame"
# The launch screen's choice that runs a core with no sandbox (sandbox_consent_run).
UNSANDBOXED_CHOICE = "Run unsandboxed"
UI_DUMP = "/sdcard/core-harness-ui.xml"

PASS, FAIL, NOT_TESTED = "pass", "fail", "not tested"


# ---- frames ----------------------------------------------------------------

def parse_raw_frame(data):
    """(width, height, rgba bytes) from `screencap` without -p.

    The header is width, height and format as 32-bit little-endian integers,
    followed on Android 9+ by a colour space; its size is whatever is left
    over once width * height * 4 bytes of pixels are accounted for.
    """
    if len(data) < 12:
        raise ValueError("screencap returned %d bytes" % len(data))
    width, height, _fmt = struct.unpack_from("<III", data, 0)
    pixels = width * height * 4
    header = len(data) - pixels
    if width == 0 or height == 0 or header not in (12, 16):
        raise ValueError("unexpected screencap layout: %dx%d, %d bytes" % (width, height, len(data)))
    return width, height, data[header:]


# The share of the screen's height at the top and at the bottom left out of every measure: the
# status bar and the navigation bar, which Android draws over any game. With them counted, a
# black game screen read as a picture (the emulator run of 2026-10-29: NScripter "passed" its
# first frame on the navigation bar alone).
SYSTEM_BARS = 0.08


def sample(width, height, rgba, step=8):
    """(r, g, b) of a grid of pixels, every [step]th pixel each way, between the system bars.

    Colour, not luma: the test games show input by changing colour, and their two colours can
    share a luma (the LÖVE and HTML games' blue and red differ by 3 of 255 in luma, so the emulator
    run of 2026-10-29 read their response to input as no change at all).
    """
    values = []
    for y in range(int(height * SYSTEM_BARS), height - int(height * SYSTEM_BARS), step):
        row = y * width * 4
        for x in range(0, width, step):
            i = row + x * 4
            values.append((rgba[i], rgba[i + 1], rgba[i + 2]))
    return values


def distance(a, b):
    """How far apart two (r, g, b) colours are: the largest difference of one channel."""
    return max(abs(a[0] - b[0]), abs(a[1] - b[1]), abs(a[2] - b[2]))


def is_flat(values, tolerance=6):
    """True when every sampled pixel is within [tolerance] of the mean colour: one flat colour (a black screen)."""
    if not values:
        return True
    mean = tuple(sum(v[c] for v in values) / len(values) for c in range(3))
    return all(distance(v, mean) <= tolerance for v in values)


def changed_fraction(a, b, threshold=12):
    """The share of sampled pixels whose colour moved by more than [threshold] between two samples."""
    if not a or len(a) != len(b):
        return 1.0
    return sum(1 for x, y in zip(a, b) if distance(x, y) > threshold) / len(a)


# How long the input check waits for the screen to settle before it measures anyway.
SETTLE_SECONDS = 60

# How long each check waits for what it observes before it counts as not happening.
INPUT_ANSWER_SECONDS = 6   # the screen changing after input
SAVE_SECONDS = 10          # a newer file in the save folder
LOAD_SECONDS = 3           # a load that crashes ends the runtime within this
EXIT_SECONDS = 15          # the runtime ending after the exit keys


def settled(before, after):
    """Whether a game's screen is ready for input: more than one colour, and less than 2% of it
    changed over the 3 s between [before] and [after] (an animated game's own motion is less:
    0.009 for the LÖVE test game's moving square)."""
    return not is_flat(after) and changed_fraction(before, after) < 0.02


def first_frame_shown(events, picture):
    """Whether the game has drawn: Enginehost's [FIRST_FRAME] event once it is watching the
    game's screen ([SCREEN_WATCHED]), else [picture], a screenshot of more than one colour.
    The screenshot alone passed Enginehost's loading screen as the Ren'Py game's first frame
    (emulator run 40887903439), while the game itself was still black behind it."""
    if FIRST_FRAME in events:
        return True
    if SCREEN_WATCHED in events:
        return False
    return picture


GAMEPAD = "gamepad:"


def key_command(code):
    """The `input` command for a games-file key: an Android key code, sent as a keyboard's, or
    `gamepad:` and a key code, sent as a gamepad's. A gamepad's buttons are controller input,
    which Enginehost maps to the engine's actions; a keyboard's keys reach only a core that
    reads the keyboard, and a libretro core does not."""
    if code.startswith(GAMEPAD):
        return "input gamepad keyevent %s" % shlex.quote(code[len(GAMEPAD):])
    return "input keyevent %s" % shlex.quote(code)


def lines_of(logcat, pids):
    """The lines of a `logcat -v threadtime` dump written by one of [pids], or naming Enginehost.

    The second kind is the system's account of the game's processes (started, killed, died),
    which no line of their own gives when they are killed from outside.
    """
    kept = []
    for line in logcat.splitlines():
        fields = line.split(None, 3)
        if len(fields) > 2 and (fields[2] in pids or PACKAGE in line):
            kept.append(line)
    return "\n".join(kept) + ("\n" if kept else "")


def write_png(path, width, height, rgba):
    """A plain RGBA PNG, for the person reading the report."""
    def chunk(kind, body):
        return struct.pack(">I", len(body)) + kind + body + struct.pack(">I", zlib.crc32(kind + body) & 0xffffffff)
    stride = width * 4
    raw = b"".join(b"\x00" + bytes(rgba[y * stride:(y + 1) * stride]) for y in range(height))
    with open(path, "wb") as out:
        out.write(b"\x89PNG\r\n\x1a\n")
        out.write(chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)))
        out.write(chunk(b"IDAT", zlib.compress(raw, 6)))
        out.write(chunk(b"IEND", b""))


def centre_of(ui_xml, text):
    """The centre of the node in a uiautomator dump whose text is exactly [text], or None."""
    for node in re.finditer(r'<node [^>]*>', ui_xml):
        attrs = dict(re.findall(r'([\w-]+)="([^"]*)"', node.group(0)))
        if attrs.get("text") != text:
            continue
        bounds = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", attrs.get("bounds", ""))
        if bounds:
            x1, y1, x2, y2 = map(int, bounds.groups())
            return (x1 + x2) // 2, (y1 + y2) // 2
    return None


# ---- the device ------------------------------------------------------------

class Device:
    def __init__(self, adb, serial=None):
        self.base = shlex.split(adb) + (["-s", serial] if serial else [])

    def run(self, *args, binary=False, timeout=60, check=False):
        result = subprocess.run(self.base + list(args), stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
        if check and result.returncode != 0:
            raise RuntimeError("adb %s failed: %s" % (" ".join(args), result.stderr.decode(errors="replace").strip()))
        return result.stdout if binary else result.stdout.decode(errors="replace")

    def shell(self, command, **kwargs):
        return self.run("shell", command, **kwargs)

    def pid(self, process):
        out = self.shell("pidof %s" % shlex.quote(process)).strip()
        return out.split()[0] if out else None

    def app_pids(self):
        """Pids of Enginehost's processes (its isolated runtime among them) and of WebView's renderers."""
        pids = set()
        for line in self.shell("ps -A -o PID,NAME").splitlines()[1:]:
            fields = line.split()
            if len(fields) == 2 and (fields[1].startswith(PACKAGE) or "sandboxed_process" in fields[1]):
                pids.add(fields[0])
        return pids

    def focused(self):
        out = self.shell("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' | head -2")
        return " ".join(out.split())

    def frame(self):
        return parse_raw_frame(self.run("exec-out", "screencap", binary=True, timeout=30))

    def key(self, code):
        self.shell(key_command(code))

    def tap_centre(self, width, height):
        self.shell("input tap %d %d" % (width // 2, height // 2))

    def tap(self, x, y):
        self.shell("input tap %d %d" % (x, y))

    def ui_text(self):
        """The screen's UI hierarchy as uiautomator dumps it, or "" when it cannot."""
        self.shell("uiautomator dump %s" % UI_DUMP)
        xml = self.shell("cat %s" % UI_DUMP)
        self.shell("rm -f %s" % UI_DUMP)
        return xml

    def find_text(self, text):
        """The centre of the on-screen element whose text is exactly [text], or None."""
        return centre_of(self.ui_text(), text)

    def newest_mtime(self, folder):
        """Newest modification time (seconds) of any file under [folder] on the device, or 0."""
        out = self.shell("find %s -type f -exec stat -c %%Y {} + 2>/dev/null | sort -n | tail -1" % shlex.quote(folder))
        out = out.strip()
        return int(out) if out.isdigit() else 0


# ---- one game --------------------------------------------------------------

def wait_until(condition, seconds, every=0.5):
    """Polls [condition] until it holds or [seconds] pass; answers whether it held.

    The checks wait on what they can observe (a newer save file, a changed screen, the
    process gone) with a deadline, not for a fixed time: a slow emulator used to read as a
    failed game (code quality review, Droidtop/tracker#768)."""
    deadline = time.time() + seconds
    while True:
        if condition():
            return True
        if time.time() >= deadline:
            return False
        time.sleep(every)


class GameRun:
    """One game's run through the checks, each a method: first frame, input, survival, save
    and load, exit, then the logs. The result dict is what the report reads."""

    def __init__(self, device, engine, folder, steps, out_dir, args):
        self.device, self.folder, self.steps, self.out_dir, self.args = device, folder, steps, out_dir, args
        self.result = {"engine": engine, "folder": folder, "checks": {}, "notes": []}
        self.checks = self.result["checks"]
        # Every pid the game's processes had, so their log can be told from the rest of the device's
        # once they are gone (a game that closed itself leaves no process to ask).
        self.pids = set()

    def shot(self, name):
        try:
            w, h, rgba = self.device.frame()
            write_png(os.path.join(self.out_dir, name + ".png"), w, h, rgba)
            return w, h, rgba
        except Exception as error:  # a failed screenshot is a note, not a failed game
            self.result["notes"].append("screenshot %s failed: %s" % (name, error))
            return None

    def runtime_alive(self):
        return self.device.pid(RUNTIME_PROCESS) is not None

    def launch_to_first_frame(self):
        """Starts the game clean and waits for its first frame: the runtime in front, showing more
        than one flat colour, with Enginehost's own first-frame event. Answers whether it came."""
        device, args, result = self.device, self.args, self.result
        device.shell("am force-stop %s" % PACKAGE)
        device.run("logcat", "-c")
        device.run("logcat", "-b", "crash", "-c")
        self.started = time.time()
        device.shell("am start -a %s --es path %s" % (LAUNCH_ACTION, shlex.quote(self.folder)))
        # An unsandboxed core first asks the person on the launch screen; the
        # harness answers for them only when its operator said so.
        first = None
        last_focus = ""
        result["unsandboxed"] = False
        while time.time() - self.started < args.first_frame_seconds:
            time.sleep(2)
            self.pids |= device.app_pids()
            last_focus = device.focused()
            if not result["unsandboxed"] and "LaunchActivity" in last_focus:
                choice = device.find_text(UNSANDBOXED_CHOICE)
                if choice:
                    result["unsandboxed"] = True
                    if not args.run_unsandboxed:
                        result["skipped"] = "its core runs unsandboxed; rerun with --run-unsandboxed to run it anyway"
                        break
                    result["notes"].append("ran its core UNSANDBOXED (--run-unsandboxed): no sandbox protected this run")
                    device.tap(*choice)
                    continue
            if device.pid(RUNTIME_PROCESS) and PACKAGE in last_focus and "LaunchActivity" not in last_focus:
                frame = self.shot("first-frame-candidate")
                events = device.run("logcat", "-d", "-s", EVENTS_TAG + ":I")
                if first_frame_shown(events, bool(frame) and not is_flat(sample(*frame))):
                    first = time.time() - self.started
                    break
        if result.get("skipped"):
            self.checks["first frame"] = NOT_TESTED
        elif first is not None:
            self.checks["first frame"] = PASS
            result["first_frame_seconds"] = round(first, 1)
            self.shot("first-frame")
        else:
            self.checks["first frame"] = FAIL
            reason = "the launch screen never gave way" if "LaunchActivity" in last_focus else \
                "the runtime showed one flat colour (black screen)" if device.pid(RUNTIME_PROCESS) else \
                "no runtime process"
            result["notes"].append("no first frame within %ds: %s (focus: %s)" % (args.first_frame_seconds, reason, last_focus))
            with open(os.path.join(self.out_dir, "screen-ui.xml"), "w") as f:
                f.write(device.ui_text())
            self.shot("no-first-frame")
        return first is not None

    def check_input(self):
        """The screen moves with input more than it does by itself, measured once the screen has
        settled: a picture that holds still for 3 s. An engine's own start-up (Ren'Py's presplash,
        then black while it loads) is neither, and input sent into it is lost (emulator run
        40889525212: Ren'Py changed 1.0 of the screen "idle")."""
        device = self.device
        deadline = time.time() + SETTLE_SECONDS
        while True:
            quiet_a = self.shot("input-before")
            time.sleep(3)
            quiet_b = self.shot("input-idle")
            if time.time() > deadline or (quiet_a and quiet_b and settled(sample(*quiet_a), sample(*quiet_b))):
                break
        self.result["settled_seconds"] = round(time.time() - self.started, 1)
        for key in self.steps.get("input_keys", ["KEYCODE_BUTTON_A", "KEYCODE_ENTER", "KEYCODE_DPAD_DOWN", "KEYCODE_BUTTON_A"]):
            device.key(key)
            time.sleep(0.5)  # pacing between presses, as a person presses
        if quiet_b:
            device.tap_centre(quiet_b[0], quiet_b[1])
        if not (quiet_a and quiet_b):
            self.checks["input"] = NOT_TESTED
            return
        idle = changed_fraction(sample(*quiet_a), sample(*quiet_b))
        threshold = max(0.02, idle * 1.5)
        moved = None

        def answered():
            nonlocal moved
            moved = self.shot("input-after")
            return bool(moved) and changed_fraction(sample(*quiet_b), sample(*moved)) > threshold
        wait_until(answered, INPUT_ANSWER_SECONDS, every=1.0)
        if not moved:
            self.checks["input"] = NOT_TESTED
            return
        active = changed_fraction(sample(*quiet_b), sample(*moved))
        self.result["input_change"] = {"idle": round(idle, 3), "after_input": round(active, 3)}
        self.checks["input"] = PASS if active > threshold else FAIL
        if self.checks["input"] == FAIL:
            self.result["notes"].append("the screen did not respond to input more than it changes by itself")

    def check_alive(self):
        """Still running after a while of play, with nothing in the crash buffer."""
        self.pids |= self.device.app_pids()
        time.sleep(self.args.play_seconds)  # the check is that it lasts this long
        alive = self.runtime_alive()
        crash = self.device.run("logcat", "-b", "crash", "-d")
        self.checks["stays alive"] = PASS if alive and RUNTIME_PROCESS not in crash else FAIL
        if not alive:
            self.result["notes"].append("the runtime process ended within %ds of the first frame" % self.args.play_seconds)
        self.shot("after-play")

    def check_save_and_load(self):
        """Save with this engine's keys: a file in its save folder is newer than before. Load with
        its keys: the runtime is still there afterwards."""
        device = self.device
        save = self.steps.get("save")
        if not (save and save.get("keys") and save.get("folder")):
            self.checks["save"] = self.checks["load"] = NOT_TESTED
            return
        before = device.newest_mtime(save["folder"])
        for key in save["keys"]:
            device.key(key)
            time.sleep(save.get("delay", 1.0))  # pacing between presses
        saved = wait_until(lambda: device.newest_mtime(save["folder"]) > before, SAVE_SECONDS, every=1.0)
        self.checks["save"] = PASS if saved else FAIL
        load = self.steps.get("load")
        if not (load and load.get("keys")):
            self.checks["load"] = NOT_TESTED
            return
        for key in load["keys"]:
            device.key(key)
            time.sleep(load.get("delay", 1.0))
        # A load that crashes ends the runtime within a few seconds; none ending is the pass.
        ended = wait_until(lambda: not self.runtime_alive(), LOAD_SECONDS, every=1.0)
        self.checks["load"] = FAIL if ended else PASS
        self.shot("after-load")

    def check_exit(self):
        """Exit with this engine's keys: the runtime ends, with no crash."""
        exit_steps = self.steps.get("exit")
        if not (exit_steps and exit_steps.get("keys")):
            self.checks["exit"] = NOT_TESTED
            return
        for key in exit_steps["keys"]:
            self.device.key(key)
            time.sleep(exit_steps.get("delay", 1.0))
        ended = wait_until(lambda: not self.runtime_alive(), EXIT_SECONDS, every=1.0)
        crash = self.device.run("logcat", "-b", "crash", "-d")
        self.checks["exit"] = PASS if ended and RUNTIME_PROCESS not in crash else FAIL

    def collect_logs(self):
        """The logs, whatever happened, and the result written beside them."""
        device, out_dir, result = self.device, self.out_dir, self.result
        logcat = device.run("logcat", "-d", "-v", "threadtime", timeout=120)
        with open(os.path.join(out_dir, "logcat.txt"), "w") as f:
            f.write(logcat)
        with open(os.path.join(out_dir, "app-log.txt"), "w") as f:
            f.write(lines_of(logcat, self.pids))
        with open(os.path.join(out_dir, "crash.txt"), "w") as f:
            f.write(device.run("logcat", "-b", "crash", "-d", "-v", "threadtime"))
        events = device.run("logcat", "-d", "-s", EVENTS_TAG + ":I")
        with open(os.path.join(out_dir, "host-events.txt"), "w") as f:
            f.write(events)
        result["host_events"] = [line.split(": ", 1)[-1] for line in events.splitlines() if EVENTS_TAG in line][-12:]
        device.shell("am force-stop %s" % PACKAGE)
        result["passed"] = not result.get("skipped") and all(v != FAIL for v in self.checks.values())
        with open(os.path.join(out_dir, "result.json"), "w") as f:
            json.dump(result, f, indent=2)


def run_game(device, engine, folder, steps, out_dir, args):
    """Run every check on one game; answers the result dict."""
    os.makedirs(out_dir, exist_ok=True)
    run = GameRun(device, engine, folder, steps, out_dir, args)
    if run.launch_to_first_frame():
        run.check_input()
        run.check_alive()
        run.check_save_and_load()
        run.check_exit()
    else:
        for name in ("input", "stays alive", "save", "load", "exit"):
            run.checks[name] = NOT_TESTED
    run.collect_logs()
    return run.result


# ---- the report ------------------------------------------------------------

LEARNED_ON_DEVICE = "/sdcard/Android/data/%s/files/sandbox-learned" % PACKAGE


def render_report(results, device_line, learning=None):
    names = ["first frame", "input", "stays alive", "save", "load", "exit"]
    lines = ["# Enginehost core test harness", "", device_line, ""]
    if learning:
        lines += ["**Learning run** (`debug.enginehost.learn all`): nothing was refused and no game had a "
                  "sandbox. " + learning, ""]
    lines += ["| engine | game | " + " | ".join(names) + " |", "|" + " --- |" * (len(names) + 2)]
    for r in results:
        cells = [r["checks"].get(n, NOT_TESTED) for n in names]
        game = os.path.basename(r["folder"].rstrip("/")) + (" (unsandboxed)" if r.get("unsandboxed") else "")
        lines.append("| %s | %s | %s |" % (r["engine"], game, " | ".join(cells)))
    skipped = [r for r in results if r.get("skipped")]
    failed = [r for r in results if not r["passed"] and not r.get("skipped")]
    ran = len(results) - len(skipped)
    lines += ["", "%d of %d games run passed every check that ran." % (ran - len(failed), ran)]
    ran_unsandboxed = [r for r in results if r.get("unsandboxed") and not r.get("skipped")]
    if ran_unsandboxed:
        lines += ["", "**Ran unsandboxed** (--run-unsandboxed): " +
                  ", ".join("%s %s" % (r["engine"], os.path.basename(r["folder"].rstrip("/"))) for r in ran_unsandboxed) + "."]
    if skipped:
        lines += ["", "**Not run:** " + "; ".join(
            "%s %s: %s" % (r["engine"], os.path.basename(r["folder"].rstrip("/")), r["skipped"]) for r in skipped)]
    for r in failed:
        lines += ["", "## %s: %s" % (r["engine"], r["folder"])]
        lines += ["- " + note for note in r["notes"]]
        if r.get("host_events"):
            lines += ["- Enginehost's last events: " + "; ".join(r["host_events"])]
    return "\n".join(lines) + "\n"


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--games", required=True, help="JSON: engine -> list of game folders on the device (docs/core-harness.md)")
    ap.add_argument("--out", required=True, help="folder for the report, logs and screenshots (on this machine)")
    ap.add_argument("--adb", default="adb", help='the adb command, e.g. "HD-Adb" or the console\'s guarded adb')
    ap.add_argument("--serial", help="adb device serial, when more than one is attached")
    ap.add_argument("--engine", action="append", help="only these engines (repeatable)")
    ap.add_argument("--first-frame-seconds", type=int, default=90)
    ap.add_argument("--play-seconds", type=int, default=20)
    ap.add_argument("--run-unsandboxed", action="store_true",
                    help='answer "Run unsandboxed" for a core with no sandbox; the report names every game run that way')
    ap.add_argument("--learn", action="store_true",
                    help="train the sandbox: every core runs in learning mode (docs/engine-sandbox.md), and its "
                         "profiles and a proposed policy are pulled into the output folder")
    args = ap.parse_args(argv)

    with open(args.games) as f:
        games = json.load(f)
    device = Device(args.adb, args.serial)
    if "package:" not in device.shell("pm path %s" % PACKAGE):
        sys.exit("Enginehost (%s) is not installed on the device" % PACKAGE)
    version = device.shell("dumpsys package %s | grep -m1 versionName" % PACKAGE).strip()
    model = device.shell("getprop ro.product.model").strip()
    sdk = device.shell("getprop ro.build.version.sdk").strip()
    device_line = "Device: %s, API %s. Enginehost %s." % (model, sdk, version.replace("versionName=", ""))

    if args.learn:
        # Learning applies at each launch; it is turned off again whatever happens below.
        device.shell("setprop debug.enginehost.learn all")
    results = []
    try:
        run_all(device, games, args, results)
    finally:
        if args.learn:
            device.shell("setprop debug.enginehost.learn 0")
    learning = pull_learned(device, args.out) if args.learn else None

    os.makedirs(args.out, exist_ok=True)
    with open(os.path.join(args.out, "report.json"), "w") as f:
        json.dump({"device": device_line, "learning": learning, "results": results}, f, indent=2)
    with open(os.path.join(args.out, "report.md"), "w") as f:
        f.write(render_report(results, device_line, learning))
    print("Report: %s" % os.path.join(args.out, "report.md"))
    return 0 if all(r["passed"] or r.get("skipped") for r in results) else 1


def pull_learned(device, out):
    """Pulls the learning profiles and writes the proposal beside them; answers the report's sentence."""
    folder = os.path.join(out, "sandbox-learned")
    os.makedirs(folder, exist_ok=True)
    names = [n for n in device.shell("ls %s" % LEARNED_ON_DEVICE).split() if n.endswith(".json")]
    for name in names:
        device.run("pull", "%s/%s" % (LEARNED_ON_DEVICE, name), os.path.join(folder, name))
    pulled = sorted(os.path.join(folder, n) for n in names if os.path.isfile(os.path.join(folder, n)))
    if not pulled:
        return "No profile was found in %s." % LEARNED_ON_DEVICE
    propose = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "sandbox-learn", "propose.py")
    proposal = subprocess.run([sys.executable, propose] + pulled, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    with open(os.path.join(out, "sandbox-proposal.md"), "wb") as f:
        f.write(proposal.stdout)
    return "Profiles: `sandbox-learned/` (%d); proposed policy for review: `sandbox-proposal.md`." % len(pulled)


def run_all(device, games, args, results):
    for engine, entry in games.items():
        if args.engine and engine not in args.engine:
            continue
        folders = entry["games"] if isinstance(entry, dict) else entry
        steps = entry.get("steps", {}) if isinstance(entry, dict) else {}
        for index, folder in enumerate(folders):
            out_dir = os.path.join(args.out, "%s-%02d-%s" % (engine, index + 1, os.path.basename(folder.rstrip("/"))))
            print("%s: %s" % (engine, folder), flush=True)
            result = run_game(device, engine, folder, steps, out_dir, args)
            print("  " + ", ".join("%s %s" % (k, v) for k, v in result["checks"].items()), flush=True)
            results.append(result)


if __name__ == "__main__":
    sys.exit(main())
