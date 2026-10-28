import importlib.util
import os
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
SPEC = importlib.util.spec_from_file_location("check_accent_references", os.path.join(HERE, "check-accent-references.py"))
check = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(check)


class AccentReferencesTest(unittest.TestCase):
    def test_the_app_names_the_accent_only_where_the_painter_reaches(self):
        res = os.path.join(HERE, "..", "app", "src", "main", "res")
        self.assertEqual([], check.violations(res))

    def test_an_unpainted_attribute_is_refused(self):
        with tempfile.TemporaryDirectory() as res:
            os.makedirs(os.path.join(res, "layout"))
            with open(os.path.join(res, "layout", "x.xml"), "w", encoding="utf-8") as f:
                f.write('<TextView android:textColorLink="@color/eh_accent" android:textColor="@color/eh_accent" />')
            self.assertEqual(['layout/x.xml: android:textColorLink="@color/eh_accent"'], check.violations(res))

    def test_a_style_item_is_read_like_an_attribute(self):
        with tempfile.TemporaryDirectory() as res:
            os.makedirs(os.path.join(res, "values"))
            with open(os.path.join(res, "values", "styles.xml"), "w", encoding="utf-8") as f:
                f.write('<resources><style name="A"><item name="android:textColor">@color/eh_accent</item>'
                        '<item name="android:colorEdgeEffect">@color/eh_accent</item></style></resources>')
            self.assertEqual(['values/styles.xml: <item name="android:colorEdgeEffect">@color/eh_accent</item>'],
                             check.violations(res))


if __name__ == "__main__":
    unittest.main()
