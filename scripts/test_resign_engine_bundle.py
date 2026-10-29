"""Run with: python3 -m unittest discover -s scripts -p "test_*.py".

Every key and certificate here is generated per run (openssl), never a real one.
"""

import base64
import hashlib
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
build_engine_bundle = module("build_engine_bundle_for_tests", "build-engine-bundle.py")
ORIGIN = "https://github.com/droidtop/enginehost-example-plugin"


def openssl(*arguments: str) -> None:
    subprocess.run(["openssl", *arguments], check=True, capture_output=True)


def legacy_bundle(metadata: Path, payload: Path, output: Path, envelope: Path | None = None) -> None:
    """A bundle in the shape CI built the September releases: a manifest with a `signing` block and
    `enginehost-bundle.sig` after it. Nothing makes or verifies that signature any more
    (Droidtop/tracker#768), so its bytes here are a stand-in."""
    files = build_engine_bundle.payload_files(payload)
    aggregate = hashlib.sha256()
    records = []
    for relative, _path, data, mode in files:
        aggregate.update(relative.encode() + b"\0" + str(len(data)).encode("ascii") + b"\0" + data)
        records.append({"path": relative, "size": len(data), "sha256": build_engine_bundle.sha256(data), "mode": mode})
    manifest = json.loads(metadata.read_text())
    manifest.update({
        "formatVersion": 1, "assetName": output.name, "origin": manifest["origin"].rstrip("/"),
        "payloadSha256": aggregate.hexdigest().upper(), "files": records,
        "signing": {"algorithm": "SHA256withECDSA", "publicKeySpki": "AAAA", "keySha256": "00" * 32},
    })
    manifest_bytes = json.dumps(manifest, sort_keys=True, separators=(",", ":")).encode()
    signature = base64.b64encode(b"legacy signature stand-in")
    output.parent.mkdir(parents=True, exist_ok=True)
    with tarfile.open(output, "w:xz", format=tarfile.USTAR_FORMAT) as archive:
        archive.addfile(*build_engine_bundle.tar_member("enginehost-bundle.json", manifest_bytes))
        archive.addfile(*build_engine_bundle.tar_member("enginehost-bundle.sig", signature + b"\n"))
        for relative, _path, data, mode in files:
            archive.addfile(*build_engine_bundle.tar_member(relative, data, mode))
    if envelope is not None:
        entry = {"manifestBase64": base64.b64encode(manifest_bytes).decode(), "signatureBase64": signature.decode()}
        envelope.write_text(json.dumps({"formatVersion": 1, "bundles": [entry]}))


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
        # A legacy-only bundle, in the shape CI built the September releases.
        (r / "payload").mkdir()
        (r / "payload" / "classes.dex").write_bytes(b"dex\n035\0" + bytes(range(64)))
        (r / "metadata.json").write_text(json.dumps({
            "bundleId": "dev.enginehost.example", "engine": "example", "pluginVersion": "1.0.0-3",
            "apiVersion": 1, "entrypoint": "dev.enginehost.example.Core", "origin": ORIGIN,
            "dexFiles": ["classes.dex"], "capabilities": [],
        }))
        (r / "source").mkdir()
        self.source = r / "source" / "dev.enginehost.example.enginehost.tar.xz"
        legacy_bundle(r / "metadata.json", r / "payload", self.source, r / "source" / "enginehost-release.json")

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

    def test_the_re_signed_bundle_verifies_in_v1_and_drops_only_the_legacy_signature(self):
        out = self.resign(self.source, "out")
        self.assertTrue(self.verify(out, "--root-key", str(self.root / "root.pub"))["verified"])
        with tarfile.open(self.source, "r:xz") as before, tarfile.open(out, "r:xz") as after:
            old = [m.name for m in before.getmembers()]
            new = [m.name for m in after.getmembers()]
            self.assertEqual(new, [old[0], "enginehost-bundle.jws"] + old[2:])
            for name in new:
                if name != "enginehost-bundle.jws":
                    self.assertEqual(before.extractfile(name).read(), after.extractfile(name).read(), name)

    def test_the_envelope_gains_the_same_jws_and_loses_the_legacy_signature(self):
        out = self.resign(self.source, "out")
        envelope = json.loads((self.root / "out" / "enginehost-release.json").read_text())
        with tarfile.open(out, "r:xz") as archive:
            jws = archive.extractfile("enginehost-bundle.jws").read().decode().strip()
        (entry,) = envelope["bundles"]
        self.assertEqual(entry["jws"], jws)
        self.assertNotIn("signatureBase64", entry)

    def test_verify_refuses_a_bundle_signed_only_the_legacy_way(self):
        result = subprocess.run(["python3", str(SCRIPTS / "verify-engine-bundle.py"), str(self.source)],
                                capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("no v1 signature", result.stderr)

    def test_a_fresh_build_carries_the_v1_signature_alone(self):
        built = self.root / "built" / "dev.enginehost.example.enginehost.tar.xz"
        subprocess.run([
            "python3", str(SCRIPTS / "build-engine-bundle.py"), "--metadata", str(self.root / "metadata.json"),
            "--payload", str(self.root / "payload"), "--certificate-key", str(self.root / "leaf-key.pem"),
            "--certificate-chain", str(self.root / "leaf.pem"), "--output", str(built),
        ], check=True, capture_output=True)
        with tarfile.open(built, "r:xz") as archive:
            names = [m.name for m in archive.getmembers()]
            manifest = json.loads(archive.extractfile("enginehost-bundle.json").read())
        self.assertEqual(names[:2], ["enginehost-bundle.json", "enginehost-bundle.jws"])
        self.assertNotIn("enginehost-bundle.sig", names)
        self.assertNotIn("signing", manifest)
        self.assertTrue(self.verify(built, "--root-key", str(self.root / "root.pub"))["verified"])

    def test_a_bundle_that_already_carries_v1_is_refused(self):
        out = self.resign(self.source, "out")
        with self.assertRaises(SystemExit):
            self.resign(out, "again")

    def test_a_certificate_for_another_origin_is_refused(self):
        metadata = json.loads((self.root / "metadata.json").read_text())
        metadata["origin"] = "https://github.com/droidtop/enginehost-other-plugin"
        (self.root / "metadata.json").write_text(json.dumps(metadata))
        other = self.root / "other" / "dev.enginehost.example.enginehost.tar.xz"
        legacy_bundle(self.root / "metadata.json", self.root / "payload", other)
        with self.assertRaises(SystemExit):
            resign_engine_bundle.resign(other, self.root / "leaf-key.pem", self.root / "leaf.pem",
                                        self.root / "out-other", None)


if __name__ == "__main__":
    unittest.main()
