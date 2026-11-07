"""Bukin Magnifier launcher icon art: one description, two outputs.

Each icon design is a list of simple shapes on Android's 108 x 108 adaptive-icon
canvas (the launcher shows the middle 72 x 72; the safe zone every mask keeps is
the circle of radius 33 around the centre). From that one list this module
writes the Android VectorDrawable XML (background, foreground, monochrome) and
renders PNGs with cairo, so the pictures always match the XML.

Used by scripts/icon-options.py (the three drafts) and scripts/make-icon.py
(the chosen icon into app/src/main/res and docs/play/assets).
"""

import math
from dataclasses import dataclass, field

import cairo

CANVAS = 108.0
CENTRE = 54.0
SAFE_RADIUS = 33.0
VISIBLE = 72.0  # the part of the canvas a launcher shows, before its mask

YELLOW = "#FFD54F"  # the app's accent (res/values/colors.xml)
BLACK = "#000000"
WHITE = "#FFFFFF"


# ---------------------------------------------------------------- shapes


@dataclass
class Circle:
    cx: float
    cy: float
    r: float
    fill: str | None = None
    stroke: str | None = None
    width: float = 0.0


@dataclass
class Line:
    x1: float
    y1: float
    x2: float
    y2: float
    stroke: str
    width: float
    cap: str = "round"  # round | butt


@dataclass
class Arc:
    """An open arc, angles in degrees, clockwise on screen (y down), a0 < a1."""
    cx: float
    cy: float
    r: float
    a0: float
    a1: float
    stroke: str
    width: float
    cap: str = "round"


@dataclass
class Poly:
    points: list
    fill: str


@dataclass
class Glow:
    """A disc filled with a radial gradient: `stops` are (offset 0..1 of r, colour), centre outwards.
    Drawn in XML as an aapt:attr gradient (VectorDrawable supports gradients from API 24)."""
    cx: float
    cy: float
    r: float
    stops: list


@dataclass
class Compound:
    """Several outlines (Circles and Polys, their own colours ignored) filled as one path with the
    even-odd rule, so an outline inside another cuts a hole (android:fillType="evenOdd", API 24+)."""
    parts: list
    fill: str


@dataclass
class Design:
    key: str
    name: str
    idea: str
    background: list  # shapes; may be empty when bg_colour covers it
    foreground: list
    monochrome: list
    bg_colour: str = BLACK
    extents: list = field(default_factory=list)  # filled in by check_safe_zone


def _hex(colour):
    c = colour.lstrip("#")
    if len(c) == 6:
        c = "FF" + c
    a, r, g, b = (int(c[i:i + 2], 16) / 255 for i in range(0, 8, 2))
    return r, g, b, a


def _android_colour(colour):
    c = colour.lstrip("#").upper()
    return "#" + ("FF" + c if len(c) == 6 else c)


def _f(v):
    s = f"{v:.2f}".rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s


# ---------------------------------------------------------------- XML


def _path_data(shape):
    if isinstance(shape, (Circle, Glow)):
        x0, x1, y, r = shape.cx - shape.r, shape.cx + shape.r, shape.cy, shape.r
        return f"M{_f(x0)},{_f(y)}A{_f(r)},{_f(r)} 0,1 1,{_f(x1)},{_f(y)}A{_f(r)},{_f(r)} 0,1 1,{_f(x0)},{_f(y)}Z"
    if isinstance(shape, Line):
        return f"M{_f(shape.x1)},{_f(shape.y1)}L{_f(shape.x2)},{_f(shape.y2)}"
    if isinstance(shape, Arc):
        a0, a1 = math.radians(shape.a0), math.radians(shape.a1)
        sx, sy = shape.cx + shape.r * math.cos(a0), shape.cy + shape.r * math.sin(a0)
        ex, ey = shape.cx + shape.r * math.cos(a1), shape.cy + shape.r * math.sin(a1)
        large = 1 if (shape.a1 - shape.a0) > 180 else 0
        return f"M{_f(sx)},{_f(sy)}A{_f(shape.r)},{_f(shape.r)} 0,{large} 1,{_f(ex)},{_f(ey)}"
    if isinstance(shape, Poly):
        pts = shape.points
        return "M" + "L".join(f"{_f(x)},{_f(y)}" for x, y in pts) + "Z"
    if isinstance(shape, Compound):
        return "".join(_path_data(p) for p in shape.parts)
    raise TypeError(shape)


def _path_xml(shape, mono=False):
    if isinstance(shape, Glow):
        items = "\n".join(
            f'                <item android:offset="{_f(o)}" android:color="{_android_colour(WHITE if mono else c)}" />'
            for o, c in shape.stops)
        return (f'    <path android:pathData="{_path_data(shape)}">\n'
                '        <aapt:attr name="android:fillColor">\n'
                f'            <gradient android:type="radial" android:centerX="{_f(shape.cx)}" '
                f'android:centerY="{_f(shape.cy)}" android:gradientRadius="{_f(shape.r)}">\n'
                f"{items}\n"
                "            </gradient>\n"
                "        </aapt:attr>\n"
                "    </path>")
    colour = (lambda c: WHITE) if mono else (lambda c: c)
    attrs = [f'android:pathData="{_path_data(shape)}"']
    fill = getattr(shape, "fill", None)
    stroke = getattr(shape, "stroke", None)
    if isinstance(shape, Compound):
        attrs.append('android:fillType="evenOdd"')
    if fill:
        attrs.append(f'android:fillColor="{_android_colour(colour(fill))}"')
    else:
        attrs.append('android:fillColor="#00000000"')
    if stroke:
        attrs.append(f'android:strokeColor="{_android_colour(colour(stroke))}"')
        attrs.append(f'android:strokeWidth="{_f(shape.width)}"')
        cap = getattr(shape, "cap", None)
        if cap:
            attrs.append(f'android:strokeLineCap="{cap}"')
    return "    <path\n        " + "\n        ".join(attrs) + " />"


def vector_xml(shapes, comment, mono=False, bg_colour=None):
    body = []
    if bg_colour is not None:
        body.append(_path_xml(Poly([(0, 0), (CANVAS, 0), (CANVAS, CANVAS), (0, CANVAS)], bg_colour)))
    body += [_path_xml(s, mono) for s in shapes]
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        f"<!-- {comment}\n     Generated by scripts/make-icon.py from scripts/icon_art.py: edit those, not this file. -->\n"
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        + ('    xmlns:aapt="http://schemas.android.com/aapt"\n' if any(isinstance(x, Glow) for x in shapes) else "")
        + '    android:width="108dp"\n    android:height="108dp"\n'
        '    android:viewportWidth="108"\n    android:viewportHeight="108">\n'
        + "\n".join(body) + "\n</vector>\n"
    )


# ---------------------------------------------------------------- rendering


def _draw_shape(ctx, shape, colour_override=None):
    def src(c):
        ctx.set_source_rgba(*_hex(colour_override or c))

    if isinstance(shape, Circle):
        ctx.new_path()
        ctx.arc(shape.cx, shape.cy, shape.r, 0, 2 * math.pi)
        if shape.fill:
            src(shape.fill)
            ctx.fill_preserve()
        if shape.stroke:
            src(shape.stroke)
            ctx.set_line_width(shape.width)
            ctx.stroke_preserve()
        ctx.new_path()
    elif isinstance(shape, (Line, Arc)):
        ctx.new_path()
        if isinstance(shape, Line):
            ctx.move_to(shape.x1, shape.y1)
            ctx.line_to(shape.x2, shape.y2)
        else:
            ctx.arc(shape.cx, shape.cy, shape.r, math.radians(shape.a0), math.radians(shape.a1))
        src(shape.stroke)
        ctx.set_line_width(shape.width)
        ctx.set_line_cap(cairo.LINE_CAP_ROUND if shape.cap == "round" else cairo.LINE_CAP_BUTT)
        ctx.stroke()
    elif isinstance(shape, Poly):
        ctx.new_path()
        ctx.move_to(*shape.points[0])
        for p in shape.points[1:]:
            ctx.line_to(*p)
        ctx.close_path()
        src(shape.fill)
        ctx.fill()
    elif isinstance(shape, Glow):
        grad = cairo.RadialGradient(shape.cx, shape.cy, 0, shape.cx, shape.cy, shape.r)
        for o, c in shape.stops:
            grad.add_color_stop_rgba(o, *_hex(colour_override or c))
        ctx.new_path()
        ctx.arc(shape.cx, shape.cy, shape.r, 0, 2 * math.pi)
        ctx.set_source(grad)
        ctx.fill()
    elif isinstance(shape, Compound):
        ctx.new_path()
        for part in shape.parts:
            if isinstance(part, Circle):
                ctx.new_sub_path()
                ctx.arc(part.cx, part.cy, part.r, 0, 2 * math.pi)
                ctx.close_path()
            else:
                ctx.move_to(*part.points[0])
                for p in part.points[1:]:
                    ctx.line_to(*p)
                ctx.close_path()
        ctx.set_fill_rule(cairo.FILL_RULE_EVEN_ODD)
        src(shape.fill)
        ctx.fill()
        ctx.set_fill_rule(cairo.FILL_RULE_WINDING)
    else:
        raise TypeError(shape)


def _mask_path(ctx, size, mask):
    ctx.new_path()
    if mask == "circle":
        ctx.arc(size / 2, size / 2, size / 2, 0, 2 * math.pi)
    elif mask == "squircle":
        # A superellipse (n = 4), close to Samsung's and Pixel's "squircle" masks.
        n, a = 4.0, size / 2
        for i in range(361):
            t = math.radians(i)
            c, s = math.cos(t), math.sin(t)
            x = a + a * math.copysign(abs(c) ** (2 / n), c)
            y = a + a * math.copysign(abs(s) ** (2 / n), s)
            (ctx.move_to if i == 0 else ctx.line_to)(x, y)
        ctx.close_path()
    elif mask == "rounded":
        r = size * 0.18
        ctx.move_to(r, 0)
        ctx.arc(size - r, r, r, -math.pi / 2, 0)
        ctx.arc(size - r, size - r, r, 0, math.pi / 2)
        ctx.arc(r, size - r, r, math.pi / 2, math.pi)
        ctx.arc(r, r, r, math.pi, 3 * math.pi / 2)
        ctx.close_path()
    else:  # square
        ctx.rectangle(0, 0, size, size)


def render(design, size, mask="square", mono=None, crop="visible"):
    """An ARGB cairo surface of the icon, `size` px square.

    crop: "visible" maps the launcher's visible 72 x 72 to the surface (what a
    launcher or Play shows); "full" maps the whole 108 x 108 canvas.
    mono: None for the colour icon, or (background, glyph) colours for a themed
    icon drawn from the monochrome layer.
    """
    surface = cairo.ImageSurface(cairo.FORMAT_ARGB32, size, size)
    ctx = cairo.Context(surface)
    ctx.set_antialias(cairo.ANTIALIAS_BEST)
    _mask_path(ctx, size, mask)
    ctx.clip()
    span = VISIBLE if crop == "visible" else CANVAS
    scale = size / span
    ctx.scale(scale, scale)
    ctx.translate(-(CANVAS - span) / 2, -(CANVAS - span) / 2)
    if mono:
        ctx.set_source_rgba(*_hex(mono[0]))
        ctx.paint()
        for s in design.monochrome:
            _draw_shape(ctx, s, mono[1])
    else:
        ctx.set_source_rgba(*_hex(design.bg_colour))
        ctx.paint()
        for s in design.background + design.foreground:
            _draw_shape(ctx, s)
    surface.flush()
    return surface


def surface_to_pil(surface):
    from PIL import Image

    w, h = surface.get_width(), surface.get_height()
    # cairo stores premultiplied BGRA; the "BGRa" raw mode un-premultiplies it for PIL.
    return Image.frombuffer("RGBA", (w, h), bytes(surface.get_data()), "raw", "BGRa", surface.get_stride(), 1).copy()


# ---------------------------------------------------------------- checks


def _shape_reach(shape):
    """The farthest distance any painted point of `shape` reaches from the centre."""
    def d(x, y):
        return math.hypot(x - CENTRE, y - CENTRE)

    if isinstance(shape, Glow):
        return d(shape.cx, shape.cy) + shape.r
    if isinstance(shape, Circle):
        return d(shape.cx, shape.cy) + shape.r + (shape.width / 2 if shape.stroke else 0)
    if isinstance(shape, Line):
        return max(d(shape.x1, shape.y1), d(shape.x2, shape.y2)) + shape.width / 2
    if isinstance(shape, Arc):
        best = 0.0
        for i in range(101):
            a = math.radians(shape.a0 + (shape.a1 - shape.a0) * i / 100)
            best = max(best, d(shape.cx + shape.r * math.cos(a), shape.cy + shape.r * math.sin(a)))
        return best + shape.width / 2
    if isinstance(shape, Poly):
        return max(d(x, y) for x, y in shape.points)
    if isinstance(shape, Compound):
        return max(d(p.cx, p.cy) + p.r if isinstance(p, Circle) else _shape_reach(p) for p in shape.parts)
    raise TypeError(shape)


def safe_zone_report(design):
    """(farthest reach of the foreground, of the monochrome layer); both must be <= SAFE_RADIUS."""
    return (max(_shape_reach(s) for s in design.foreground),
            max(_shape_reach(s) for s in design.monochrome))


# ---------------------------------------------------------------- the designs


def _capsule(x1, y1, x2, y2, w, steps=10):
    """An outline of a thick line from (x1, y1) to (x2, y2): flat at the start, round at the end."""
    ang = math.atan2(y2 - y1, x2 - x1)
    nx, ny = -math.sin(ang) * w / 2, math.cos(ang) * w / 2
    pts = [(x1 - nx, y1 - ny), (x1 + nx, y1 + ny)]
    for i in range(steps + 1):
        a = ang + math.pi / 2 - math.pi * i / steps
        pts.append((x2 + math.cos(a) * w / 2, y2 + math.sin(a) * w / 2))
    return pts


def _beam_in_safe_zone(top, half, slope, radius):
    """The beam's outline cut off by a circle round the centre: from the top edge (half-width
    `half`, growing by `slope` per unit down) to where each side leaves the circle, then round the
    bottom along the circle."""
    def side_exit(sign):
        lo, hi = top, CANVAS
        for _ in range(60):
            mid = (lo + hi) / 2
            x = CENTRE + sign * (half + slope * (mid - top))
            lo, hi = (mid, hi) if math.hypot(x - CENTRE, mid - CENTRE) < radius else (lo, mid)
        return CENTRE + sign * (half + slope * (lo - top)), lo

    lx, ly = side_exit(-1)
    rx, ry = side_exit(1)
    a_right = math.atan2(ry - CENTRE, rx - CENTRE)
    a_left = math.atan2(ly - CENTRE, lx - CENTRE)
    pts = [(CENTRE - half, top), (CENTRE + half, top), (rx, ry)]
    for i in range(1, 24):
        a = a_right + (a_left - a_right) * i / 24
        pts.append((CENTRE + radius * math.cos(a), CENTRE + radius * math.sin(a)))
    pts.append((lx, ly))
    return pts


def _glass(cx, cy, r, ring, colour, handle_to, handle_w, lens_fill=None):
    """A magnifying glass: a ring (stroke `ring`) round (cx, cy) and a handle out to `handle_to`
    along the 45-degree diagonal (down and right)."""
    outer = r + ring / 2
    hx = cx + outer * math.cos(math.radians(45)) - 0.5
    shapes = []
    if lens_fill:
        shapes.append(Circle(cx, cy, r, fill=lens_fill))
    shapes.append(Line(hx, hx - cx + cy, handle_to, handle_to - cx + cy, colour, handle_w))
    shapes.append(Circle(cx, cy, r, stroke=colour, width=ring))
    return shapes


def _letter_a(cx, top, bottom, half_width, leg, bar_y, bar_h, colour):
    """A bold sans-serif capital A as three filled shapes: two legs and the crossbar."""
    left = Poly([(cx - half_width, bottom), (cx - half_width + leg, bottom),
                 (cx + leg / 2, top), (cx - leg / 2, top)], colour)
    right = Poly([(cx + half_width, bottom), (cx + half_width - leg, bottom),
                  (cx - leg / 2, top), (cx + leg / 2, top)], colour)
    def span(y):
        # half-width of the outer silhouette at height y (legs' outer edges)
        t = (y - top) / (bottom - top)
        return leg / 2 + (half_width - leg / 2) * t

    y0, y1 = bar_y, bar_y + bar_h
    bar = Poly([(cx - span(y0) + 0.5, y0), (cx + span(y0) - 0.5, y0),
                (cx + span(y1) - 0.5, y1), (cx - span(y1) + 0.5, y1)], colour)
    return [left, right, bar]


def design_a():
    """Bright lens: the lens itself glows yellow, with a white highlight: a lit magnifier."""
    cx = cy = 49.0
    r, ring = 14.0, 6.0
    fg = _glass(cx, cy, r, ring, WHITE, 72.5, 9.0, lens_fill=YELLOW)
    fg.append(Arc(cx, cy, 8.0, 200, 255, WHITE, 3.0))  # the glint
    # Short rays above and left of the lens: the light.
    rays = []
    for ang in (-135, -100, -170):
        a = math.radians(ang)
        r0, r1 = r + ring / 2 + 3.0, r + ring / 2 + 7.0
        rays.append(Line(cx + r0 * math.cos(a), cy + r0 * math.sin(a),
                         cx + r1 * math.cos(a), cy + r1 * math.sin(a), YELLOW, 3.4))
    fg = rays + fg
    # Themed icons: the outline glass (ring, glint, handle) and the rays, all one colour.
    mono = [Circle(cx, cy, r, stroke=WHITE, width=ring), Arc(cx, cy, 8.0, 200, 255, WHITE, 3.0),
            *[Line(l.x1, l.y1, l.x2, l.y2, WHITE, l.width) for l in rays],
            Line(*_handle(cx, cy, r, ring, 72.5), WHITE, 9.0)]
    return Design("A", "Bright lens", "a lit lens: the glass glows yellow, with three short rays of light",
                  [], fg, mono, BLACK)


def _handle(cx, cy, r, ring, to):
    outer = r + ring / 2
    hx = cx + outer * math.cos(math.radians(45)) - 0.5
    return hx, hx - cx + cy, to, to - cx + cy


def design_b():
    """Reading lens: yellow tile, black glass, a bold A in the lens: made for reading."""
    cx = cy = 50.0
    r, ring = 17.5, 6.0
    fg = _glass(cx, cy, r, ring, BLACK, 74.0, 9.0)
    fg += _letter_a(cx, cy - 10.5, cy + 10.0, 9.5, 5.0, cy + 2.0, 3.6, BLACK)
    mono = list(fg)  # black on yellow; the themed layer only uses the alpha
    return Design("B", "Reading lens", "yellow tile, black glass with a bold A: a reading magnifier",
                  [], fg, mono, YELLOW)


def design_c():
    """Torch beam: the LED at the top shines a yellow beam down through a black glass."""
    led_y = 28.0
    # The beam: from the LED, widening to the bottom edge (wider than the mask: launchers clip it).
    beam = Poly([(54 - 8.0, led_y + 1), (54 + 8.0, led_y + 1), (54 + 36, 108), (54 - 36, 108)], YELLOW)
    cx, cy = 52.0, 58.0
    r, ring = 13.5, 5.5
    # The LED: a short white bar, a lamp rather than a dot (a dot over a cone reads as a person).
    led = Line(54 - 8.0, led_y, 54 + 8.0, led_y, WHITE, 6.0)
    fg = [led]
    fg += _glass(cx, cy, r, ring, BLACK, 70.0, 7.5, lens_fill="#FFF3C4")
    # Themed icons: one colour, so the glass is cut out of the beam (even-odd): the LED, then the
    # beam with the ring and the handle as holes and the lens filled again.
    hx1, hy1, hx2, hy2 = _handle(cx, cy, r, ring, 70.0)
    mono = [led,
            Compound([Poly(_beam_in_safe_zone(led_y + 6.0, 8.5, (36 - 8.0) / (108 - led_y - 1), 32.0), WHITE),
                      Circle(cx, cy, r + ring / 2, fill=WHITE), Circle(cx, cy, r - ring / 2, fill=WHITE),
                      Poly(_capsule(hx1 + 1.0, hy1 + 1.0, hx2, hy2, 7.5), WHITE)], WHITE)]
    return Design("C", "Torch beam", "the LED shines a yellow beam down through a black glass",
                  [beam], fg, mono, BLACK)


# ---------------------------------------------------------------- second round (owner: "4 new examples")

TRACK = "#8A8A8A"  # the lens ring's grey track in the app (LensRingView)
PAPER = "#FFFFFF"


def _turn(points, cx, cy, degrees):
    """Points (relative to an origin) turned by `degrees` (clockwise on screen) and moved to (cx, cy)."""
    a = math.radians(degrees)
    c, s = math.cos(a), math.sin(a)
    return [(cx + x * c - y * s, cy + x * s + y * c) for x, y in points]


def design_d():
    """Lens-ring dial: the app's own dial as the glass: a grey track, a yellow fill, a white thumb."""
    cx = cy = 50.0
    r, ring = 18.0, 7.0
    hx1, hy1, hx2, hy2 = _handle(cx, cy, r, ring, 73.5)
    fg = [Line(hx1, hy1, hx2, hy2, YELLOW, 9.0),
          Circle(cx, cy, r, stroke=TRACK, width=ring),
          Arc(cx, cy, r, 135, 290, YELLOW, ring, cap="butt")]
    # Ticks inside the ring, across its top, like the dial's scale.
    for ang in range(180, 361, 45):
        a = math.radians(ang)
        fg.append(Line(cx + 7.5 * math.cos(a), cy + 7.5 * math.sin(a),
                       cx + 11.5 * math.cos(a), cy + 11.5 * math.sin(a), WHITE, 2.6))
    tx, ty = cx + r * math.cos(math.radians(290)), cy + r * math.sin(math.radians(290))
    fg.append(Circle(tx, ty, 5.6, fill=WHITE, stroke=BLACK, width=1.6))
    mono = [Line(hx1, hy1, hx2, hy2, WHITE, 9.0), Circle(cx, cy, r, stroke=WHITE, width=ring),
            Circle(tx, ty, 6.4, fill=WHITE)]
    mono += [Line(l.x1, l.y1, l.x2, l.y2, WHITE, l.width) for l in fg if isinstance(l, Line) and l.width < 3.0]
    return Design("D", "Lens-ring dial", "the app's own dial as the glass: grey track, yellow fill, white thumb",
                  [], fg, mono, BLACK)


def design_e():
    """Magnified page: a yellow page of small print; under the glass the lines are big and bold."""
    # Small print across the whole tile (background layer: it may run to the edges).
    rows = []
    lengths = [(10, 98), (10, 80), (10, 94), (10, 60), (10, 98), (10, 88), (10, 70), (10, 96), (10, 84), (10, 52)]
    for i, (x0, x1) in enumerate(lengths):
        y = 14 + i * 9
        rows.append(Line(x0, y, x1, y, "#7A6420", 2.4))
    cx, cy = 51.0, 50.0
    r, ring = 17.5, 5.5
    hx1, hy1, hx2, hy2 = _handle(cx, cy, r, ring, 74.5)
    fg = [Line(hx1, hy1, hx2, hy2, BLACK, 9.0),
          Circle(cx, cy, r + ring / 2, fill=PAPER),  # the glass covers the small print
          Circle(cx, cy, r, stroke=BLACK, width=ring)]
    big = []
    for dy, shorten in ((-8.0, 0.0), (0.0, 0.0), (8.0, 6.0)):
        half = math.sqrt((r - ring / 2) ** 2 - dy ** 2) - 3.5
        big.append(Line(cx - half, cy + dy, cx + half - shorten, cy + dy, BLACK, 4.6))
    fg += big
    mono = [Line(hx1, hy1, hx2, hy2, WHITE, 9.0), Circle(cx, cy, r, stroke=WHITE, width=ring),
            *[Line(b.x1, b.y1, b.x2, b.y2, WHITE, b.width) for b in big]]
    return Design("E", "Magnified page", "yellow page of small print; under the glass the lines are big and bold",
                  rows, fg, mono, YELLOW)


def design_f():
    """Bulb lens: a light bulb whose round glass is the lens, its screw base the handle."""
    # Drawn pointing down in its own coordinates, then turned so the base points down and right.
    ox, oy, turn = 49.0, 49.0, -45.0
    r = 16.0
    neck = [(-8.6, 12.0), (8.6, 12.0), (5.6, 21.0), (-5.6, 21.0)]
    stripes = [[(-6.4, y), (6.4, y), (6.4, y + 3.0), (-6.4, y + 3.0)] for y in (22.2, 26.2, 30.2)]
    tip = [(-3.6, 34.2), (3.6, 34.2), (2.0, 37.4), (-2.0, 37.4)]
    fg = [Poly(_turn(neck, ox, oy, turn), "#E6E6E6")]
    fg += [Poly(_turn(sp, ox, oy, turn), "#BDBDBD") for sp in stripes]
    fg.append(Poly(_turn(tip, ox, oy, turn), "#9E9E9E"))
    fg.append(Circle(ox, oy, r, fill=YELLOW, stroke=WHITE, width=3.6))
    fg.append(Arc(ox, oy, 10.0, 195, 250, WHITE, 3.0))  # the glint
    # Three short rays: it gives light.
    rays = []
    for ang in (-135, -95, -175):
        a = math.radians(ang)
        r0, r1 = r + 4.2, r + 8.0
        rays.append(Line(ox + r0 * math.cos(a), oy + r0 * math.sin(a),
                         ox + r1 * math.cos(a), oy + r1 * math.sin(a), YELLOW, 3.2))
    fg = rays + fg
    mono = [Poly(_turn(neck, ox, oy, turn), WHITE), *[Poly(_turn(sp, ox, oy, turn), WHITE) for sp in stripes],
            Poly(_turn(tip, ox, oy, turn), WHITE), Circle(ox, oy, r, stroke=WHITE, width=3.6),
            Arc(ox, oy, 10.0, 195, 250, WHITE, 3.0), *[Line(l.x1, l.y1, l.x2, l.y2, WHITE, l.width) for l in rays]]
    return Design("F", "Bulb lens", "a light bulb whose round glass is the lens and whose base is the handle",
                  [], fg, mono, BLACK)


def design_g():
    """Glow ring: one bold yellow glass with a soft yellow glow around it, on black. Minimal."""
    cx = cy = 49.0
    r, ring = 15.5, 8.0
    # A smooth glow from just outside the ring, fading out by r 30. A thin dark gap first keeps the
    # ring's edge crisp at 36-48 px, where stacked discs smeared into a brown halo.
    outer = r + ring / 2  # 19.5
    glow = [Glow(cx, cy, 30.0, [(0.0, "#00FFD54F"), ((outer + 1.2) / 30.0, "#00FFD54F"),
                                ((outer + 2.2) / 30.0, "#8CFFD54F"), ((outer + 5.5) / 30.0, "#40FFD54F"),
                                (1.0, "#00FFD54F")])]
    hx1, hy1, hx2, hy2 = _handle(cx, cy, r, ring, 72.0)
    # The lens stays black: the glow is only around the glass.
    fg = [Circle(cx, cy, r, fill=BLACK), Line(hx1, hy1, hx2, hy2, YELLOW, 11.0),
          Circle(cx, cy, r, stroke=YELLOW, width=ring)]
    mono = [Line(hx1, hy1, hx2, hy2, WHITE, 11.0), Circle(cx, cy, r, stroke=WHITE, width=ring)]
    return Design("G", "Glow ring", "one bold yellow glass with a soft glow around it: minimal",
                  glow, fg, mono, BLACK)


DESIGNS = {"A": design_a, "B": design_b, "C": design_c, "D": design_d, "E": design_e, "F": design_f,
           "G": design_g}
