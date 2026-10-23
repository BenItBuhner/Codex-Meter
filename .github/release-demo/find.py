#!/usr/bin/env python3
"""find.py UI.xml TEXT [INDEX]: prints the centre "x y" of the INDEX-th (default 0) node whose
text or content-desc equals TEXT (falls back to starts-with). Prints nothing when not found."""
import re
import sys
import xml.etree.ElementTree as ET

path, want = sys.argv[1], sys.argv[2]
index = int(sys.argv[3]) if len(sys.argv) > 3 else 0
try:
    root = ET.parse(path).getroot()
except Exception:
    sys.exit(0)
nodes = list(root.iter("node"))


def label(n):
    return (n.get("text") or "").strip(), (n.get("content-desc") or "").strip()


exact = [n for n in nodes if want in label(n)]
loose = [n for n in nodes if any(v.startswith(want) for v in label(n) if v)]
hits = exact or loose
if len(hits) <= index:
    sys.exit(0)
m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", hits[index].get("bounds", ""))
if m:
    x1, y1, x2, y2 = map(int, m.groups())
    print((x1 + x2) // 2, (y1 + y2) // 2)
