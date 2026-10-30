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
import io
import zipfile
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


ELF = {"arm64-v8a": 183, "x86_64": 62, "armeabi-v7a": 40, "x86": 3}


def elf(abi: str) -> bytes:
    """Enough of an ELF header for the conformance check: class and machine."""
    header = bytearray(64)
    header[:4] = b"\x7fELF"
    header[4] = 1 if abi in ("armeabi-v7a", "x86") else 2
    header[5] = 1
    header[18:20] = ELF[abi].to_bytes(2, "little")
    return bytes(header)


class OnlySixtyFourBit(unittest.TestCase):
    """--only-64-bit: the September stable builds re-signed without their 32-bit libraries (#646)."""

    ABIS = ("arm64-v8a", "armeabi-v7a", "x86", "x86_64")

    def setUp(self):
        Resign.setUp(self)
        payload = self.root / "payload"
        for abi in self.ABIS:
            (payload / "lib" / abi).mkdir(parents=True)
            (payload / "lib" / abi / "libengine.so").write_bytes(elf(abi) + abi.encode())
        apk = io.BytesIO()
        with zipfile.ZipFile(apk, "w") as archive:
            archive.writestr("AndroidManifest.xml", b"manifest")
            for abi in self.ABIS:
                archive.writestr(zipfile.ZipInfo(f"lib/{abi}/libengine.so"), elf(abi) + abi.encode())
            stored = zipfile.ZipInfo("assets/stored.bin")
            stored.compress_type = zipfile.ZIP_STORED
            archive.writestr(stored, b"stored")
        (payload / "runtime.apk").write_bytes(apk.getvalue())
        metadata = json.loads((self.root / "metadata.json").read_text())
        metadata.update({"pluginVersion": "1.0.13-2", "resourceApks": ["runtime.apk"]})
        (self.root / "metadata.json").write_text(json.dumps(metadata))
        self.source = self.root / "v1" / "dev.enginehost.example.enginehost.tar.xz"
        subprocess.run([
            "python3", str(SCRIPTS / "build-engine-bundle.py"), "--metadata", str(self.root / "metadata.json"),
            "--payload", str(payload), "--certificate-key", str(self.root / "leaf-key.pem"),
            "--certificate-chain", str(self.root / "leaf.pem"), "--output", str(self.source),
            "--release-envelope", str(self.root / "v1" / "enginehost-release.json"),
        ], check=True, capture_output=True)

    def tearDown(self):
        self.directory.cleanup()

    def strip(self, version: str = "1.0.13-3") -> Path:
        return resign_engine_bundle.resign(
            self.source, self.root / "leaf-key.pem", self.root / "leaf.pem", self.root / "out",
            self.root / "v1" / "enginehost-release.json", version,
        )

    def test_the_32_bit_libraries_leave_the_bundle_and_its_apk_and_it_verifies(self):
        out = self.strip()
        verified = Resign.verify(self, out, "--root-key", str(self.root / "root.pub"))
        self.assertTrue(verified["verified"])
        with tarfile.open(out, "r:xz") as archive:
            names = [m.name for m in archive.getmembers()]
            manifest = json.loads(archive.extractfile("enginehost-bundle.json").read())
            apk = zipfile.ZipFile(io.BytesIO(archive.extractfile("runtime.apk").read()))
            sixty_four = archive.extractfile("lib/x86_64/libengine.so").read()
        self.assertEqual(names[:2], ["enginehost-bundle.json", "enginehost-bundle.jws"])
        self.assertEqual([n for n in names if n.startswith("lib/") and n.endswith(".so")],
                         ["lib/arm64-v8a/libengine.so", "lib/x86_64/libengine.so"])
        self.assertEqual(sorted(i.filename for i in apk.infolist() if i.filename.startswith("lib/")),
                         ["lib/arm64-v8a/libengine.so", "lib/x86_64/libengine.so"])
        self.assertEqual(apk.read("assets/stored.bin"), b"stored")
        self.assertEqual(sixty_four, elf("x86_64") + b"x86_64")
        self.assertEqual(manifest["pluginVersion"], "1.0.13-3")
        self.assertEqual([f["path"] for f in manifest["files"]],
                         sorted(f["path"] for f in manifest["files"]))
        self.assertFalse(any("armeabi" in f["path"] or "/x86/" in f["path"] for f in manifest["files"]))

    def test_a_stored_apk_entry_stays_aligned(self):
        out = self.strip()
        with tarfile.open(out, "r:xz") as archive:
            data = archive.extractfile("runtime.apk").read()
        info = zipfile.ZipFile(io.BytesIO(data)).getinfo("assets/stored.bin")
        name_length = int.from_bytes(data[info.header_offset + 26:info.header_offset + 28], "little")
        extra_length = int.from_bytes(data[info.header_offset + 28:info.header_offset + 30], "little")
        self.assertEqual((info.header_offset + 30 + name_length + extra_length) % 4, 0)

    def test_the_result_passes_the_signing_gates_abi_check(self):
        conformance = module("check_bundle_conformance_for_tests", "check-bundle-conformance.py")
        out = self.strip()
        before, after = [], []
        with tarfile.open(self.source, "r:xz") as archive:
            entries = [(m.name, (lambda m=m: archive.extractfile(m).read())) for m in archive.getmembers() if m.isfile()]
            conformance.check_libraries("", entries, before)
        with tarfile.open(out, "r:xz") as archive:
            entries = [(m.name, (lambda m=m: archive.extractfile(m).read())) for m in archive.getmembers() if m.isfile()]
            conformance.check_libraries("", entries, after)
            apk = zipfile.ZipFile(io.BytesIO(archive.extractfile("runtime.apk").read()))
            conformance.check_libraries("runtime.apk!", [(i.filename, (lambda i=i: apk.read(i))) for i in apk.infolist()], after)
        self.assertTrue(any("32-bit" in problem for problem in before))
        self.assertEqual(after, [])

    def test_the_envelope_names_the_new_manifest(self):
        out = self.strip()
        envelope = json.loads((self.root / "out" / "enginehost-release.json").read_text())
        with tarfile.open(out, "r:xz") as archive:
            manifest_bytes = archive.extractfile("enginehost-bundle.json").read()
            jws = archive.extractfile("enginehost-bundle.jws").read().decode().strip()
        (entry,) = envelope["bundles"]
        self.assertEqual(base64.b64decode(entry["manifestBase64"]), manifest_bytes)
        self.assertEqual(entry["jws"], jws)

    def test_a_version_that_is_not_newer_is_refused(self):
        for version in ("1.0.13-2", "1.0.12-9", "1.0.13"):
            with self.assertRaises(SystemExit):
                self.strip(version)


if __name__ == "__main__":
    unittest.main()
