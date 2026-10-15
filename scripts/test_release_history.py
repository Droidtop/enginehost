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


def envelope(bundle_id, version):
    manifest = release_history.base64.b64encode(
        release_history.json.dumps({"bundleId": bundle_id, "pluginVersion": version}).encode()).decode()
    return {"formatVersion": 1, "bundles": [{"manifestBase64": manifest}]}


def listing(*releases):
    """One page of releases: (tag, asset id) each, with an enginehost-release.json asset."""
    return [[{"tag_name": tag, "target_commitish": "a" * 40,
              "assets": [{"name": "enginehost-release.json", "id": asset}]} for tag, asset in releases]]


class RelistOnGone(unittest.TestCase):
    """Droidtop/tracker#551: a rolling release recreated mid-read is listed again, never skipped."""

    def setUp(self):
        self.pauses = []

    def api(self, listings, assets):
        """listings: one page set per listing call; assets: id -> envelope, or None for a 404."""
        calls = {"listings": 0}

        def api(*arguments, parse=None, gone_is_final=False):
            if arguments[0].startswith("repos/x/releases?"):
                calls["listings"] += 1
                return listings[min(calls["listings"], len(listings)) - 1]
            asset = int(arguments[-1].rsplit("/", 1)[1])
            if assets.get(asset) is None:
                self.assertTrue(gone_is_final, "an envelope fetch must hear of a 404 at once")
                raise release_history.Gone("gh: Not Found (HTTP 404)")
            return assets[asset]
        return api, calls

    def test_a_gone_envelope_is_found_again_in_a_fresh_listing(self):
        # The unstable release was recreated between the listing and the fetch:
        # asset 2 is gone, its replacement 3 carries the same build.
        api, calls = self.api(
            [listing(("v1", 1), ("unstable", 2)), listing(("v1", 1), ("unstable", 3))],
            {1: envelope("dev.enginehost.x", "1.0.0-1"), 3: envelope("dev.enginehost.x", "1.0.0-2")},
        )
        builds = release_history.published_builds("x", "dev.enginehost.x", api=api, sleep=self.pauses.append)
        self.assertEqual(sorted(b.version for b in builds), ["1.0.0-1", "1.0.0-2"])
        self.assertEqual(calls["listings"], 2)
        self.assertEqual(self.pauses, [release_history.RELIST_PAUSES[0]])

    def test_an_envelope_still_missing_fails_the_build_rather_than_being_skipped(self):
        api, calls = self.api([listing(("v1", 1), ("unstable", 2))], {1: envelope("dev.enginehost.x", "1.0.0-1")})
        with self.assertRaises(SystemExit) as stop:
            release_history.published_builds("x", "dev.enginehost.x", api=api, sleep=self.pauses.append)
        self.assertIn("still missing", str(stop.exception))
        self.assertEqual(calls["listings"], len(release_history.RELIST_PAUSES) + 1)
        self.assertEqual(tuple(self.pauses), release_history.RELIST_PAUSES)

    def test_gh_api_raises_gone_on_404_without_retrying_when_asked(self):
        run, calls = answers((1, b"", b"gh: Not Found (HTTP 404)"))
        with self.assertRaises(release_history.Gone):
            gh_api("repos/x/releases/assets/2", run=run, sleep=self.pauses.append, gone_is_final=True)
        self.assertEqual(len(calls), 1)
        self.assertEqual(self.pauses, [])


if __name__ == "__main__":
    unittest.main()
