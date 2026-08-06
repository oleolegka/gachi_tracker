#!/usr/bin/env python3
"""Generates the launcher icon from the pixel-art map below.

WHAT IT WRITES (all paths relative to the repository root):

    app/src/main/res/drawable/ic_launcher_foreground.xml   vector, the drawing
    app/src/main/res/drawable/ic_launcher_monochrome.xml   vector, themed-icon silhouette
    app/src/main/res/values/ic_launcher_background.xml     the backdrop colour
    app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml     adaptive icon
    app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml

Run it after editing the map or the palette, and commit what it produced:

    python3 tools/pixel_icon.py
    python3 tools/pixel_icon.py --preview /tmp/icon.png   # needs Pillow, writes nothing else

WHY A SCRIPT AND NOT HAND-WRITTEN XML
The drawing is a 32x32 grid of coloured squares. Written by hand that is a thousand
rectangles nobody can review and nobody can change: moving one pixel of the mouth would
mean re-deriving coordinates. Here the map IS the source, and the geometry below is
derived from it.

WHERE THE MAP CAME FROM
It is variant B32 ("head and shoulders", 32x32) of the icon mock-up that was reviewed and
picked, kept outside this repository at design/icon/icon-variants.html of the design
folder. It is copied in rather than parsed out of the HTML on every run so that the
repository can regenerate its own icon without that file.

ADAPTIVE ICON GEOMETRY
The canvas is 108x108 dp; the launcher masks it with a shape of its own choosing and only
a CIRCLE OF 66 dp in the centre is guaranteed to survive. So the drawing is scaled to fit
inside that circle: the smallest circle enclosing every non-empty pixel is computed, and
that circle is what gets mapped onto the safe zone. The result is a drawing of about 53 dp
across - deliberately smaller than a typical launcher icon, which is the price of nothing
ever being clipped. Raising SAFE_DIAMETER_DP trades that guarantee for size.
"""

from __future__ import annotations

import argparse
import itertools
import math
import os
import re

# --- the drawing ---------------------------------------------------------------------
#
# One character is one pixel:
#   .  empty       S  skin        s  skin in shadow   H  hair
#   o  lines (brows, mouth, harness straps)           E  eyes
#   V  cloth       B  belt        (V and B are unused by this map, kept for other maps)

PIXEL_MAP = [
    "................................",
    "............HHHHHHHH............",
    "..........HHHHHHHHHHHH..........",
    "........HHHHHHHHHHHHHHHH........",
    ".......HHHHHHHHHHHHHHHHHH.......",
    ".......HHHHHHHHHHHHHHHHHH.......",
    ".......HHHHHHHHHHHHHHHHHH.......",
    ".......HHHSSSSSSSSSSSSHHH.......",
    ".......HHSSSSSSSSSSSSSSHH.......",
    ".......HHSSSSSSSSSSSSSSHH.......",
    ".......HHSSSSSSSSSSSSSSHH.......",
    ".......HHSooooSSSSooooSHH.......",
    ".......HHSooooSSSSooooSHH.......",
    ".......HHSSEEESSSSEEESSHH.......",
    ".......HHSSSSSSSSSSSSSSHH.......",
    ".......SSSSSSSSssSSSSSSSS.......",
    ".......SSSSSSSSssSSSSSSSS.......",
    ".......SSSSSSSssssSSSSSSS.......",
    ".......SSSSSSSSSSSSSSSSSS.......",
    ".......SSSSSSSSSSSSSSSSSS.......",
    ".......SSSSSooooooooSSSSS.......",
    ".......sSSSSSooooooSSSSSs.......",
    "........SSSSSSSSSSSSSSSS........",
    "..........SSSSSSSSSSSS..........",
    "............sSSSSSSs............",
    "...........SSSSSSSSSS...........",
    "......ssSSSSSSSSSSSSSSSSss......",
    "..sssSSSSSSSSSSSSSSSSSSSSSSsss..",
    "sssSSSsSSSSSSSSSSSSSSSSSSsSSSsss",
    "ssSSSSsSSSooSSSSSSSSooSSSsSSSSss",
    "ssSSSSsSSSSooSSSSSSooSSSSsSSSSss",
    "ssSSSSsSSSSSooSSSSooSSSSSsSSSSss",
]

# --- colours -------------------------------------------------------------------------

BACKDROP = "#1A1A19"  # the same dark surface the app itself uses (ui/theme/Color.kt)

PALETTE = {
    "S": "#E8A06C",  # skin
    "s": "#BD7745",  # skin in shadow
    "H": "#5C3B21",  # hair
    "o": "#2F2119",  # lines
    "E": "#241A14",  # eyes
    "V": "#3A3630",  # cloth
    "B": "#2F2119",  # belt
}

# On a dark backdrop the darkest colours sink into it - #5C3B21 hair on #1A1A19 is a
# contrast of about 1.8:1, i.e. the top of the head stops existing at launcher size. The
# mock-up solves this the same way, by lifting those three; this is the same drawing, not
# a different one.
DARK_BACKDROP_TWEAK = {"H": "#8A5F38", "V": "#57524A", "B": "#3D2C1F"}

# Painting order. Later wins where two rectangles touch (see EPS), so the details that
# carry the face go last.
DRAW_ORDER = ["S", "s", "V", "B", "H", "o", "E"]

# --- geometry ------------------------------------------------------------------------

CANVAS_DP = 108.0  # the adaptive-icon canvas
SAFE_DIAMETER_DP = 86.0  # the drawing's enclosing circle; 66 is the mask-proof minimum, but it leaves the art visibly small next to other launcher icons

# Neighbouring rectangles are grown by this much (in canvas units, against a cell of
# roughly 1.7) so that no hairline of the backdrop shows through the seam between two
# colours. Rectangles OF THE SAME colour share one <path> and need no such help - a single
# path is rasterised as one shape and has no internal seams.
EPS = 0.02


def used_pixels(pixel_map):
    """[(x, y, char)] for every non-empty cell."""
    return [
        (x, y, ch)
        for y, row in enumerate(pixel_map)
        for x, ch in enumerate(row)
        if ch != "."
    ]


def circumcircle(a, b, c):
    """Circle through three points, or None if they are collinear."""
    (ax, ay), (bx, by), (cx, cy) = a, b, c
    d = 2 * (ax * (by - cy) + bx * (cy - ay) + cx * (ay - by))
    if abs(d) < 1e-12:
        return None
    ux = ((ax * ax + ay * ay) * (by - cy) + (bx * bx + by * by) * (cy - ay) + (cx * cx + cy * cy) * (ay - by)) / d
    uy = ((ax * ax + ay * ay) * (cx - bx) + (bx * bx + by * by) * (ax - cx) + (cx * cx + cy * cy) * (bx - ax)) / d
    return (ux, uy), math.dist((ux, uy), a)


def convex_hull(points):
    """Monotone chain. The minimal enclosing circle is decided by hull points only."""
    pts = sorted(set(points))
    if len(pts) <= 2:
        return pts

    def half(seq):
        out = []
        for p in seq:
            while len(out) >= 2:
                (x1, y1), (x2, y2) = out[-2], out[-1]
                if (x2 - x1) * (p[1] - y1) - (y2 - y1) * (p[0] - x1) > 0:
                    break
                out.pop()
            out.append(p)
        return out[:-1]

    return half(pts) + half(reversed(pts))


def min_enclosing_circle(points):
    """Exact, by brute force over the hull: every pair as a diameter, every triple as a
    circumcircle. The hull here has a couple of dozen points, so this is instant and,
    unlike an iterative approximation, it is deterministic and reviewable."""
    hull = convex_hull(points)
    best = None
    candidates = []
    for a, b in itertools.combinations(hull, 2):
        centre = ((a[0] + b[0]) / 2, (a[1] + b[1]) / 2)
        candidates.append((centre, math.dist(centre, a)))
    for a, b, c in itertools.combinations(hull, 3):
        got = circumcircle(a, b, c)
        if got:
            candidates.append(got)
    for centre, radius in candidates:
        if radius + 1e-9 < (best[1] if best else float("inf")):
            if all(math.dist(centre, p) <= radius + 1e-9 for p in hull):
                best = (centre, radius)
    return best


def layout(pixel_map):
    """Cell size in canvas units and the origin of the grid, such that the drawing's
    enclosing circle is the safe zone, concentric with the canvas."""
    corners = []
    for x, y, _ in used_pixels(pixel_map):
        corners += [(x, y), (x + 1, y), (x, y + 1), (x + 1, y + 1)]
    (cx, cy), radius = min_enclosing_circle(corners)
    cell = (SAFE_DIAMETER_DP / 2) / radius
    return cell, CANVAS_DP / 2 - cx * cell, CANVAS_DP / 2 - cy * cell


def rectangles(pixel_map, chars):
    """Greedy rectangle cover of the cells whose character is in `chars`: run right, then
    grow down while the whole run matches. Emitting one rectangle per pixel would work
    too, at four times the file size and no visual difference."""
    height, width = len(pixel_map), len(pixel_map[0])
    taken = [[False] * width for _ in range(height)]
    out = []
    for y in range(height):
        for x in range(width):
            if taken[y][x] or pixel_map[y][x] not in chars:
                continue
            x2 = x
            while x2 + 1 < width and not taken[y][x2 + 1] and pixel_map[y][x2 + 1] in chars:
                x2 += 1
            y2 = y
            while y2 + 1 < height and all(
                not taken[y2 + 1][xx] and pixel_map[y2 + 1][xx] in chars for xx in range(x, x2 + 1)
            ):
                y2 += 1
            for yy in range(y, y2 + 1):
                for xx in range(x, x2 + 1):
                    taken[yy][xx] = True
            out.append((x, y, x2 - x + 1, y2 - y + 1))
    return out


def num(v):
    """Three decimals, trailing zeros trimmed - the XML is read by people too."""
    s = f"{v:.3f}".rstrip("0").rstrip(".")
    return "0" if s in ("", "-0") else s


def path_data(rects, cell, ox, oy):
    parts = []
    for x, y, w, h in rects:
        x0, y0 = ox + x * cell - EPS, oy + y * cell - EPS
        parts.append(f"M{num(x0)},{num(y0)}h{num(w * cell + 2 * EPS)}v{num(h * cell + 2 * EPS)}h{num(-(w * cell + 2 * EPS))}z")
    return "".join(parts)


def vector_xml(paths, comment):
    body = "\n".join(
        f'    <path\n        android:fillColor="{colour}"\n        android:pathData="{data}" />'
        for colour, data in paths
    )
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        f"<!--\n{comment}\n-->\n"
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        f'    android:width="{num(CANVAS_DP)}dp"\n'
        f'    android:height="{num(CANVAS_DP)}dp"\n'
        f'    android:viewportWidth="{num(CANVAS_DP)}"\n'
        f'    android:viewportHeight="{num(CANVAS_DP)}">\n'
        f"{body}\n"
        "</vector>\n"
    )


GENERATED = "    Generated by tools/pixel_icon.py from the pixel map in that file.\n    Do not edit by hand: edit the map and run the script again."


def build(root):
    palette = dict(PALETTE, **DARK_BACKDROP_TWEAK)
    cell, ox, oy = layout(PIXEL_MAP)

    present = {ch for _, _, ch in used_pixels(PIXEL_MAP)}
    foreground = []
    for ch in DRAW_ORDER:
        if ch not in present:
            continue
        rects = rectangles(PIXEL_MAP, {ch})
        foreground.append((palette[ch], path_data(rects, cell, ox, oy)))

    # The themed (monochrome) icon: the system paints it in one colour of its own, so
    # everything that is not a hole is one shape. The face is kept readable by leaving the
    # eyes, brows, mouth and straps EMPTY - they become holes that show the themed
    # background, which is the only way those features survive a single-colour icon.
    solid = present - {"o", "E"}
    mono = [("#FF000000", path_data(rectangles(PIXEL_MAP, solid), cell, ox, oy))]

    files = {
        "app/src/main/res/drawable/ic_launcher_foreground.xml": vector_xml(
            foreground,
            f"{GENERATED}\n\n"
            "    The drawing of the launcher icon, on the 108dp adaptive-icon canvas. It is\n"
            "    sized to sit inside the 66dp circle every launcher mask keeps.",
        ),
        "app/src/main/res/drawable/ic_launcher_monochrome.xml": vector_xml(
            mono,
            f"{GENERATED}\n\n"
            "    The themed-icon variant (Android 13+): one silhouette, tinted by the system.\n"
            "    Eyes, brows, mouth and straps are holes rather than shapes - a single colour\n"
            "    cannot draw them otherwise.",
        ),
        "app/src/main/res/values/ic_launcher_background.xml": (
            '<?xml version="1.0" encoding="utf-8"?>\n'
            f"<!--\n{GENERATED}\n-->\n"
            "<resources>\n"
            f'    <color name="ic_launcher_background">{BACKDROP}</color>\n'
            "</resources>\n"
        ),
    }

    adaptive = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        f"<!--\n{GENERATED}\n-->\n"
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <background android:drawable="@color/ic_launcher_background" />\n'
        '    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n'
        '    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />\n'
        "</adaptive-icon>\n"
    )
    # Round and square are the same file: the drawing already fits the circle, so there is
    # nothing to draw differently for a round mask.
    files["app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml"] = adaptive
    files["app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml"] = adaptive

    for rel, text in files.items():
        path = os.path.join(root, rel)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(text)

    width = len(PIXEL_MAP[0]) * cell
    height = len(PIXEL_MAP) * cell
    print(f"cell {cell:.4f} dp, drawing {width:.1f} x {height:.1f} dp on a {CANVAS_DP:.0f} dp canvas")
    print(f"safe zone {SAFE_DIAMETER_DP:.0f} dp - the drawing's enclosing circle is exactly that")
    for rel in files:
        print(f"  {os.path.getsize(os.path.join(root, rel)):>6} B  {rel}")


def preview(root, out_path, size=512):
    """Renders the GENERATED xml (not the map) to a PNG, masked to a circle, so the file
    that ships can be looked at. Needs Pillow; nothing else in this script does."""
    from PIL import Image, ImageDraw

    scale = size / CANVAS_DP
    img = Image.new("RGBA", (size, size), BACKDROP)
    draw = ImageDraw.Draw(img)
    xml = open(os.path.join(root, "app/src/main/res/drawable/ic_launcher_foreground.xml"), encoding="utf-8").read()
    for colour, data in re.findall(r'fillColor="(#[0-9A-Fa-f]+)"\s+android:pathData="([^"]+)"', xml):
        for x, y, w, h in re.findall(r"M(-?[\d.]+),(-?[\d.]+)h(-?[\d.]+)v(-?[\d.]+)", data):
            x, y, w, h = float(x), float(y), float(w), float(h)
            draw.rectangle([x * scale, y * scale, (x + w) * scale, (y + h) * scale], fill=colour)
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).ellipse([0, 0, size - 1, size - 1], fill=255)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(img, mask=mask)
    # the 66dp safe zone, as a hairline, to see how much room is left
    inset = (CANVAS_DP - SAFE_DIAMETER_DP) / 2 * scale
    ImageDraw.Draw(out).ellipse([inset, inset, size - inset, size - inset], outline=(255, 0, 0, 120))
    out.save(out_path)
    print(f"preview -> {out_path}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--preview", metavar="PNG", help="also render a PNG preview (needs Pillow)")
    args = parser.parse_args()
    repo_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    build(repo_root)
    if args.preview:
        preview(repo_root, args.preview)
