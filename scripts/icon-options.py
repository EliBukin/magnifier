#!/usr/bin/env python3
"""Draws the three launcher-icon drafts side by side, for the owner to choose one.

    python3 scripts/icon-options.py <out_dir>

Writes <out_dir>/icon-options.png (each draft large, in the common launcher
masks, as a themed icon, and at real small sizes on a launcher-like home
screen) and, per draft, <out_dir>/icon-options/<key>/ with the 512 x 512 Play
preview and the three VectorDrawable layers. The art itself is in icon_art.py.
"""

import os
import sys

import cairo
from PIL import Image, ImageDraw, ImageFont

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import icon_art as art  # noqa: E402

FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
FONT_BOLD = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"

THEMED_LIGHT = ("#DDE3F3", "#27355A")  # Android 13 themed icon, light wallpaper
THEMED_DARK = ("#232A36", "#C9D6F2")  # and dark


def pil(design, size, **kw):
    return art.surface_to_pil(art.render(design, size, **kw))


def placeholder(size, colour, shape):
    """A neutral stand-in for another app's icon on the mock home screen."""
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.ellipse((0, 0, size - 1, size - 1), fill=colour)
    m = size * 0.3
    if shape == "square":
        d.rounded_rectangle((m, m, size - m, size - m), radius=size * 0.06, fill="white")
    elif shape == "dot":
        d.ellipse((m, m, size - m, size - m), fill="white")
    else:
        d.polygon([(size / 2, m), (size - m, size - m), (m, size - m)], fill="white")
    return img


def home_screen(design, width, icon, wallpaper, label_colour, themed=None):
    """A row of four apps, ours third, at a real launcher size, with labels."""
    h = icon + 40
    img = Image.new("RGB", (width, h), wallpaper)
    font = ImageFont.truetype(FONT, 12)
    others = [("#5B8DEF", "square", "Notes"), ("#3FAF7A", "dot", "Phone"), (None, None, "Magnifier"),
              ("#E0675A", "tri", "Camera")]
    step = width / 4
    for i, (colour, shape, label) in enumerate(others):
        x = int(step * i + (step - icon) / 2)
        if colour:
            tile = placeholder(icon, colour, shape)
        else:
            tile = pil(design, icon, mask="circle", mono=themed)
        img.paste(tile, (x, 8), tile)
        d = ImageDraw.Draw(img)
        tw = d.textlength(label, font=font)
        d.text((step * i + (step - tw) / 2, 8 + icon + 6), label, font=font, fill=label_colour)
    return img


def column(design, width):
    col = Image.new("RGB", (width, 1180), "#2A2A2A")
    d = ImageDraw.Draw(col)
    title = ImageFont.truetype(FONT_BOLD, 30)
    small = ImageFont.truetype(FONT, 15)
    d.text((24, 18), f"{design.key}. {design.name}", font=title, fill="white")
    d.text((24, 58), design.idea, font=small, fill="#CFCFCF")

    big = pil(design, 360, mask="rounded")  # roughly how Play shows the 512 icon
    col.paste(big, ((width - 360) // 2, 92), big)

    y = 470
    d.text((24, y), "Launcher masks (circle, squircle, rounded square), 96 px", font=small, fill="#CFCFCF")
    x = 24
    for mask in ("circle", "squircle", "rounded"):
        t = pil(design, 96, mask=mask)
        col.paste(t, (x, y + 26), t)
        x += 120
    y += 140
    d.text((24, y), "Themed icon (Android 13+, monochrome layer), light and dark", font=small, fill="#CFCFCF")
    x = 24
    for mono in (THEMED_LIGHT, THEMED_DARK):
        t = pil(design, 96, mask="circle", mono=mono)
        col.paste(t, (x, y + 26), t)
        x += 120
    y += 140
    d.text((24, y), "Real sizes: 72, 48, 36 and 24 px", font=small, fill="#CFCFCF")
    x = 24
    for s in (72, 48, 36, 24):
        t = pil(design, s, mask="circle")
        col.paste(t, (x, y + 26 + (72 - s) // 2), t)
        x += s + 24
    y += 120
    d.text((24, y), "On a home screen (48 px icons): dark, light, themed", font=small, fill="#CFCFCF")
    y += 24
    for wall, label, themed in (("#101418", "#FFFFFF", None), ("#C9D3DF", "#111111", None),
                                ("#C9D3DF", "#111111", THEMED_LIGHT)):
        strip = home_screen(design, width - 48, 48, wall, label, themed)
        col.paste(strip, (24, y))
        y += strip.height + 6
    return col


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "."
    os.makedirs(out, exist_ok=True)
    width = 520
    columns = []
    for key, make in art.DESIGNS.items():
        design = make()
        fg, mono = art.safe_zone_report(design)
        assert fg <= art.SAFE_RADIUS + 1e-6 and mono <= art.SAFE_RADIUS + 1e-6, (key, fg, mono)
        print(f"{key}: foreground reaches {fg:.1f}, monochrome {mono:.1f} (safe zone {art.SAFE_RADIUS})")
        folder = os.path.join(out, "icon-options", key)
        os.makedirs(folder, exist_ok=True)
        pil(design, 512).convert("RGB").save(os.path.join(folder, "preview-512.png"))
        with open(os.path.join(folder, "ic_launcher_background.xml"), "w") as f:
            f.write(art.vector_xml(design.background, f"Launcher icon {key} ({design.name}): background.",
                                   bg_colour=design.bg_colour))
        with open(os.path.join(folder, "ic_launcher_foreground.xml"), "w") as f:
            f.write(art.vector_xml(design.foreground, f"Launcher icon {key} ({design.name}): foreground."))
        with open(os.path.join(folder, "ic_launcher_monochrome.xml"), "w") as f:
            f.write(art.vector_xml(design.monochrome, f"Launcher icon {key} ({design.name}): monochrome "
                                   "(themed icons use only its shape).", mono=True))
        columns.append(column(design, width))
    sheet = Image.new("RGB", (width * len(columns) + 16 * (len(columns) + 1), columns[0].height + 32), "#1A1A1A")
    for i, c in enumerate(columns):
        sheet.paste(c, (16 + i * (width + 16), 16))
    path = os.path.join(out, "icon-options.png")
    sheet.save(path)
    print(path)


if __name__ == "__main__":
    main()
