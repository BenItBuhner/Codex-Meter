"""Finds More's close button on a screenshot: the round light-grey glass at the sheet's top right.
Prints "x y" in pixels. Looks for the sheet's top edge (the first row, from the bottom half up, where
the page's black turns into the sheet's grey) and puts the X 26 dp below it, 46 dp in from the right."""
import struct, sys, zlib

def load(path):
    data = open(path, 'rb').read()
    pos = 8; idat = b''; w = h = 0
    while pos < len(data):
        n = struct.unpack('>I', data[pos:pos + 4])[0]; t = data[pos + 4:pos + 8]; body = data[pos + 8:pos + 8 + n]
        if t == b'IHDR': w, h, depth, ctype = struct.unpack('>IIBB', body[:10])
        elif t == b'IDAT': idat += body
        pos += 12 + n
    raw = zlib.decompress(idat); bpp = 4 if ctype == 6 else 3; stride = w * bpp
    rows = []; prev = bytearray(stride); i = 0
    for _ in range(h):
        f = raw[i]; line = bytearray(raw[i + 1:i + 1 + stride]); i += 1 + stride
        for x in range(stride):
            a = line[x - bpp] if x >= bpp else 0; b = prev[x]; c = prev[x - bpp] if x >= bpp else 0
            if f == 1: line[x] = (line[x] + a) & 255
            elif f == 2: line[x] = (line[x] + b) & 255
            elif f == 3: line[x] = (line[x] + (a + b) // 2) & 255
            elif f == 4:
                p = a + b - c; pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                line[x] = (line[x] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
        rows.append(line); prev = line
    return w, h, bpp, rows

w, h, bpp, rows = load(sys.argv[1])
dens = float(sys.argv[2]) if len(sys.argv) > 2 else w / 384.0 * 160
d = dens / 160 if len(sys.argv) > 2 else w / 384.0
x = w // 2
top = None
for y in range(h // 3, h):
    r, g, b = rows[y][x * bpp:x * bpp + 3]
    if r + g + b > 3 * 22: top = y; break
if top is None: sys.exit(1)
print(round(w - 46 * d), round(top + 26 * d))
