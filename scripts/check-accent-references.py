#!/usr/bin/env python3
"""Keep the accent colours where the accent painter reaches them (Droidtop/tracker#644).

Enginehost draws its accent from Settings > Appearance or a launching app, not only
from the colour the XML palette ships (docs/ui-v2.md, "The accent at run time").
Android cannot recolour a resource, so AccentViewInflater repaints a view as it is
inflated, and only through the attributes it reads (PAINTED_ATTRIBUTES below, the same
list as AccentViewInflater.PAINTED_ATTRS). An accent colour named anywhere else would
stay the shipped azure whatever the person chose. This check refuses that, so a new use
either goes through one of those attributes or is added to AccentDrawables.

Usage: check-accent-references.py [res-dir]   (default app/src/main/res)
Exits 1 and lists each offending reference.
"""
import os
import re
import sys

ACCENT = re.compile(r"@color/(eh_accent\w*|eh_on_accent\w*|eh_focus_ring|eh_focus_fill|eh_button_primary_text)\b")

# Attributes AccentViewInflater repaints, in a layout or a style item.
PAINTED_ATTRIBUTES = {"android:background", "android:textColor", "android:tint",
                      "android:backgroundTint", "android:drawableTint"}

# Files whose accent references are the definitions the painter mirrors or replaces:
# the palettes, the accent drawables AccentDrawables rebuilds, the primary text selector,
# the theme's colorAccent family (the painter tints switches and bars itself), and the
# launcher icon, which Android draws outside the app.
DEFINITIONS = {
    "values/colors.xml", "values-night/colors.xml",
    "values/themes.xml",
    "color/eh_button_primary_text.xml",
    "drawable/eh_btn_primary_bg.xml", "drawable/eh_btn_secondary_bg.xml",
    "drawable/eh_btn_tonal_bg.xml", "drawable/eh_card_focusable_bg.xml",
    "drawable/eh_card_selectable_bg.xml", "drawable/eh_row_bg.xml",
    "drawable/eh_edittext_bg.xml",
    "drawable/ic_launcher_background.xml", "drawable/ic_launcher_foreground_placeholder.xml",
}

ATTRIBUTE = re.compile(r'([\w:]+)\s*=\s*"([^"]*)"')
STYLE_ITEM = re.compile(r'<item\s+name="([^"]+)"\s*>([^<]*)</item>')


def violations(res_dir):
    found = []
    for folder, _, files in os.walk(res_dir):
        for name in files:
            if not name.endswith(".xml"):
                continue
            path = os.path.join(folder, name)
            relative = os.path.relpath(path, res_dir).replace(os.sep, "/")
            if relative in DEFINITIONS:
                continue
            with open(path, encoding="utf-8") as handle:
                text = handle.read()
            for attribute, value in ATTRIBUTE.findall(text):
                if ACCENT.search(value) and attribute not in PAINTED_ATTRIBUTES:
                    found.append(f"{relative}: {attribute}=\"{value}\"")
            for attribute, value in STYLE_ITEM.findall(text):
                if ACCENT.search(value) and attribute not in PAINTED_ATTRIBUTES:
                    found.append(f"{relative}: <item name=\"{attribute}\">{value.strip()}</item>")
    return found


def main(argv):
    res_dir = argv[1] if len(argv) > 1 else os.path.join("app", "src", "main", "res")
    found = violations(res_dir)
    for line in found:
        print(f"accent colour outside the painter's reach: {line}")
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
