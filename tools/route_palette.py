#!/usr/bin/env python3
"""Searches the six route colours, one per Garmin hue, for RouteColors.

Each slot stays within HUE_WINDOW of its pure sRGB hue in OKLCh, so it reads as that name.
A palette must clear CONTRAST against the map's land, water and vegetation, and keep its slots
apart by CIEDE2000 under normal vision and simulated protanopia, deuteranopia and tritanopia
(Machado 2009, full severity). Of the palettes that do, the most vivid wins, short of neon.

Standard library only: python3 tools/route_palette.py
"""

import math
import random

NAMES = ["red", "yellow", "green", "cyan", "blue", "magenta"]
HUE_WINDOW = 8.0
MIN_CHROMA = 0.08
# Past this a line reads as neon on the map.
MAX_CHROMA = 0.20

# Land is surfaceContainerLowest: tone 100 in light, tone 4 in dark, whatever the wallpaper.
# Water and vegetation are MapRenderTheme's.
THEMES = {
    "light": {
        "surfaces": {"land": 0xFFFFFF, "water": 0xA6CBE3, "vegetation": 0xD5E9CF},
        "separation": 18.0,
        "cvd_separation": 7.0,
        "min_lightness": 0.0,
        "max_lightness": 1.0,
    },
    "dark": {
        "surfaces": {"land": 0x0F0F0F, "water": 0x16323F, "vegetation": 0x1E3220},
        "separation": 25.0,
        "cvd_separation": 12.0,
        # Darker, red goes brown and magenta purple against the dark land.
        "min_lightness": 0.6,
        "max_lightness": 0.85,
    },
}
CONTRAST = {"land": 2.5, "water": 1.75, "vegetation": 1.75}

CVD = {
    "protan": ((0.152286, 1.052583, -0.204868), (0.114503, 0.786281, 0.099216), (-0.003882, -0.048116, 1.051998)),
    "deutan": ((0.367322, 0.860646, -0.227968), (0.280085, 0.672501, 0.047413), (-0.011820, 0.042940, 0.968881)),
    "tritan": ((1.255528, -0.076749, -0.178779), (-0.078411, 0.930809, 0.147602), (0.004733, 0.691367, 0.303900)),
}


def to_linear(c):
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def from_linear(c):
    return 12.92 * c if c <= 0.0031308 else 1.055 * c ** (1 / 2.4) - 0.055


def linear_rgb(rgb):
    return tuple(to_linear((rgb >> s & 0xFF) / 255) for s in (16, 8, 0))


def luminance(lin):
    return 0.2126 * lin[0] + 0.7152 * lin[1] + 0.0722 * lin[2]


def contrast(a, b):
    la, lb = sorted((luminance(a), luminance(b)), reverse=True)
    return (la + 0.05) / (lb + 0.05)


def oklab_to_linear(L, a, b):
    l = (L + 0.3963377774 * a + 0.2158037573 * b) ** 3
    m = (L - 0.1055613458 * a - 0.0638541728 * b) ** 3
    s = (L - 0.0894841775 * a - 1.2914855480 * b) ** 3
    return (
        4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
        -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
        -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s,
    )


def oklch(lin):
    l = math.cbrt(0.4122214708 * lin[0] + 0.5363325363 * lin[1] + 0.0514459929 * lin[2])
    m = math.cbrt(0.2119034982 * lin[0] + 0.6806995451 * lin[1] + 0.1073969566 * lin[2])
    s = math.cbrt(0.0883024619 * lin[0] + 0.2817188376 * lin[1] + 0.6299787005 * lin[2])
    L = 0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s
    a = 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s
    b = 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s
    return L, math.hypot(a, b), math.degrees(math.atan2(b, a)) % 360


def cielab(lin):
    x = (0.4124564 * lin[0] + 0.3575761 * lin[1] + 0.1804375 * lin[2]) / 0.95047
    y = 0.2126729 * lin[0] + 0.7151522 * lin[1] + 0.0721750 * lin[2]
    z = (0.0193339 * lin[0] + 0.1191920 * lin[1] + 0.9503041 * lin[2]) / 1.08883

    def f(t):
        return math.cbrt(t) if t > 216 / 24389 else (24389 / 27 * t + 16) / 116

    fx, fy, fz = f(x), f(y), f(z)
    return 116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz)


def ciede2000(p, q):
    L1, a1, b1 = p
    L2, a2, b2 = q
    c_bar = (math.hypot(a1, b1) + math.hypot(a2, b2)) / 2
    g = 0.5 * (1 - math.sqrt(c_bar**7 / (c_bar**7 + 25**7)))
    a1p, a2p = a1 * (1 + g), a2 * (1 + g)
    c1p, c2p = math.hypot(a1p, b1), math.hypot(a2p, b2)
    h1p = math.degrees(math.atan2(b1, a1p)) % 360 if c1p else 0.0
    h2p = math.degrees(math.atan2(b2, a2p)) % 360 if c2p else 0.0
    dl, dc = L2 - L1, c2p - c1p
    if c1p * c2p == 0:
        dh = 0.0
    elif abs(h2p - h1p) <= 180:
        dh = h2p - h1p
    else:
        dh = h2p - h1p - 360 if h2p > h1p else h2p - h1p + 360
    dH = 2 * math.sqrt(c1p * c2p) * math.sin(math.radians(dh / 2))
    l_bar, cp_bar = (L1 + L2) / 2, (c1p + c2p) / 2
    if c1p * c2p == 0:
        h_bar = h1p + h2p
    elif abs(h1p - h2p) <= 180:
        h_bar = (h1p + h2p) / 2
    else:
        h_bar = (h1p + h2p + 360) / 2 if h1p + h2p < 360 else (h1p + h2p - 360) / 2
    t = (1 - 0.17 * math.cos(math.radians(h_bar - 30)) + 0.24 * math.cos(math.radians(2 * h_bar))
         + 0.32 * math.cos(math.radians(3 * h_bar + 6)) - 0.20 * math.cos(math.radians(4 * h_bar - 63)))
    sl = 1 + 0.015 * (l_bar - 50) ** 2 / math.sqrt(20 + (l_bar - 50) ** 2)
    sc, sh = 1 + 0.045 * cp_bar, 1 + 0.015 * cp_bar * t
    rt = (-2 * math.sqrt(cp_bar**7 / (cp_bar**7 + 25**7))
          * math.sin(math.radians(60 * math.exp(-(((h_bar - 275) / 25) ** 2)))))
    return math.sqrt((dl / sl) ** 2 + (dc / sc) ** 2 + (dH / sh) ** 2 + rt * (dc / sc) * (dH / sh))


def simulate(lin, matrix):
    return tuple(min(1.0, max(0.0, sum(row[i] * lin[i] for i in range(3)))) for row in matrix)


def q_lightness(lin):
    return oklch(lin)[0]


def to_rgb(lin):
    return sum(round(from_linear(c) * 255) << s for c, s in zip(lin, (16, 8, 0)))


PRIMARY_HUES = {
    name: oklch(linear_rgb(rgb))[2]
    for name, rgb in zip(NAMES, (0xFF0000, 0xFFFF00, 0x00FF00, 0x00FFFF, 0x0000FF, 0xFF00FF))
}


class Candidate:
    def __init__(self, rgb):
        self.rgb = rgb
        lin = linear_rgb(rgb)
        self.chroma = oklch(lin)[1]
        self.views = [cielab(lin)] + [cielab(simulate(lin, m)) for m in CVD.values()]


def candidates(name, theme):
    surfaces = {k: linear_rgb(v) for k, v in theme["surfaces"].items()}
    found = {}
    centre = PRIMARY_HUES[name]
    for li in range(25, 96):
        for ci in range(8, 38):
            for hi in range(-int(HUE_WINDOW), int(HUE_WINDOW) + 1, 2):
                lin = oklab_to_linear(li / 100, ci / 100 * math.cos(math.radians(centre + hi)),
                                      ci / 100 * math.sin(math.radians(centre + hi)))
                if not all(-1e-4 <= c <= 1 + 1e-4 for c in lin):
                    continue
                rgb = to_rgb(tuple(min(1.0, max(0.0, c)) for c in lin))
                if rgb in found:
                    continue
                q = linear_rgb(rgb)
                _, chroma, hue = oklch(q)
                off = min(abs(hue - centre) % 360, 360 - abs(hue - centre) % 360)
                if not MIN_CHROMA <= chroma <= MAX_CHROMA or off > HUE_WINDOW or not theme["min_lightness"] <= q_lightness(q) <= theme["max_lightness"]:
                    continue
                if all(contrast(q, surfaces[k]) >= v for k, v in CONTRAST.items()):
                    found[rgb] = Candidate(rgb)
    return list(found.values())


def separation(a, b, theme):
    """Worst margin over the four visions, as a fraction of each one's target."""
    worst = math.inf
    for i, (p, q) in enumerate(zip(a.views, b.views)):
        target = theme["separation"] if i == 0 else theme["cvd_separation"]
        worst = min(worst, ciede2000(p, q) / target)
    return worst


def score(palette, theme):
    margin = min(separation(a, b, theme) for i, a in enumerate(palette) for b in palette[i + 1:])
    vivid = min(c.chroma for c in palette)
    # Clearing every target first; then the dullest slot as vivid as can be; then more margin.
    return (min(margin, 1.0), vivid if margin >= 1 else 0.0, margin)


def search(theme, restarts=40, seed=1):
    rng = random.Random(seed)
    pools = [candidates(n, theme) for n in NAMES]
    for name, pool in zip(NAMES, pools):
        if not pool:
            raise SystemExit(f"No {name} clears the contrast targets")
    # Thinned for speed to the most vivid.
    pools = [sorted(p, key=lambda c: -c.chroma)[:600] for p in pools]
    best, best_score = None, None
    for _ in range(restarts):
        palette = [rng.choice(p) for p in pools]
        current = score(palette, theme)
        improved = True
        while improved:
            improved = False
            for slot, pool in enumerate(pools):
                for c in pool:
                    trial = palette[:slot] + [c] + palette[slot + 1:]
                    s = score(trial, theme)
                    if s > current:
                        palette, current, improved = trial, s, True
        if best_score is None or current > best_score:
            best, best_score = palette, current
    return best, best_score


def report(label, palette, theme):
    print(f"{label}:")
    surfaces = {k: linear_rgb(v) for k, v in theme["surfaces"].items()}
    for name, c in zip(NAMES, palette):
        lin = linear_rgb(c.rgb)
        L, chroma, hue = oklch(lin)
        contrasts = " ".join(f"{k} {contrast(lin, s):.2f}" for k, s in surfaces.items())
        print(f"  {name:8} 0x{c.rgb:06X}  L {L:.2f} C {chroma:.3f} h {hue:5.1f}  {contrasts}")
    visions = ["normal"] + list(CVD)
    for i, vision in enumerate(visions):
        pairs = [(ciede2000(a.views[i], b.views[i]), NAMES[x], NAMES[y])
                 for x, a in enumerate(palette) for y, b in enumerate(palette) if x < y]
        d, x, y = min(pairs)
        print(f"  closest {vision:7} {d:5.1f}  {x}/{y}")


if __name__ == "__main__":
    for label, theme in THEMES.items():
        palette, s = search(theme)
        report(label, palette, theme)
        print(f"  margin {s[2]:.2f} of target, dullest chroma {min(c.chroma for c in palette):.3f}\n")
