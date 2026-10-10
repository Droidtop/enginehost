"""Run with: python3 -m unittest discover -s scripts -p "test_*.py" (the script
under test has a dash in its name, so it is loaded by path)."""

import importlib.util
import unittest
from pathlib import Path

from release_history import Published, declared_core, next_build, version_key

_spec = importlib.util.spec_from_file_location(
    "check_declared_version", Path(__file__).with_name("check-declared-version.py"))
_module = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_module)
judge = _module.judge

HEAD = "a" * 40
OLD = "b" * 40


def builds(*versions, commit=OLD):
    return [Published(v, commit, "t") for v in versions]


class VersionOrder(unittest.TestCase):
    def test_build_orders_within_a_declared_version(self):
        self.assertLess(version_key("1.0.0-9"), version_key("1.0.0-10"))
        self.assertLess(version_key("1.0.0"), version_key("1.0.0-1"))
        self.assertLess(version_key("1.0.0-99"), version_key("1.0.1-1"))

    def test_legacy_run_numbers_belong_to_the_zero_patch(self):
        self.assertEqual(declared_core("1.0.57"), (1, 0, 0))
        self.assertEqual(declared_core("1.0.0-57"), (1, 0, 0))
        self.assertEqual(declared_core("1.0.1-3"), (1, 0, 1))

    def test_next_build_restarts_for_a_new_declared_version(self):
        self.assertEqual(next_build("1.0.1", ["1.0.0-4", "1.0.1-1", "1.0.1-2"]), 3)
        self.assertEqual(next_build("1.0.2", ["1.0.1-2"]), 1)


class Judge(unittest.TestCase):
    def check(self, declared, published, files=()):
        return judge(declared, published, HEAD, lambda commit: list(files))

    def test_first_publish(self):
        self.assertTrue(self.check("1.0.0", [])[0])

    def test_bumped_version_passes_with_changed_sources(self):
        self.assertTrue(self.check("1.0.1", builds("1.0.0-3"), ["renpy/x.py"])[0])

    def test_changed_sources_under_the_same_version_fail(self):
        ok, message = self.check("1.0.0", builds("1.0.0-3"), ["renpy/x.py", "README.md"])
        self.assertFalse(ok)
        self.assertIn("renpy/x.py", message)
        self.assertNotIn("README.md", message)

    def test_legacy_published_build_counts_as_the_zero_patch(self):
        self.assertFalse(self.check("1.0.0", builds("1.0.57"), ["a.c"])[0])
        self.assertTrue(self.check("1.0.1", builds("1.0.57"), ["a.c"])[0])

    def test_docs_and_ci_only_pass(self):
        self.assertTrue(self.check("1.0.0", builds("1.0.0-3"),
                                   [".github/workflows/x.yml", "CHANGELOG.md", "docs/a.txt"])[0])

    def test_dropping_the_old_release_script_is_ci_wiring(self):
        self.assertTrue(self.check("1.0.0", builds("1.0.0-3"),
                                   ["build-scripts/publish-history-release.sh"])[0])

    def test_line_composition_bookkeeping_is_not_a_revision(self):
        self.assertTrue(self.check("1.0.0", builds("1.0.0-3"),
                                   ["enginehost/line.json", "enginehost/lines/4.6/line.json"])[0])
        self.assertFalse(self.check("1.0.0", builds("1.0.0-3"),
                                    ["enginehost/lines/4.6/root/enginehost/runtime.json",
                                     "enginehost/runtime.json"])[0])

    def test_rerun_of_the_published_commit_passes(self):
        self.assertTrue(judge("1.0.0", builds("1.0.0-3", commit=HEAD), HEAD, lambda c: ["x"])[0])

    def test_declared_below_published_fails(self):
        self.assertFalse(self.check("1.0.0", builds("1.0.1-1"))[0])

    def test_unknown_release_commit_is_not_held_against_the_build(self):
        self.assertTrue(judge("1.0.0", builds("1.0.0-3"), HEAD, lambda c: None)[0])

    def test_newest_build_of_the_version_sets_the_baseline(self):
        published = [Published("1.0.0-1", OLD, "a"), Published("1.0.0-2", HEAD, "b")]
        self.assertTrue(judge("1.0.0", published, HEAD, lambda c: ["x"])[0])


if __name__ == "__main__":
    unittest.main()
