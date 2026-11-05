"""Run with: python3 -m unittest discover -s scripts -p "test_*.py".

The signing roots across the key rotation of 2026-11-04 (Droidtop/tracker#876, #550): the new root has no end, the old
one is trusted until the instant app/src/main/assets/trust/enginehost-roots.json names. Every key here is generated per
run (openssl), never a real one; the real pins are only read.
"""

import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPTS))
import bundle_jws  # noqa: E402

from pki_for_tests import make_pki  # noqa: E402

ORIGIN = "https://github.com/droidtop/enginehost-example-plugin"

TRUST = SCRIPTS.parent / "app/src/main/assets/trust"
EXPIRY = int(datetime(2026, 11, 11, 23, 59, 59, tzinfo=timezone.utc).timestamp())
NEW_ROOT_SPKI_SHA256 = "9629576d51bf000c2a8df606c66c1366fa27481e2297dc4e1ab68a646b95f5f0"


def pem_text(path: Path) -> str:
    return path.read_text()


class Roots(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)
        make_pki(self.root, "old-")
        make_pki(self.root, "new-")
        self.old = bundle_jws.Root(bundle_jws.pem_public_key(pem_text(self.root / "old-root.pub")), EXPIRY)
        self.new = bundle_jws.Root(bundle_jws.pem_public_key(pem_text(self.root / "new-root.pub")))
        self.content = b'{"bundleId":"dev.enginehost.example"}'

    def tearDown(self):
        self.directory.cleanup()

    def sign(self, prefix: str) -> str:
        chain = bundle_jws.pem_certificates(pem_text(self.root / f"{prefix}leaf.pem"))
        return bundle_jws.sign(self.content, self.root / f"{prefix}leaf-key.pem", chain, "plugin", detached=True)

    def test_an_old_root_chain_is_accepted_through_the_last_second_and_refused_one_second_later(self):
        jws = self.sign("old-")
        roots = [self.new, self.old]
        for now in (EXPIRY - 86400, EXPIRY, EXPIRY + 0.999):
            named = bundle_jws.verify(jws, "plugin", roots, self.content, now)
            self.assertEqual(named["anchorSpki"], self.old.spki)
        for now in (EXPIRY + 1, EXPIRY + 400 * 86400):
            with self.assertRaises(bundle_jws.Refused):
                bundle_jws.verify(jws, "plugin", roots, self.content, now)

    def test_a_new_root_chain_is_accepted_before_and_after(self):
        jws = self.sign("new-")
        for now in (EXPIRY - 86400, EXPIRY, EXPIRY + 1, EXPIRY + 400 * 86400):
            named = bundle_jws.verify(jws, "plugin", [self.new, self.old], self.content, now)
            self.assertEqual(named["anchorSpki"], self.new.spki)

    def test_a_bare_key_has_no_end_and_a_chain_under_another_root_is_refused(self):
        jws = self.sign("old-")
        bundle_jws.verify(jws, "plugin", self.old.spki, self.content, EXPIRY + 400 * 86400)
        with self.assertRaises(bundle_jws.Refused):
            bundle_jws.verify(jws, "plugin", [self.new], self.content, EXPIRY - 1)

    def test_the_pinned_list_reads_its_roots_and_their_end(self):
        trust = self.root / "trust"
        trust.mkdir()
        (trust / "enginehost-root.pub").write_text(pem_text(self.root / "new-root.pub"))
        (trust / "enginehost-root-old.pub").write_text(pem_text(self.root / "old-root.pub"))
        (trust / "enginehost-roots.json").write_text(json.dumps({"roots": [
            {"key": "enginehost-root.pub"}, {"key": "enginehost-root-old.pub", "notAfter": "2026-11-11T23:59:59Z"},
        ]}))
        self.assertEqual(bundle_jws.pinned_roots(trust), [self.new, self.old])
        (trust / "enginehost-roots.json").write_text(json.dumps({"roots": [{"key": "../x.pub"}]}))
        with self.assertRaises(bundle_jws.Refused):
            bundle_jws.pinned_roots(trust)

    def test_verify_engine_bundle_follows_the_trust_directory_and_the_clock(self):
        built = self.root / "built" / "dev.enginehost.example.enginehost.tar.xz"
        (self.root / "payload").mkdir()
        (self.root / "payload" / "classes.dex").write_bytes(b"dex\n035\0" + bytes(range(64)))
        (self.root / "metadata.json").write_text(json.dumps({
            "bundleId": "dev.enginehost.example", "engine": "example", "pluginVersion": "1.0.0-3",
            "apiVersion": 1, "entrypoint": "dev.enginehost.example.Core", "origin": ORIGIN,
            "dexFiles": ["classes.dex"], "capabilities": [],
        }))
        subprocess.run([
            "python3", str(SCRIPTS / "build-engine-bundle.py"), "--metadata", str(self.root / "metadata.json"),
            "--payload", str(self.root / "payload"), "--certificate-key", str(self.root / "old-leaf-key.pem"),
            "--certificate-chain", str(self.root / "old-leaf.pem"), "--output", str(built),
        ], check=True, capture_output=True)

        def trust(old_end: str) -> Path:
            directory = self.root / ("trust-" + old_end[:4])
            directory.mkdir()
            (directory / "enginehost-root.pub").write_text(pem_text(self.root / "new-root.pub"))
            (directory / "enginehost-root-old.pub").write_text(pem_text(self.root / "old-root.pub"))
            (directory / "enginehost-roots.json").write_text(json.dumps({"roots": [
                {"key": "enginehost-root.pub"}, {"key": "enginehost-root-old.pub", "notAfter": old_end},
            ]}))
            return directory

        def run(directory: Path):
            return subprocess.run(["python3", str(SCRIPTS / "verify-engine-bundle.py"), str(built), "--trust-dir", str(directory)],
                                  capture_output=True, text=True)

        self.assertEqual(run(trust("2999-01-01T00:00:00Z")).returncode, 0)
        retired = run(trust("2001-01-01T00:00:00Z"))
        self.assertNotEqual(retired.returncode, 0)
        self.assertIn("retired", retired.stderr)


class PinnedFiles(unittest.TestCase):
    """The files this repository pins, read as the app reads them."""

    def test_the_new_root_has_no_end_and_the_old_one_ends_at_the_decided_instant(self):
        new, old = bundle_jws.pinned_roots(TRUST)
        self.assertEqual(hashlib.sha256(new.spki).hexdigest(), NEW_ROOT_SPKI_SHA256)
        self.assertIsNone(new.not_after)
        self.assertEqual(old.not_after, EXPIRY)
        self.assertNotEqual(old.spki, new.spki)
        # The file that has always held "the" root is now the new one.
        self.assertEqual(bundle_jws.pem_public_key(pem_text(TRUST / "enginehost-root.pub")), new.spki)


if __name__ == "__main__":
    unittest.main()
