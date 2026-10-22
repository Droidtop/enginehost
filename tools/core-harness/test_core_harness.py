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

    def test_changed_fraction(self):
        self.assertEqual(0.0, h.changed_fraction([10, 20], [10, 20]))
        self.assertEqual(0.5, h.changed_fraction([10, 20], [10, 200]))

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
    if args[:2] == ["exec-out", "screencap"]:
        frame(state["screen"])
    elif "pm path" in cmd: print("package:/data/app/dev.enginehost/base.apk")
    elif "pidof" in cmd: print("4242" if state["launched"] else "")
    elif "dumpsys window" in cmd: print("mCurrentFocus=Window{{1 u0 dev.enginehost/dev.enginehost.RuntimeActivity}}" if state["launched"] else "")
    elif "am start" in cmd: state["launched"] = True
    elif "input" in cmd: state["taps"] += 1
    elif args[:1] == ["logcat"] and "-s" in args: print("10-22 I enginehost-events: runtime started")
    elif "force-stop" in cmd: state["launched"] = False
    with open(state_path, "w") as f: json.dump(state, f)
    ''')


class RunGameTest(unittest.TestCase):
    def run_with(self, screen):
        d = tempfile.mkdtemp()
        state = os.path.join(d, "state.json")
        with open(state, "w") as f:
            json.dump({"screen": screen, "launched": False, "taps": 0}, f)
        adb = os.path.join(d, "adb")
        with open(adb, "w") as f:
            f.write(FAKE_ADB.format(python=sys.executable, state=state))
        os.chmod(adb, 0o755)
        args = types.SimpleNamespace(first_frame_seconds=5, play_seconds=0)
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
        self.assertIn("0 of 1 games passed", text)


if __name__ == "__main__":
    unittest.main()
