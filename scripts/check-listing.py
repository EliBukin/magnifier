#!/usr/bin/env python3
"""Counts the Play store texts against Play's limits.

    python3 scripts/check-listing.py

Reads every "### <Field> (<language>)" heading followed by a ```text block in
docs/play/store-listing.md and docs/play/release-notes-*.md, counts the block's
characters (Unicode characters, line breaks included, as Play Console counts
them) and prints a table. Exits non-zero if a text is over its limit, empty,
has trailing spaces, or if a field is missing for a language.

Limits (Play Console, 2026): app name 30, short description 80, full
description 4000, release notes 500.
"""

import glob
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LIMITS = {"App name": 30, "Short description": 80, "Full description": 4000, "Release notes": 500}
LANGUAGES = ("en-US", "iw-IL")
BLOCK = re.compile(r"^### (?P<field>[A-Za-z ]+) \((?P<lang>[a-zA-Z-]+)\)\s*\n+```text\n(?P<text>.*?)\n```",
                   re.S | re.M)


def texts(path):
    with open(path, encoding="utf-8") as f:
        return [(m["field"], m["lang"], m["text"]) for m in BLOCK.finditer(f.read())]


def main():
    files = [os.path.join(ROOT, "docs/play/store-listing.md")] + sorted(
        glob.glob(os.path.join(ROOT, "docs/play/release-notes-*.md")))
    problems, seen = [], set()
    print(f"{'File':<24} {'Field':<18} {'Lang':<6} {'Chars':>6} {'Limit':>6}")
    for path in files:
        name = os.path.basename(path)
        for field, lang, text in texts(path):
            limit = LIMITS.get(field)
            if limit is None:
                problems.append(f"{name}: unknown field '{field}'")
                continue
            n = len(text)
            seen.add((name, field, lang))
            print(f"{name:<24} {field:<18} {lang:<6} {n:>6} {limit:>6}")
            if n > limit:
                problems.append(f"{name}: {field} ({lang}) is {n} characters, over {limit}")
            if not text.strip():
                problems.append(f"{name}: {field} ({lang}) is empty")
            if any(line != line.rstrip() for line in text.splitlines()):
                problems.append(f"{name}: {field} ({lang}) has trailing spaces")
    wanted = [("store-listing.md", f, l) for f in ("App name", "Short description", "Full description")
              for l in LANGUAGES]
    wanted += [(os.path.basename(p), "Release notes", l) for p in files[1:] for l in LANGUAGES]
    for item in wanted:
        if item not in seen:
            problems.append(f"{item[0]}: {item[1]} ({item[2]}) missing")
    if problems:
        print("FAILED:\n- " + "\n- ".join(problems))
        sys.exit(1)
    print("all texts within Play's limits")


if __name__ == "__main__":
    main()
