#!/usr/bin/env bash
# Liquid Glass home-screen widget corners on a real Android 16 phone: real widgets hosted the way a
# launcher hosts them (WidgetHostDemoActivity), light and dark, over a flat backdrop. Saves the
# screen and each widget's four corners blown up 8x, plus a measure of how far anything drawn by the
# widget sits outside Glassé's continuous ("squircle") corner (corners.txt).
set -euo pipefail
APK="${1:?phone APK required}"
OUT="${2:?output directory required}"
PKG=dev.bennett.codexmeter
mkdir -p "$OUT"
adb wait-for-device
until [[ "$(adb shell getprop sys.boot_completed | tr -d '\r')" == 1 ]]; do sleep 2; done
adb shell settings put system screen_off_timeout 1800000 || true
adb shell settings put global hide_error_dialogs 1 || true
adb shell svc power stayon true || true
adb shell wm dismiss-keyguard || true
adb install -r "$APK"
adb shell appwidget grantbind --package "$PKG" --user 0
python3 -m pip install --quiet --user pillow >/dev/null 2>&1 || true
DENSITY=$(adb shell wm density | tail -1 | grep -o '[0-9]*$')
echo "density $DENSITY"
# Liquid Glass, demo data, then the hosted widgets
adb shell am start -W -n "$PKG/.UsagePaceDemoActivity" --es design liquid_glass --ez enter_demo true >/dev/null
sleep 4
for opacity in 88 100; do
  adb logcat -c || true
  adb shell am start -W -S -n "$PKG/.WidgetHostDemoActivity" --ei opacity "$opacity" >/dev/null
  # the glass is baked off the main thread, then the widget is updated
  sleep 8
  adb exec-out screencap -p > "$OUT/widgets-$opacity.png"
  adb logcat -d -s WidgetHostDemo:I CodexMeterWidget:I > "$OUT/log-$opacity.txt" || true
  python3 - "$OUT" "$opacity" "$DENSITY" <<'PY'
import re, sys, math
from PIL import Image
out, opacity, dpi = sys.argv[1], sys.argv[2], int(sys.argv[3])
d = dpi / 160
shot = Image.open(f"{out}/widgets-{opacity}.png").convert("RGB")
bg = shot.getpixel((4, shot.height // 2))
rects = {}
for line in open(f"{out}/log-{opacity}.txt"):
    m = re.search(r"rect (\w+) (\d+) (\d+) (\d+) (\d+)", line)
    if m:
        rects[m.group(1)] = tuple(int(v) for v in m.groups()[1:])
# Glassé's continuous corner (Smooth.kt CONT, in units of r from the corner), sampled densely
C = [1.52866483,0.0,1.08849323,0.0,0.86840689,0.0244025,0.66993427,0.065496,0.36745291,0.18120437,
     0.18120436,0.36745286,0.065496,0.66993427,0.0244025,0.86840689,0.0,1.08849323,0.0,1.52866471]
curve = []
p0 = (C[0], C[1]); i = 2
while i < len(C):
    p1, p2, p3 = (C[i], C[i+1]), (C[i+2], C[i+3]), (C[i+4], C[i+5])
    for k in range(200):
        t = k / 199; u = 1 - t
        curve.append(tuple(u**3*p0[j] + 3*u*u*t*p1[j] + 3*u*t*t*p2[j] + t**3*p3[j] for j in range(2)))
    p0 = p3; i += 6
def inside(a, b):
    """True if (a, b) (units of r from the corner) is inside the continuous corner."""
    if a >= 1.5287 or b >= 1.5287:
        return a >= 0 and b >= 0
    # the curve is monotone: inside when below-right of it along the diagonal ray
    best = min(curve, key=lambda q: abs(math.atan2(q[1], q[0]) - math.atan2(b, a)))
    return math.hypot(a, b) >= math.hypot(*best)
margin, radius = 4 * d, 24 * d
lines = []
for theme, (x, y, w, h) in rects.items():
    gx, gy, gw, gh = x + margin, y + margin, w - 2 * margin, h - 2 * margin
    r = min(radius, min(gw, gh) / 2)
    n = int(1.8 * r)
    worst = 0
    for corner, (cx, cy, sx, sy) in {"tl": (gx, gy, 1, 1), "tr": (gx + gw, gy, -1, 1),
                                     "bl": (gx, gy + gh, 1, -1), "br": (gx + gw, gy + gh, -1, -1)}.items():
        box = (min(cx, cx + sx * n), min(cy, cy + sy * n), max(cx, cx + sx * n), max(cy, cy + sy * n))
        box = tuple(int(round(v)) for v in box)
        shot.crop(box).resize(((box[2] - box[0]) * 8, (box[3] - box[1]) * 8), Image.NEAREST) \
            .save(f"{out}/corner-{opacity}-{theme}-{corner}.png")
        # pixels outside the continuous corner (by more than 1.5 px, the antialiasing) that differ
        # from the backdrop: anything drawn there sticks out of the glass's outline
        stray = 0
        for py in range(box[1], box[3]):
            for px in range(box[0], box[2]):
                a = (px + 0.5 - cx) * sx / r; b = (py + 0.5 - cy) * sy / r
                ai = a + 1.5 / r; bi = b + 1.5 / r
                if a < 0 or b < 0 or inside(ai, bi):
                    continue
                c = shot.getpixel((px, py))
                if max(abs(c[k] - bg[k]) for k in range(3)) > 24:
                    stray += 1
        worst = max(worst, stray)
        lines.append(f"{opacity} {theme} {corner}: {stray} px drawn outside the continuous corner")
    lines.append(f"{opacity} {theme}: worst corner {worst} px")
open(f"{out}/corners.txt", "a").write("\n".join(lines) + "\n")
print("\n".join(lines))
PY
done
adb logcat -d > "$OUT/logcat.txt" || true
