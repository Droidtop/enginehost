"""Tests for core_harness.py: the frame checks, the report, and run_game's flow against a fake adb.

    python3 -m unittest discover -s tools/core-harness
"""

import json
import os
import struct
import sys
import tempfile
import textwrap
import types
import unittest
from unittest import mock

sys.path.insert(0, os.path.dirname(__file__))
import core_harness as h  # noqa: E402


def raw(width, height, pixel_at, header=16):
    head = struct.pack("<III", width, height, 1) + (b"\0\0\0\0" if header == 16 else b"")
    body = bytearray()
    for y in range(height):
        for x in range(width):
            body += bytes(pixel_at(x, y)) + b"\xff"
    return head + bytes(body)


class FrameTest(unittest.TestCase):
    def test_both_header_sizes_parse(self):
        for header in (12, 16):
            w, hh, rgba = h.parse_raw_frame(raw(4, 3, lambda x, y: (1, 2, 3), header))
            self.assertEqual((4, 3, 48), (w, hh, len(rgba)))

    def test_a_black_screen_is_flat_and_a_picture_is_not(self):
        black = h.sample(*h.parse_raw_frame(raw(32, 32, lambda x, y: (0, 0, 0))))
        picture = h.sample(*h.parse_raw_frame(raw(32, 32, lambda x, y: (x * 8, y * 8, 40))))
        self.assertTrue(h.is_flat(black))
        self.assertFalse(h.is_flat(picture))

    def test_the_system_bars_do_not_make_a_black_screen_a_picture(self):
        # A black game with a navigation bar drawn in its bottom rows, as the emulator showed it.
        frame = h.parse_raw_frame(raw(32, 64, lambda x, y: (200, 200, 200) if y >= 61 and x % 4 == 0 else (0, 0, 0)))
        self.assertTrue(h.is_flat(h.sample(*frame, step=1)))

    def test_changed_fraction(self):
        grey, white = (10, 10, 10), (200, 200, 200)
        self.assertEqual(0.0, h.changed_fraction([grey, white], [grey, white]))
        self.assertEqual(0.5, h.changed_fraction([grey, grey], [grey, white]))

    def test_a_gamepad_key_is_sent_as_a_gamepads(self):
        self.assertEqual("input keyevent KEYCODE_ENTER", h.key_command("KEYCODE_ENTER"))
        self.assertEqual("input gamepad keyevent KEYCODE_BUTTON_A", h.key_command("gamepad:KEYCODE_BUTTON_A"))

    def test_lines_of_keeps_only_the_given_pids(self):
        dump = ("10-29 06:35:17.298  4625  4625 I enginehost-events: launch\n"
                "10-29 06:35:17.300  1615  2232 W A       : noise\n"
                "--------- beginning of main\n")
        self.assertEqual("10-29 06:35:17.298  4625  4625 I enginehost-events: launch\n", h.lines_of(dump, {"4625"}))
        self.assertEqual("", h.lines_of(dump, set()))
        killed = "10-29 06:45:40.900   519   939 I ActivityManager: Killing 4656:dev.enginehost:runtime/u0a192 (adj 0): lmk\n"
        self.assertEqual(killed, h.lines_of(killed, set()))

    def test_a_change_of_colour_at_the_same_luma_is_a_change(self):
        # The test games' blue and red: luma 86 and 89.
        blue, red = (56, 89, 153), (153, 56, 89)
        self.assertEqual(1.0, h.changed_fraction([blue], [red]))

    def test_png_is_written(self):
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "a.png")
            h.write_png(path, 2, 2, bytes(16))
            with open(path, "rb") as f:
                self.assertEqual(b"\x89PNG", f.read(4))


FAKE_ADB = textwrap.dedent('''\
    #!{python}
    import json, os, sys, struct
    state_path = {state!r}
    with open(state_path) as f: state = json.load(f)
    args = sys.argv[1:]
    def frame(kind):
        w = hh = 32
        head = struct.pack("<IIII", w, hh, 1, 0)
        out = bytearray()
        for y in range(hh):
            for x in range(w):
                if kind == "black": out += b"\\0\\0\\0\\xff"
                else: out += bytes([(x * 8 + state["taps"] * 60) % 256, (y * 8) % 256, 40, 255])
        sys.stdout.buffer.write(head + bytes(out))
    cmd = " ".join(args)
    if "setprop debug.enginehost.learn" in cmd:
        state.setdefault("learn", []).append(cmd.split()[-1])
    elif "ls /sdcard/Android/data/dev.enginehost/files/sandbox-learned" in cmd:
        print("dev.enginehost.love2d.11.v1-3f0c1a7a9b2c.json")
    elif args[:1] == ["pull"] and "sandbox-learned" in args[1]:
        with open(args[2], "w") as f:
            json.dump({{"core": "dev.enginehost.love2d.11.v1", "build": "0.9.5", "archive": "3F0C", "abi": "x86_64",
                       "runs": 1, "games": ["Test"], "paths": [], "calls": [
                           {{"call": "socket", "would": "EACCES", "count": 1, "examples": ["socket(0x2, 0x1, 0)"]}}]}}, f)
    elif args[:2] == ["exec-out", "screencap"]:
        frame(state["screen"])
    elif "pm path" in cmd: print("package:/data/app/dev.enginehost/base.apk")
    elif "pidof" in cmd: print("4242" if state["launched"] else "")
    elif "dumpsys window" in cmd and state["asking"]: print("mCurrentFocus=Window{{1 u0 dev.enginehost/dev.enginehost.LaunchActivity}}")
    elif "dumpsys window" in cmd: print("mCurrentFocus=Window{{1 u0 dev.enginehost/dev.enginehost.RuntimeActivity}}" if state["launched"] else "")
    elif "am start" in cmd and state["unsandboxed"]: state["asking"] = True
    elif "am start" in cmd: state["launched"] = True
    elif "cat /sdcard/core-harness-ui.xml" in cmd:
        print('<hierarchy><node text="Cancel" bounds="[0,0][10,10]" /><node text="Run unsandboxed" bounds="[100,200][300,260]" /></hierarchy>' if state["asking"] else "<hierarchy />")
    elif "input tap 200 230" in cmd and state["asking"]: state["asking"] = False; state["launched"] = True
    elif "input" in cmd: state["taps"] += 1
    elif args[:1] == ["logcat"] and "-s" in args: print("10-22 I enginehost-events: runtime started")
    elif "force-stop" in cmd: state["launched"] = False
    with open(state_path, "w") as f: json.dump(state, f)
    ''')


class RunGameTest(unittest.TestCase):
    def run_with(self, screen, unsandboxed=False, run_unsandboxed=False):
        d = tempfile.mkdtemp()
        state = os.path.join(d, "state.json")
        with open(state, "w") as f:
            json.dump({"screen": screen, "launched": False, "taps": 0, "unsandboxed": unsandboxed, "asking": False}, f)
        adb = os.path.join(d, "adb")
        with open(adb, "w") as f:
            f.write(FAKE_ADB.format(python=sys.executable, state=state))
        os.chmod(adb, 0o755)
        args = types.SimpleNamespace(first_frame_seconds=5, play_seconds=0, run_unsandboxed=run_unsandboxed)
        clock = iter(range(0, 10000))
        # The fake device answers at once; the harness's clock ticks a second per look.
        with mock.patch.object(h.time, "sleep", lambda s: None), mock.patch.object(h.time, "time", lambda: next(clock)):
            return h.run_game(h.Device(adb), "renpy", "/storage/emulated/0/Games/Test", {}, os.path.join(d, "out"), args)

    def test_a_game_that_draws_passes_first_frame_input_and_survival(self):
        r = self.run_with("picture")
        self.assertEqual(h.PASS, r["checks"]["first frame"])
        self.assertEqual(h.PASS, r["checks"]["input"])
        self.assertEqual(h.PASS, r["checks"]["stays alive"])
        self.assertEqual(h.NOT_TESTED, r["checks"]["save"])
        self.assertTrue(r["passed"])
        self.assertIn("runtime started", r["host_events"])

    def test_a_black_screen_fails_first_frame_and_says_why(self):
        r = self.run_with("black")
        self.assertEqual(h.FAIL, r["checks"]["first frame"])
        self.assertFalse(r["passed"])
        self.assertTrue(any("black screen" in n for n in r["notes"]))

    def test_the_report_lists_every_game_and_the_failures(self):
        r = self.run_with("black")
        text = h.render_report([r], "Device: test")
        self.assertIn("| renpy | Test |", text)
        self.assertIn("0 of 1 games run passed", text)

    def test_an_unsandboxed_core_is_not_run_without_the_operator_saying_so(self):
        r = self.run_with("picture", unsandboxed=True)
        self.assertEqual(h.NOT_TESTED, r["checks"]["first frame"])
        self.assertTrue(r["unsandboxed"])
        self.assertIn("--run-unsandboxed", r["skipped"])
        text = h.render_report([r], "Device: test")
        self.assertIn("**Not run:**", text)
        self.assertIn("0 of 0 games run passed", text)

    def test_with_run_unsandboxed_the_sheet_is_answered_and_the_report_says_so(self):
        r = self.run_with("picture", unsandboxed=True, run_unsandboxed=True)
        self.assertEqual(h.PASS, r["checks"]["first frame"])
        self.assertTrue(r["passed"])
        text = h.render_report([r], "Device: test")
        self.assertIn("| renpy | Test (unsandboxed) |", text)
        self.assertIn("**Ran unsandboxed**", text)

    def test_the_ui_dump_is_read_for_the_choice_centre(self):
        dump = '<node text="Run unsandboxed" bounds="[100,200][300,260]" />'
        self.assertEqual((200, 230), h.centre_of(dump, "Run unsandboxed"))
        self.assertIsNone(h.centre_of(dump, "Cancel"))


class LearnTest(unittest.TestCase):
    def test_learn_turns_learning_on_and_off_and_pulls_a_proposal(self):
        d = tempfile.mkdtemp()
        state = os.path.join(d, "state.json")
        with open(state, "w") as f:
            json.dump({"screen": "picture", "launched": False, "taps": 0, "unsandboxed": False, "asking": False}, f)
        adb = os.path.join(d, "adb")
        with open(adb, "w") as f:
            f.write(FAKE_ADB.format(python=sys.executable, state=state))
        os.chmod(adb, 0o755)
        games = os.path.join(d, "games.json")
        with open(games, "w") as f:
            json.dump({"love2d": ["/storage/emulated/0/Games/Test"]}, f)
        out = os.path.join(d, "out")
        clock = iter(range(0, 100000))
        with mock.patch.object(h.time, "sleep", lambda s: None), mock.patch.object(h.time, "time", lambda: next(clock)), \
                mock.patch.object(h, "PACKAGE", "dev.enginehost"):
            h.main(["--adb", adb, "--games", games, "--out", out, "--learn", "--play-seconds", "0",
                    "--first-frame-seconds", "5"])
        with open(state) as f:
            self.assertEqual(["all", "0"], json.load(f)["learn"])
        self.assertTrue(os.path.isfile(os.path.join(out, "sandbox-learned", "dev.enginehost.love2d.11.v1-3f0c1a7a9b2c.json")))
        with open(os.path.join(out, "sandbox-proposal.md")) as f:
            self.assertIn("`socket`: network", f.read())
        with open(os.path.join(out, "report.md")) as f:
            self.assertIn("**Learning run**", f.read())


if __name__ == "__main__":
    unittest.main()
