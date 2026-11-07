#!/usr/bin/env python3
"""Composes the Play store graphics from real screenshots of the app.

    python3 scripts/store-shots.py [--icon A|B|C] [--out docs/play/assets/draft]

Inputs (committed, so anyone can rerun this):
    <out>/raw/en/NN-name.png   screenshots of the app in English (A20, 720 x 1560)
    <out>/raw/he/NN-name.png   the same screens in Hebrew (emulator, 720 x 1600)
Outputs:
    <out>/screenshots/en-US/NN-name.png and iw-IL/NN-name.png: 1080 x 1920, 24-bit
        PNG, no alpha (Play: 2-8 phone screenshots, long side at most twice the
        short one; the phones' own 720 x 1560 is too tall, hence the frame);
    <out>/feature-graphic-1024x500-en-US.png and -iw-IL.png: 24-bit, no alpha.

Each screenshot gets a short caption above it, in its language. SHOTS below
lists the screens in order with their captions; a shot without a raw picture
in one language is skipped, and the script says so.

At step 5a (owner rule: every upload ships with graphics from that exact
build) the raw pictures are retaken from the release build and this script is
run again. The icon on the feature graphic is the launcher icon's art
(scripts/icon_art.py), so it always matches the app.
"""

import argparse
import os
import sys

from PIL import Image, ImageDraw, ImageFilter, ImageFont

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import icon_art as art  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

FONTS = {
    "en": ("/usr/share/fonts/truetype/noto/NotoSans-Bold.ttf", "/usr/share/fonts/truetype/noto/NotoSans-Regular.ttf"),
    "he": ("/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf",
           "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf"),
}
LANG_DIRS = {"en": "en-US", "he": "iw-IL"}

YELLOW = (255, 213, 79)
WHITE = (255, 255, 255)
GREY = (200, 200, 200)
BG_TOP = (18, 18, 18)
BG_BOTTOM = (0, 0, 0)

W, H = 1080, 1920
CAPTION_H = 400
SHOT_MAX_H = 1420
SHOT_MAX_W = 900

# (file name, (headline, line) in English, the same in Hebrew)
SHOTS = [
    ("01-read", ("Read small print", "Your phone's rear camera, enlarged"),
     ("קוראים אותיות קטנות", "המצלמה האחורית של הטלפון, מוגדלת")),
    ("02-light", ("Light when it's dark", "The LED torch, on with one tap"),
     ("אור כשחשוך", "פנס ה־LED נדלק בהקשה אחת")),
    ("03-freeze", ("Freeze and zoom in", "A sharp photo to look around in"),
     ("מקפיאים ומגדילים", "תמונה חדה להגדיל ולזוז בה")),
    ("04-colours", ("Colours for easier reading", "High contrast, inverted, yellow on black"),
     ("צבעים לקריאה נוחה", "ניגודיות גבוהה, הפוכים, צהוב על שחור")),
    ("05-ring", ("Turn the lens ring", "Zoom, brightness or focus"),
     ("מסובבים את טבעת העדשה", "הגדלה, בהירות או מיקוד")),
    ("06-private", ("No internet, no ads", "Nothing leaves your phone"),
     ("בלי אינטרנט, בלי פרסומות", "שום דבר לא יוצא מהטלפון")),
]

FEATURE = {
    "en": ("Bukin Magnifier", "A magnifying glass with the light", "Free · No ads · No internet"),
    "he": ("Bukin Magnifier", "זכוכית מגדלת עם פנס", "בחינם · בלי פרסומות · בלי אינטרנט"),
}


def font(lang, bold, size):
    return ImageFont.truetype(FONTS[lang][0 if bold else 1], size)


def text_args(lang):
    return {"direction": "rtl", "language": "he"} if lang == "he" else {"language": "en"}


def gradient(w, h):
    img = Image.new("RGB", (w, h))
    d = ImageDraw.Draw(img)
    for y in range(h):
        t = y / max(1, h - 1)
        d.line([(0, y), (w, y)], fill=tuple(int(a + (b - a) * t) for a, b in zip(BG_TOP, BG_BOTTOM)))
    return img


def fit_text(draw, text, lang, bold, size, max_w):
    """The biggest font, from `size` down, that fits `text` on one line in `max_w`."""
    while size > 20:
        f = font(lang, bold, size)
        if draw.textlength(text, font=f, **text_args(lang)) <= max_w:
            return f
        size -= 2
    return font(lang, bold, size)


def centred(draw, y, text, lang, f, fill):
    w = draw.textlength(text, font=f, **text_args(lang))
    draw.text(((W - w) / 2, y), text, font=f, fill=fill, **text_args(lang))


def rounded(img, radius):
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, img.width - 1, img.height - 1), radius=radius, fill=255)
    return mask


def compose(raw_path, headline, line, lang):
    canvas = gradient(W, H)
    d = ImageDraw.Draw(canvas)
    f1 = fit_text(d, headline, lang, True, 84, W - 120)
    f2 = fit_text(d, line, lang, False, 48, W - 120)
    centred(d, 120, headline, lang, f1, YELLOW)
    centred(d, 250, line, lang, f2, WHITE)

    shot = Image.open(raw_path).convert("RGB")
    scale = min(SHOT_MAX_H / shot.height, SHOT_MAX_W / shot.width)
    shot = shot.resize((round(shot.width * scale), round(shot.height * scale)), Image.LANCZOS)
    x = (W - shot.width) // 2
    y = CAPTION_H + (H - CAPTION_H - shot.height) // 2
    # A soft shadow and a thin rim, like a phone's edge, so the black app sits off the background.
    shadow = Image.new("L", (shot.width + 80, shot.height + 80), 0)
    ImageDraw.Draw(shadow).rounded_rectangle((40, 40, shot.width + 40, shot.height + 40), radius=44, fill=170)
    shadow = shadow.filter(ImageFilter.GaussianBlur(18))
    canvas.paste(Image.new("RGB", shadow.size, (60, 60, 60)), (x - 40, y - 40), shadow)
    rim = Image.new("RGB", (shot.width + 12, shot.height + 12), (70, 70, 70))
    canvas.paste(rim, (x - 6, y - 6), rounded(rim, 46))
    canvas.paste(shot, (x, y), rounded(shot, 40))
    return canvas


def feature_graphic(lang, icon_key):
    fw, fh = 1024, 500
    canvas = gradient(fw, fh)
    d = ImageDraw.Draw(canvas)
    design = art.DESIGNS[icon_key]()
    icon = art.surface_to_pil(art.render(design, 300, mask="rounded"))
    rtl = lang == "he"
    icon_x = fw - 90 - 300 if rtl else 90
    canvas.paste(icon, (icon_x, 100), icon)
    title, tagline, facts = FEATURE[lang]
    left, right = (70, fw - 90 - 300 - 50) if rtl else (90 + 300 + 50, fw - 60)
    width = right - left
    f1 = fit_text(d, title, "en", True, 76, width)
    f2 = fit_text(d, tagline, lang, True, 46, width)
    f3 = fit_text(d, facts, lang, False, 34, width)

    def put(y, text, f, fill, text_lang):
        w = d.textlength(text, font=f, **text_args(text_lang))
        d.text((right - w if rtl else left, y), text, font=f, fill=fill, **text_args(text_lang))

    put(130, title, f1, WHITE, "en")
    put(245, tagline, f2, YELLOW, lang)
    put(325, facts, f3, GREY, lang)
    return canvas


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--icon", default="B", choices=sorted(art.DESIGNS))
    ap.add_argument("--out", default=os.path.join(ROOT, "docs/play/assets/draft"))
    args = ap.parse_args()
    made, missing = [], []
    for lang, play_lang in LANG_DIRS.items():
        out_dir = os.path.join(args.out, "screenshots", play_lang)
        os.makedirs(out_dir, exist_ok=True)
        for name, en, he in SHOTS:
            raw = os.path.join(args.out, "raw", lang, name + ".png")
            if not os.path.exists(raw):
                missing.append(f"{lang}/{name}")
                continue
            headline, line = en if lang == "en" else he
            img = compose(raw, headline, line, lang)
            path = os.path.join(out_dir, name + ".png")
            img.save(path, optimize=True)
            made.append(os.path.relpath(path, args.out))
        fg = feature_graphic(lang, args.icon)
        path = os.path.join(args.out, f"feature-graphic-1024x500-{play_lang}.png")
        fg.save(path, optimize=True)
        made.append(os.path.relpath(path, args.out))
    for m in made:
        img = Image.open(os.path.join(args.out, m))
        assert img.mode == "RGB", (m, img.mode)  # no alpha
        w, h = img.size
        if "feature-graphic" in m:
            assert (w, h) == (1024, 500), (m, img.size)
        else:  # phone screenshots
            assert max(w, h) <= 2 * min(w, h) and 320 <= min(w, h) and max(w, h) <= 3840, (m, img.size)
        print(f"{m}: {w}x{h} {img.mode}")
    if missing:
        print("no raw picture (skipped):", ", ".join(missing))


if __name__ == "__main__":
    main()
