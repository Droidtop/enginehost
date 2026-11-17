#!/usr/bin/env python3
"""Fails when a string resource breaks the copy rule Enginehost shares with droidtop.

One rule for both apps (Droidtop/tracker#945, droidtop's docs/DESIGN-LANGUAGE.md "Copy" and its
scripts/check-ui-rules.sh copy-phrases-res): a string says one thing in active words, so no
semicolon splice (two sentences instead), no "its own page", errors as "Couldn't X" never
"X could not be Y", no "Recommended." and no "Press to" / "Tap here". The pattern is droidtop's,
word for word, so the two checks cannot drift apart.

Usage: check-copy.py [root]   (default: the repository root, one level above this file)
"""
import pathlib
import re
import sys

PATTERN = re.compile(
    r"([a-z)]; [a-z]|its own (page|screen)|(cannot|can not|could not|couldn.t) be [a-z]+|Recommended\.|"
    r"\b(Press|Select|Tap|Click) (to|here)\b)"
)
STRING = re.compile(r"<string\b[^>]*>([^<]*)</string>")


def offences(root: pathlib.Path):
    for path in sorted(root.glob("**/src/main/res/values*/strings.xml")):
        if "build" in path.parts:
            continue
        for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            for text in STRING.findall(line):
                if PATTERN.search(text):
                    yield f"{path.relative_to(root)}:{number}: {text.strip()}"


def main(argv):
    root = pathlib.Path(argv[1]) if len(argv) > 1 else pathlib.Path(__file__).resolve().parent.parent
    found = list(offences(root))
    for line in found:
        print(line)
    if found:
        print(
            f"{len(found)} string(s) break the copy rule: two sentences, not a semicolon; "
            '"Couldn\'t X", not "X could not be Y" (Droidtop/tracker#945).',
            file=sys.stderr,
        )
        return 1
    print("Copy rule: every string resource passes.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
