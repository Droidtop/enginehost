"""Run with: python3 -m unittest discover -s scripts -p "test_*.py"."""

import subprocess
import unittest

import release_history
from release_history import gh_api


def answers(*replies):
    """A stand-in for gh: each call takes the next (returncode, stdout, stderr)."""
    calls = []

    def run(arguments):
        calls.append(arguments)
        code, out, err = replies[len(calls) - 1]
        return subprocess.CompletedProcess(["gh", "api", *arguments], code, out, err)
    return run, calls


class GhApiRetry(unittest.TestCase):
    def setUp(self):
        self.pauses = []

    def test_an_empty_body_is_asked_for_again(self):
        run, calls = answers((1, b"", b"unexpected end of JSON input"), (0, b"[[]]", b""))
        self.assertEqual(gh_api("repos/x/releases", parse=release_history.json.loads,
                                run=run, sleep=self.pauses.append), [[]])
        self.assertEqual(len(calls), 2)
        self.assertEqual(self.pauses, [release_history.RETRY_PAUSES[0]])

    def test_a_body_that_is_not_json_is_asked_for_again(self):
        run, calls = answers((0, b"", b""), (0, b'{"bundles": []}', b""))
        self.assertEqual(gh_api("asset", parse=release_history.json.loads,
                                run=run, sleep=self.pauses.append), {"bundles": []})
        self.assertEqual(len(calls), 2)

    def test_it_gives_up_with_the_last_message(self):
        failures = [(1, b"", b"HTTP 502: Bad Gateway")] * (len(release_history.RETRY_PAUSES) + 1)
        run, calls = answers(*failures)
        with self.assertRaises(SystemExit) as stop:
            gh_api("repos/x/releases", run=run, sleep=self.pauses.append)
        self.assertIn("HTTP 502", str(stop.exception))
        self.assertEqual(len(calls), len(release_history.RETRY_PAUSES) + 1)
        self.assertEqual(tuple(self.pauses), release_history.RETRY_PAUSES)

    def test_a_good_first_answer_does_not_wait(self):
        run, calls = answers((0, b"raw", b""))
        self.assertEqual(gh_api("x", run=run, sleep=self.pauses.append), b"raw")
        self.assertEqual(self.pauses, [])


if __name__ == "__main__":
    unittest.main()
