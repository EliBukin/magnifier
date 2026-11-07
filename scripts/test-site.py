#!/usr/bin/env python3
"""Checks the website and the privacy policy against each other and against the app.

    python3 scripts/test-site.py

Renders the site into a temporary folder with scripts/render-site.py, then
checks:
- both pages, English and Hebrew (right to left), the contact email and the
  same effective date in both languages;
- nothing loads from outside: no scripts, images or style sheets from
  elsewhere; every local link and #anchor resolves;
- the facts the policy states still hold in the code: the permissions in the
  manifest (camera, storage up to Android 9, no internet), the Pictures folder,
  the 10-minute share copies, the three remembered settings and the backup
  rules that hold only those.
Exits non-zero, listing each failure.
"""

import os
import re
import subprocess
import sys
import tempfile
from html.parser import HTMLParser

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
EMAIL = "dev.mechanics998877@gmail.com"
failures = []


def check(ok, message):
    if not ok:
        failures.append(message)


def read(path):
    with open(os.path.join(ROOT, path), encoding="utf-8") as f:
        return f.read()


class Page(HTMLParser):
    def __init__(self):
        super().__init__()
        self.ids, self.links, self.tags, self.articles = set(), [], [], []

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        self.tags.append((tag, a))
        if "id" in a:
            self.ids.add(a["id"])
        if tag == "a":
            self.links.append(a.get("href", ""))
        if tag == "article":
            self.articles.append(a)


def site_checks(out):
    names = ["index.html", "privacy-policy.html"]
    check(os.path.exists(os.path.join(out, ".nojekyll")), ".nojekyll missing")
    parsed = {}
    for name in names:
        path = os.path.join(out, name)
        check(os.path.exists(path), f"{name} missing")
        if not os.path.exists(path):
            continue
        text = open(path, encoding="utf-8").read()
        p = Page()
        p.feed(text)
        parsed[name] = (p, text)
        check(text.startswith("<!doctype html>"), f"{name}: no doctype")
        check(('html', {'lang': 'en'}) in p.tags, f"{name}: <html lang=en> missing")
        check({"id": "en", "lang": "en"} in p.articles, f"{name}: English article missing")
        check({"id": "he", "lang": "he", "dir": "rtl"} in p.articles, f"{name}: Hebrew RTL article missing")
        check(f'href="mailto:{EMAIL}"' in text, f"{name}: contact email link missing")
        check("<script" not in text.lower(), f"{name}: has a script")
        for tag, a in p.tags:
            for attr in ("src", "srcset"):
                check(attr not in a, f"{name}: <{tag} {attr}> loads something")
            if tag == "link":
                href = a.get("href", "")
                check(a.get("rel") == "canonical" or not re.match(r"^[a-z]+:", href),
                      f"{name}: <link> to outside {href}")
        check("@import" not in text and "url(" not in text, f"{name}: CSS loads something")
    for name, (p, _) in parsed.items():
        for href in p.links:
            if href.startswith(("https://", "mailto:")):
                continue
            check(not href.startswith("http:"), f"{name}: plain http link {href}")
            target, _, anchor = href.partition("#")
            target = target or name
            check(target in parsed, f"{name}: link to missing page {href}")
            if anchor and target in parsed:
                check(anchor in parsed[target][0].ids, f"{name}: link to missing anchor {href}")


def policy_checks():
    en, he = read("docs/privacy-policy.md"), read("docs/privacy-policy.he.md")
    d_en = re.search(r"\*\*Effective date:\*\* (\d{4}-\d{2}-\d{2})", en)
    d_he = re.search(r"\*\*בתוקף מתאריך:\*\* (\d{4}-\d{2}-\d{2})", he)
    check(d_en and d_he and d_en.group(1) == d_he.group(1), "effective dates differ or are missing (EN/HE)")
    for text, lang in ((en, "EN"), (he, "HE")):
        check(EMAIL in text, f"policy {lang}: no contact email")
        check("Pictures/Bukin Magnifier" in text, f"policy {lang}: no Pictures/Bukin Magnifier")
        check("10" in text, f"policy {lang}: no 10-minute share copies")
    for phrase in ("no internet permission", "Save", "Share", "zoom", "light", "colour mode", "Children",
                   "no ads", "Android 7, 8 and 9", "backup"):
        check(phrase.lower() in en.lower(), f"policy EN: no '{phrase}'")
    for phrase in ("אין הרשאת אינטרנט", "שמירה", "שיתוף", "ההגדלה", "הפנס", "מצב הצבעים", "ילדים", "אין פרסומות"):
        check(phrase in he, f"policy HE: no '{phrase}'")


def app_checks():
    manifest = read("app/src/main/AndroidManifest.xml")
    requested = re.findall(r'<uses-permission\s+android:name="([^"]+)"(?![^>]*tools:node="remove")[^>]*>', manifest)
    check(sorted(requested) == ["android.permission.CAMERA", "android.permission.WRITE_EXTERNAL_STORAGE"],
          f"manifest permissions changed: {requested}; update the policy and Data safety")
    check(re.search(r'WRITE_EXTERNAL_STORAGE"\s+android:maxSdkVersion="28"', manifest),
          "WRITE_EXTERNAL_STORAGE is no longer limited to Android 9 (maxSdkVersion 28)")
    check("android.permission.INTERNET" not in manifest, "the manifest names INTERNET")
    store = read("app/src/main/java/bukin/magnifier/PictureStore.kt")
    check('const val FOLDER = "Bukin Magnifier"' in store, "the save folder is no longer Bukin Magnifier")
    check("SHARE_MAX_AGE_MS = 10 * 60 * 1000L" in store, "shared copies no longer go after 10 minutes")
    settings = read("app/src/main/java/bukin/magnifier/MagnifierSettings.kt")
    keys = re.findall(r'const val KEY_\w+ = "(\w+)"', settings)
    check(keys == ["zoom_linear", "light_on", "colour_mode"], f"remembered settings changed: {keys}")
    for rules in ("app/src/main/res/xml/backup_rules.xml", "app/src/main/res/xml/data_extraction_rules.xml"):
        text = read(rules)
        includes = set(re.findall(r'<include domain="(\w+)" path="([^"]+)"', text))
        check(includes == {("sharedpref", "magnifier_settings.xml")}, f"{rules}: backup holds more: {includes}")


def main():
    with tempfile.TemporaryDirectory() as out:
        subprocess.run([sys.executable, os.path.join(ROOT, "scripts/render-site.py"), out], check=True,
                       stdout=subprocess.DEVNULL)
        site_checks(out)
    policy_checks()
    app_checks()
    if failures:
        print("FAILED:\n- " + "\n- ".join(failures))
        sys.exit(1)
    print("site and policy checks: all passed")


if __name__ == "__main__":
    main()
