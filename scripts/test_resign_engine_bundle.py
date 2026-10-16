"""Run with: python3 -m unittest discover -s scripts -p "test_*.py".

Every key and certificate here is generated per run (openssl), never a real one.
"""

import importlib.util
import json
import subprocess
import tarfile
import tempfile
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent


def module(name: str, file: str):
    spec = importlib.util.spec_from_file_location(name, SCRIPTS / file)
    loaded = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(loaded)
    return loaded


resign_engine_bundle = module("resign_engine_bundle", "resign-engine-bundle.py")
ORIGIN = "https://github.com/droidtop/enginehost-example-plugin"


def openssl(*arguments: str) -> None:
    subprocess.run(["openssl", *arguments], check=True, capture_output=True)


class Resign(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)
        r = self.root
        # A throwaway root and a plugin-signing leaf under it, the v1 profile.
        openssl("ecparam", "-name", "prime256v1", "-genkey", "-noout", "-out", str(r / "root-key.pem"))
        openssl("req", "-new", "-x509", "-key", str(r / "root-key.pem"), "-subj", "/CN=Test root", "-days", "2",
                "-addext", "basicConstraints=critical,CA:TRUE,pathlen:0",
                "-addext", "keyUsage=critical,keyCertSign", "-out", str(r / "root.pem"))
        openssl("pkey", "-in", str(r / "root-key.pem"), "-pubout", "-out", str(r / "root.pub"))
        openssl("ecparam", "-name", "prime256v1", "-genkey", "-noout", "-out", str(r / "leaf-key.pem"))
        openssl("req", "-new", "-key", str(r / "leaf-key.pem"), "-subj", "/CN=leaf", "-out", str(r / "leaf.csr"))
        (r / "leaf.ext").write_text(
            "basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature\n"
            "extendedKeyUsage=2.25.230248497294207031301727532441854392946.1\n"
            "subjectAltName=URI:urn:droidtop:origin:droidtop/enginehost-example-plugin\n"
        )
        openssl("x509", "-req", "-in", str(r / "leaf.csr"), "-CA", str(r / "root.pem"), "-CAkey", str(r / "root-key.pem"),
                "-CAcreateserial", "-days", "2", "-extfile", str(r / "leaf.ext"), "-out", str(r / "leaf.pem"))
        # A legacy-only bundle, built the way CI built the September releases.
        openssl("ecparam", "-name", "prime256v1", "-genkey", "-noout", "-out", str(r / "legacy-key.pem"))
        (r / "payload").mkdir()
        (r / "payload" / "classes.dex").write_bytes(b"dex\n035\0" + bytes(range(64)))
        (r / "metadata.json").write_text(json.dumps({
            "bundleId": "dev.enginehost.example", "engine": "example", "pluginVersion": "1.0.0-3",
            "apiVersion": 1, "entrypoint": "dev.enginehost.example.Core", "origin": ORIGIN,
            "dexFiles": ["classes.dex"], "capabilities": [],
        }))
        (r / "source").mkdir()
        self.source = r / "source" / "dev.enginehost.example.enginehost.tar.xz"
        subprocess.run([
            "python3", str(SCRIPTS / "build-engine-bundle.py"), "--metadata", str(r / "metadata.json"),
            "--payload", str(r / "payload"), "--private-key", str(r / "legacy-key.pem"), "--output", str(self.source),
            "--release-envelope", str(r / "source" / "enginehost-release.json"),
            "--public-key-document", str(r / "source" / "enginehost-public-key.json"),
        ], check=True, capture_output=True)

    def tearDown(self):
        self.directory.cleanup()

    def resign(self, bundle: Path, output: str) -> Path:
        return resign_engine_bundle.resign(
            bundle, self.root / "leaf-key.pem", self.root / "leaf.pem", self.root / output,
            self.root / "source" / "enginehost-release.json",
        )

    def verify(self, bundle: Path, *flags: str) -> dict:
        result = subprocess.run(["python3", str(SCRIPTS / "verify-engine-bundle.py"), str(bundle), *flags],
                                check=True, capture_output=True, text=True)
        return json.loads(result.stdout)

    def test_the_re_signed_bundle_verifies_both_ways_and_changes_no_member(self):
        out = self.resign(self.source, "out")
        self.assertTrue(self.verify(out, "--root-key", str(self.root / "root.pub"), "--require-v1")["verified"])
        self.assertTrue(self.verify(
            out, "--repository-key", str(self.root / "source" / "enginehost-public-key.json"))["verified"])
        with tarfile.open(self.source, "r:xz") as before, tarfile.open(out, "r:xz") as after:
            old = [m.name for m in before.getmembers()]
            new = [m.name for m in after.getmembers()]
            self.assertEqual(new, old[:2] + ["enginehost-bundle.jws"] + old[2:])
            for name in old:
                self.assertEqual(before.extractfile(name).read(), after.extractfile(name).read(), name)

    def test_the_envelope_gains_the_same_jws_and_keeps_the_legacy_signature(self):
        out = self.resign(self.source, "out")
        envelope = json.loads((self.root / "out" / "enginehost-release.json").read_text())
        with tarfile.open(out, "r:xz") as archive:
            jws = archive.extractfile("enginehost-bundle.jws").read().decode().strip()
        (entry,) = envelope["bundles"]
        self.assertEqual(entry["jws"], jws)
        self.assertIn("signatureBase64", entry)

    def test_a_bundle_that_already_carries_v1_is_refused(self):
        out = self.resign(self.source, "out")
        with self.assertRaises(SystemExit):
            self.resign(out, "again")

    def test_a_certificate_for_another_origin_is_refused(self):
        metadata = json.loads((self.root / "metadata.json").read_text())
        metadata["origin"] = "https://github.com/droidtop/enginehost-other-plugin"
        (self.root / "metadata.json").write_text(json.dumps(metadata))
        other = self.root / "other" / "dev.enginehost.example.enginehost.tar.xz"
        subprocess.run([
            "python3", str(SCRIPTS / "build-engine-bundle.py"), "--metadata", str(self.root / "metadata.json"),
            "--payload", str(self.root / "payload"), "--private-key", str(self.root / "legacy-key.pem"),
            "--output", str(other),
        ], check=True, capture_output=True)
        with self.assertRaises(SystemExit):
            resign_engine_bundle.resign(other, self.root / "leaf-key.pem", self.root / "leaf.pem",
                                        self.root / "out-other", None)


if __name__ == "__main__":
    unittest.main()
