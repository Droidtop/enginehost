"""Run with: python3 -m unittest discover -s scripts -p "test_*.py"."""

import importlib.util
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("promote_plugin_release", SCRIPTS / "promote-plugin-release.py")
promote_plugin_release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(promote_plugin_release)


class Channels(unittest.TestCase):
    """The owner's channels (2026-10-31): a revision of a line with a stable release goes to testing."""

    def test_a_line_with_a_stable_release_does_not_take_unstable(self):
        problem = promote_plugin_release.channel_problem("kirikiri-v1-unstable", line_has_stable=True)
        self.assertIn("kirikiri-v1-testing", problem)

    def test_a_line_without_one_takes_unstable(self):
        self.assertIsNone(promote_plugin_release.channel_problem("xsystem4-v1-unstable", line_has_stable=False))

    def test_testing_and_stable_tags_are_never_refused(self):
        for tag in ("kirikiri-v1-testing", "kirikiri-v1"):
            self.assertIsNone(promote_plugin_release.channel_problem(tag, line_has_stable=True))


if __name__ == "__main__":
    unittest.main()
