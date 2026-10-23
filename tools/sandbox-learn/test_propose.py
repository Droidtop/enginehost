"""Tests for propose.py: python3 -m unittest discover -s tools/sandbox-learn"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))
import propose as p  # noqa: E402

PROFILE = {
    "core": "dev.enginehost.love2d.11.v1", "build": "0.9.5", "archive": "3F0C1A7A9B2C4D5E", "abi": "x86_64",
    "runs": 2, "games": ["Thermomorph", "black-screen"],
    "paths": [
        {"op": "open", "path": "$GAME/thermomorph.love", "need": "read", "count": 3},
        {"op": "open", "path": "$EXTERNAL/Music/song.ogg", "need": "read", "count": 1},
        {"op": "stat", "path": "/mnt/expand", "need": "lookup", "count": 4},
        {"op": "open", "path": "$SAVE/LOVE/thermomorph/save.dat", "need": "write", "count": 2},
        {"op": "stat", "path": "$SAVE/LOVE/thermomorph/save.dat", "need": "lookup", "count": 1},
    ],
    "calls": [
        {"call": "socket", "would": "EACCES", "count": 1, "examples": ["socket(0x2, 0x1, 0)"]},
        {"call": "kill", "would": "EPERM", "count": 2, "examples": ["kill(0x1234, 0)"]},
    ],
}


class ProposeTest(unittest.TestCase):
    def test_paths_are_grouped_by_place_with_their_strongest_need(self):
        text = p.render(p.merge([PROFILE]))
        self.assertIn("**The game folder**\n- `$GAME/thermomorph.love` read (open, 3 times)", text)
        self.assertIn("`$SAVE/LOVE/thermomorph/save.dat` write (open, stat, 3 times)", text)
        self.assertIn("Shared storage outside the game: review carefully", text)
        self.assertIn("looked up only, usually no rule needed: `/mnt/expand`", text)

    def test_forbidden_calls_are_never_proposed(self):
        text = p.render(p.merge([PROFILE]))
        proposed, refused = text.split("### Refused by decision, never proposed")
        self.assertIn("`kill` (a lockdown answers EPERM, 2 times)", proposed)
        self.assertNotIn("`socket`", proposed)
        self.assertIn("`socket`: network (1 times)", refused)

    def test_profiles_of_one_core_combine(self):
        second = dict(PROFILE, build="0.9.6", archive="AAAA", runs=1, games=["Other"],
                      paths=[{"op": "open", "path": "$GAME/thermomorph.love", "need": "write", "count": 1}], calls=[])
        core = p.merge([PROFILE, second])["dev.enginehost.love2d.11.v1"]
        self.assertEqual(3, core["runs"])
        self.assertEqual(["Thermomorph", "black-screen", "Other"], core["games"])
        self.assertEqual("write", core["paths"]["$GAME/thermomorph.love"]["need"])
        self.assertEqual(4, core["paths"]["$GAME/thermomorph.love"]["count"])

    def test_a_clean_run_says_the_policy_is_enough(self):
        clean = dict(PROFILE, paths=[], calls=[])
        self.assertIn("Nothing would have been refused", p.render(p.merge([clean])))


if __name__ == "__main__":
    unittest.main()
