"""Run with: python3 -m unittest discover -s scripts -p "test_*.py" (the script
under test has a dash in its name, so it is loaded by path)."""

import importlib.util
import os
import tempfile
import unittest
import zipfile
from pathlib import Path

_spec = importlib.util.spec_from_file_location(
    "check_bundle_conformance", Path(__file__).with_name("check-bundle-conformance.py"))
_module = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_module)
check = _module.check
known_engines = _module.known_engines

ENGINE_NAMES = Path(__file__).resolve().parent.parent / "app/src/main/kotlin/dev/enginehost/EngineNames.kt"
ENGINES = {"love2d", "renpy", "rpgmaker", "html"}


def elf(machine: int, bits: int = 2) -> bytes:
    header = bytearray(64)
    header[:4] = b"\x7fELF"
    header[4] = bits
    header[5] = 1
    header[18:20] = machine.to_bytes(2, "little")
    return bytes(header)


ARM64 = elf(183)
X86_64 = elf(62)


def metadata(**overrides):
    value = {
        "bundleId": "dev.enginehost.love2d.11.v1",
        "engine": "love2d",
        "pluginVersion": "1.0.0",
        "dexFiles": ["classes.dex"],
        "capabilities": [{"id": "love2d-11", "engineContext": "standard", "runtimeVersion": "11.5",
                          "supportedSeries": ["11"]}],
    }
    value.update(overrides)
    return value


class Conformance(unittest.TestCase):
    def setUp(self):
        self._temporary = tempfile.TemporaryDirectory()
        self.payload = Path(self._temporary.name)
        self.write("classes.dex", b"dex\n")

    def tearDown(self):
        self._temporary.cleanup()

    def write(self, name, data, mode=0o644):
        path = self.payload / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        os.chmod(path, mode)

    def problems(self, **overrides):
        return check(metadata(**overrides), self.payload, ENGINES)

    def test_both_abis_with_the_same_libraries_pass(self):
        self.write("lib/arm64-v8a/liblove.so", ARM64)
        self.write("lib/x86_64/liblove.so", X86_64)
        self.assertEqual([], self.problems())

    def test_no_native_code_is_architecture_agnostic(self):
        self.assertEqual([], self.problems())

    def test_one_abi_is_refused(self):
        self.write("lib/arm64-v8a/liblove.so", ARM64)
        self.assertTrue(any("carries no x86_64" in p for p in self.problems()))

    def test_32_bit_abi_is_refused(self):
        self.write("lib/arm64-v8a/liblove.so", ARM64)
        self.write("lib/x86_64/liblove.so", X86_64)
        self.write("lib/armeabi-v7a/liblove.so", b"\x7fELF\x01")
        self.assertTrue(any("32-bit" in p for p in self.problems()))

    def test_a_library_missing_from_one_abi_is_refused(self):
        self.write("lib/arm64-v8a/liblove.so", ARM64)
        self.write("lib/arm64-v8a/libextra.so", ARM64)
        self.write("lib/x86_64/liblove.so", X86_64)
        self.assertEqual(["lib/arm64-v8a/libextra.so has no x86_64 counterpart"], self.problems())

    def test_a_library_in_the_wrong_abi_directory_is_refused(self):
        self.write("lib/arm64-v8a/liblove.so", X86_64)
        self.write("lib/x86_64/liblove.so", X86_64)
        self.assertTrue(any("not arm64-v8a" in p for p in self.problems()))

    def test_libraries_inside_a_payload_apk_are_checked(self):
        with zipfile.ZipFile(self.payload / "runtime.apk", "w") as apk:
            apk.writestr("classes.dex", b"dex\n")
            apk.writestr("lib/arm64-v8a/libmain.so", ARM64)
        problems = self.problems(resourceApks=["runtime.apk"])
        self.assertEqual(["runtime.apk!/lib/ carries no x86_64: every core with native code ships both "
                          "arm64-v8a and x86_64 (docs/engine-bundle-format.md)"], problems)

    def test_declared_files_must_be_in_the_payload(self):
        self.assertEqual(["dexFiles names missing.dex, which the payload does not carry"],
                         self.problems(dexFiles=["missing.dex"]))

    def test_an_engine_the_app_cannot_name_is_refused(self):
        problems = self.problems(engine="twine")
        self.assertTrue(any("'twine'" in p for p in problems))

    def test_a_capability_engine_must_be_one_the_bundle_lists(self):
        capability = {"id": "mv", "engine": "rpgmaker", "engineContext": "mv", "runtimeVersion": "1",
                      "acceptsAnyEngineVersion": True}
        self.assertEqual([], self.problems(engine="html", engines=["html", "rpgmaker"], capabilities=[capability]))
        self.assertTrue(self.problems(engine="html", capabilities=[capability]))

    def test_a_capability_that_accepts_no_version_is_refused(self):
        capability = {"id": "c", "engineContext": "standard", "runtimeVersion": "11.5"}
        self.assertTrue(any("accepts no engine version" in p for p in self.problems(capabilities=[capability])))

    def test_group_writable_and_setuid_files_are_refused(self):
        self.write("classes.dex", b"dex\n", 0o664)
        self.assertTrue(any("writable" in p for p in self.problems()))

    def test_engine_ids_come_from_the_app(self):
        engines = known_engines(ENGINE_NAMES)
        self.assertIn("renpy", engines)
        self.assertIn("kirikiri2", engines)
        self.assertNotIn("twine", engines)


if __name__ == "__main__":
    unittest.main()
