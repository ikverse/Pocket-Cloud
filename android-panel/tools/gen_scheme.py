"""Generate the app's fallback Material 3 colour scheme from the brand colour, for phones without dynamic colour
(Android 10 and older, e.g. the Note 9). Follows Material Color Utilities: CAM16/HCT colour space, tonal palettes,
and the "tonal spot" scheme (the one Android uses for wallpaper colours). Checked against MCU's own test values.

    python gen_scheme.py            prints the self-check and the Kotlin colour constants
"""

import math

SEED = 0xFF3D71D9          # Pocket blue

# ---------------------------------------------------------------- colour maths (from material-color-utilities)
SRGB_TO_XYZ = [[0.41233895, 0.35762064, 0.18051042], [0.2126, 0.7152, 0.0722], [0.01932141, 0.11916382, 0.95034478]]
XYZ_TO_SRGB = [[3.2413774792388685, -1.5376652402851851, -0.49885366846268053],
               [-0.9691452513005321, 1.8758853451067872, 0.041556017264097036],
               [0.05562093689691305, -0.20395524564742123, 1.0571799111220335]]
WHITE = [95.047, 100.0, 108.883]


def linearize(c8):
    n = c8 / 255.0
    return (n / 12.92 if n <= 0.040449936 else ((n + 0.055) / 1.055) ** 2.4) * 100.0


def delinearize(lin):
    n = lin / 100.0
    v = n * 12.92 if n <= 0.0031308 else 1.055 * n ** (1 / 2.4) - 0.055
    return max(0, min(255, round(v * 255.0)))


def argb_to_xyz(argb):
    r, g, b = [linearize((argb >> s) & 255) for s in (16, 8, 0)]
    return [sum(m * c for m, c in zip(row, (r, g, b))) for row in SRGB_TO_XYZ]


def y_from_lstar(l):
    return 100.0 * ((l + 16) / 116) ** 3 if l > 8 else 100.0 * l / 903.2962962


def lstar_from_y(y):
    yn = y / 100.0
    return 116 * yn ** (1 / 3) - 16 if yn > 216 / 24389 else 24389 / 27 * yn


def argb_from_lstar(l):
    c = delinearize(y_from_lstar(l))
    return 0xFF000000 | c << 16 | c << 8 | c


class VC:  # default viewing conditions
    def __init__(self):
        adapting_luminance = 200.0 / math.pi * y_from_lstar(50.0) / 100.0
        bg_lstar = 50.0
        surround = 2.0
        m = [[0.401288, 0.650173, -0.051461], [-0.250268, 1.204414, 0.045854], [-0.002079, 0.048952, 0.953127]]
        rgb_w = [sum(a * b for a, b in zip(row, WHITE)) for row in m]
        f = 0.8 + surround / 10.0
        c = (0.59 + (0.69 - 0.59) * ((f - 0.9) * 10.0)) if f >= 0.9 else (0.525 + (0.59 - 0.525) * ((f - 0.8) * 10.0))
        d = f * (1.0 - (1.0 / 3.6) * math.exp((-adapting_luminance - 42.0) / 92.0))
        d = max(0, min(1, d))
        nc = f
        self.rgb_d = [d * (100.0 / x) + 1.0 - d for x in rgb_w]
        k = 1.0 / (5.0 * adapting_luminance + 1.0)
        k4 = k ** 4
        k4f = 1.0 - k4
        self.fl = k4 * adapting_luminance + 0.1 * k4f * k4f * (5.0 * adapting_luminance) ** (1 / 3)
        self.n = y_from_lstar(bg_lstar) / WHITE[1]
        self.z = 1.48 + math.sqrt(self.n)
        self.nbb = 0.725 / self.n ** 0.2
        self.ncb = self.nbb
        rgb_af = [(self.fl * self.rgb_d[i] * rgb_w[i] / 100.0) ** 0.42 for i in range(3)]
        rgb_a = [400.0 * x / (x + 27.13) for x in rgb_af]
        self.aw = (2.0 * rgb_a[0] + rgb_a[1] + 0.05 * rgb_a[2]) * self.nbb
        self.c, self.nc = c, nc
        self.fl_root = self.fl ** 0.25


V = VC()


def cam16(argb):
    x, y, z = argb_to_xyz(argb)
    r_c = 0.401288 * x + 0.650173 * y - 0.051461 * z
    g_c = -0.250268 * x + 1.204414 * y + 0.045854 * z
    b_c = -0.002079 * x + 0.048952 * y + 0.953127 * z
    rd, gd, bd = V.rgb_d[0] * r_c, V.rgb_d[1] * g_c, V.rgb_d[2] * b_c
    def adapt(c):
        af = (V.fl * abs(c) / 100.0) ** 0.42
        return math.copysign(1, c) * 400.0 * af / (af + 27.13)
    ra, ga, ba = adapt(rd), adapt(gd), adapt(bd)
    a = (11.0 * ra + -12.0 * ga + ba) / 11.0
    b = (ra + ga - 2.0 * ba) / 9.0
    u = (20.0 * ra + 20.0 * ga + 21.0 * ba) / 20.0
    p2 = (40.0 * ra + 20.0 * ga + ba) / 20.0
    hue = math.degrees(math.atan2(b, a)) % 360
    ac = p2 * V.nbb
    j = 100.0 * (ac / V.aw) ** (V.c * V.z)
    hue_prime = hue + 360 if hue < 20.14 else hue
    e_hue = 0.25 * (math.cos(math.radians(hue_prime) + 2.0) + 3.8)
    p1 = 50000.0 / 13.0 * e_hue * V.nc * V.ncb
    t = p1 * math.hypot(a, b) / (u + 0.305)
    alpha = t ** 0.9 * (1.64 - 0.29 ** V.n) ** 0.73
    chroma = alpha * math.sqrt(j / 100.0)
    return hue, chroma, j


def cam16_to_linear_rgb(j, chroma, hue):
    """Inverse CAM16: J, C, h -> linear sRGB (0-100 scale), without clipping."""
    alpha = 0.0 if chroma == 0 or j == 0 else chroma / math.sqrt(j / 100.0)
    t = (alpha / (1.64 - 0.29 ** V.n) ** 0.73) ** (1.0 / 0.9)
    h = math.radians(hue)
    e_hue = 0.25 * (math.cos(h + 2.0) + 3.8)
    ac = V.aw * (j / 100.0) ** (1.0 / V.c / V.z)
    p1 = e_hue * (50000.0 / 13.0) * V.nc * V.ncb
    p2 = ac / V.nbb
    hs, hc = math.sin(h), math.cos(h)
    gamma = 23.0 * (p2 + 0.305) * t / (23.0 * p1 + 11.0 * t * hc + 108.0 * t * hs)
    a, b = gamma * hc, gamma * hs
    ra = (460.0 * p2 + 451.0 * a + 288.0 * b) / 1403.0
    ga = (460.0 * p2 - 891.0 * a - 261.0 * b) / 1403.0
    ba = (460.0 * p2 - 220.0 * a - 6300.0 * b) / 1403.0
    def unadapt(c):
        base = max(0, 27.13 * abs(c) / (400.0 - abs(c)))
        return math.copysign(1, c) * (100.0 / V.fl) * base ** (1.0 / 0.42)
    rc, gc, bc = unadapt(ra) / V.rgb_d[0], unadapt(ga) / V.rgb_d[1], unadapt(ba) / V.rgb_d[2]
    x = 1.86206786 * rc - 1.01125463 * gc + 0.14918677 * bc
    y = 0.38752654 * rc + 0.62144744 * gc - 0.00897398 * bc
    z = -0.01584150 * rc - 0.03412294 * gc + 1.04996444 * bc
    return [sum(m * c for m, c in zip(row, (x, y, z))) for row in XYZ_TO_SRGB]


def solve(hue, chroma, lstar):
    """The colour with this CAM16 hue, as close to this chroma as sRGB allows, and exactly this L* (tone)."""
    if chroma < 0.0001 or lstar < 0.0001 or lstar > 99.9999:
        return argb_from_lstar(lstar)
    y_target = y_from_lstar(lstar)

    def at(c):
        lo, hi = 0.0, 100.0
        rgb = None
        for _ in range(60):                  # find J that gives the wanted luminance at this chroma
            j = (lo + hi) / 2
            rgb = cam16_to_linear_rgb(j, c, hue)
            y = 0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2]
            if y < y_target:
                lo = j
            else:
                hi = j
        return rgb

    def in_gamut(rgb):
        return all(-0.01 <= v <= 100.01 for v in rgb)

    rgb = at(chroma)
    if not in_gamut(rgb):                     # reduce chroma until it fits in sRGB
        lo, hi = 0.0, chroma
        for _ in range(40):
            mid = (lo + hi) / 2
            if in_gamut(at(mid)):
                lo = mid
            else:
                hi = mid
        rgb = at(lo)
    r, g, b = [delinearize(max(0, min(100, v))) for v in rgb]
    return 0xFF000000 | r << 16 | g << 8 | b


def palette(hue, chroma):
    return lambda tone: solve(hue, chroma, tone)


# ---------------------------------------------------------------- self-check against MCU's tests
BLUE_TONES = {100: 0xffffffff, 95: 0xfff1efff, 90: 0xffe0e0ff, 80: 0xffbec2ff, 70: 0xff9da3ff, 60: 0xff7c84ff,
              50: 0xff5a64ff, 40: 0xff343dff, 30: 0xff0000ef, 20: 0xff0001ac, 10: 0xff00006e, 0: 0xff000000}


def close(a, b, tol=2):
    return all(abs(((a >> s) & 255) - ((b >> s) & 255)) <= tol for s in (16, 8, 0))


def self_check():
    h, c, _ = cam16(0xFF0000FF)
    p = palette(h, c)
    worst = []
    for tone, want in BLUE_TONES.items():
        got = p(tone)
        worst.append((tone, f"{got & 0xFFFFFF:06x}", f"{want & 0xFFFFFF:06x}", close(got, want)))
    return worst


# ---------------------------------------------------------------- the tonal spot scheme
def scheme(seed):
    h, c, _ = cam16(seed)
    P, S, T = palette(h, 36.0), palette(h, 16.0), palette((h + 60.0) % 360, 24.0)
    N, NV, E = palette(h, 6.0), palette(h, 8.0), palette(25.0, 84.0)
    light = {
        "primary": P(40), "onPrimary": P(100), "primaryContainer": P(90), "onPrimaryContainer": P(10),
        "inversePrimary": P(80), "secondary": S(40), "onSecondary": S(100), "secondaryContainer": S(90),
        "onSecondaryContainer": S(10), "tertiary": T(40), "onTertiary": T(100), "tertiaryContainer": T(90),
        "onTertiaryContainer": T(10), "background": N(98), "onBackground": N(10), "surface": N(98), "onSurface": N(10),
        "surfaceVariant": NV(90), "onSurfaceVariant": NV(30), "surfaceTint": P(40), "inverseSurface": N(20),
        "inverseOnSurface": N(95), "error": E(40), "onError": E(100), "errorContainer": E(90), "onErrorContainer": E(10),
        "outline": NV(50), "outlineVariant": NV(80), "scrim": N(0), "surfaceBright": N(98), "surfaceDim": N(87),
        "surfaceContainerLowest": N(100), "surfaceContainerLow": N(96), "surfaceContainer": N(94),
        "surfaceContainerHigh": N(92), "surfaceContainerHighest": N(90),
    }
    dark = {
        "primary": P(80), "onPrimary": P(20), "primaryContainer": P(30), "onPrimaryContainer": P(90),
        "inversePrimary": P(40), "secondary": S(80), "onSecondary": S(20), "secondaryContainer": S(30),
        "onSecondaryContainer": S(90), "tertiary": T(80), "onTertiary": T(20), "tertiaryContainer": T(30),
        "onTertiaryContainer": T(90), "background": N(6), "onBackground": N(90), "surface": N(6), "onSurface": N(90),
        "surfaceVariant": NV(30), "onSurfaceVariant": NV(80), "surfaceTint": P(80), "inverseSurface": N(90),
        "inverseOnSurface": N(20), "error": E(80), "onError": E(20), "errorContainer": E(30), "onErrorContainer": E(90),
        "outline": NV(60), "outlineVariant": NV(30), "scrim": N(0), "surfaceBright": N(24), "surfaceDim": N(6),
        "surfaceContainerLowest": N(4), "surfaceContainerLow": N(10), "surfaceContainer": N(12),
        "surfaceContainerHigh": N(17), "surfaceContainerHighest": N(22),
    }
    return (h, c), light, dark


if __name__ == "__main__":
    rows = self_check()
    print("self-check against material-color-utilities (tones of pure blue):")
    for tone, got, want, ok in rows:
        print(f"  tone {tone:3d}: got #{got} want #{want} {'ok' if ok else 'OFF'}")
    print("all within 2/255:", all(r[3] for r in rows))
    (h, c), light, dark = scheme(SEED)
    print(f"\nseed #{SEED & 0xFFFFFF:06x}: hue {h:.1f}, chroma {c:.1f}\n")
    for name, sch in (("Light", light), ("Dark", dark)):
        for role, argb in sch.items():
            print(f"val {role}{name} = Color(0x{argb:08X})")
