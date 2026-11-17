import importlib.util
import pathlib
import tempfile
import unittest

_spec = importlib.util.spec_from_file_location("check_copy", pathlib.Path(__file__).with_name("check-copy.py"))
check_copy = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(check_copy)


def _res(root: pathlib.Path, body: str) -> None:
    folder = root / "app" / "src" / "main" / "res" / "values"
    folder.mkdir(parents=True)
    (folder / "strings.xml").write_text(f"<resources>\n{body}\n</resources>\n", encoding="utf-8")


class CheckCopyTest(unittest.TestCase):
    def test_splices_and_passive_errors_are_found(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            _res(
                root,
                '<string name="a">Saved; try again</string>\n'
                '<string name="b">The file could not be read</string>\n'
                '<string name="c">Tap here to start</string>',
            )
            self.assertEqual(3, len(list(check_copy.offences(root))))

    def test_active_copy_passes(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            _res(
                root,
                '<string name="a">Saved. Try again</string>\n'
                '<string name="b">Couldn\\\'t read the file</string>\n'
                '<string name="c">Pick a folder (one per game)</string>',
            )
            self.assertEqual([], list(check_copy.offences(root)))


if __name__ == "__main__":
    unittest.main()
