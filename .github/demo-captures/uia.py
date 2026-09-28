#!/usr/bin/env python3
"""uiautomator helper: dump the current window hierarchy and find/tap nodes by text.

Usage:
  uia.py dump                      -> prints "text | content-desc | resource-id | class | bounds" per node
  uia.py find <substring>          -> prints center x y of first node whose text/desc contains substring
  uia.py tap <substring>           -> taps that node's center
  uia.py bounds <substring>        -> prints "x1 y1 x2 y2" bounds of node
  uia.py checked <substring>       -> prints the node's checked attribute (true/false)
Match is case-insensitive on text and content-desc. Optional --index N picks the Nth match.
Optional --exact requires equality instead of substring. Optional --id SUBSTR matches resource-id instead;
--class SUBSTR matches the widget class instead (e.g. --class EditText).
  uia.py drag <substring> X Y      -> long-press the node and drag it to X,Y (launcher icon drag)
  uia.py wait <seconds> <substring> -> poll until a matching node exists (exit 1 on timeout)
  uia.py text <substring>          -> prints the matched node's full text
"""
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ADB = ["adb"]


def sh(args, **kw):
    return subprocess.run(ADB + args, check=True, capture_output=True, **kw)


def dump():
    # uiautomator can exit non-zero (even SIGKILL on a slow emulator) after writing a valid
    # file, and can fail outright mid-transition, so ignore its exit code, validate the XML,
    # and retry with backoff.
    last = "no attempts"
    for attempt in range(8):
        subprocess.run(ADB + ["shell", "rm", "-f", "/sdcard/uia.xml"], capture_output=True)
        try:
            subprocess.run(ADB + ["shell", "uiautomator", "dump", "/sdcard/uia.xml"],
                           capture_output=True, timeout=90)
        except subprocess.TimeoutExpired:
            last = "uiautomator dump timed out"
        raw = subprocess.run(ADB + ["exec-out", "cat", "/sdcard/uia.xml"],
                             capture_output=True).stdout
        if raw.lstrip().startswith(b"<?xml"):
            try:
                return ET.fromstring(raw)
            except ET.ParseError as exc:
                last = f"parse error: {exc}"
        else:
            last = f"non-xml output: {raw[:160]!r}"
        time.sleep(1 + attempt)
    raise SystemExit(f"uiautomator dump failed: {last}")


def nodes(root):
    for n in root.iter("node"):
        yield n


def parse_bounds(b):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", b)
    return tuple(int(v) for v in m.groups())


ID_FILTER = None
CLASS_FILTER = None


def matches(n, needle, exact):
    if ID_FILTER is not None:
        return ID_FILTER.lower() in n.get("resource-id", "").lower()
    if CLASS_FILTER is not None:
        return CLASS_FILTER.lower() in n.get("class", "").lower()
    for attr in ("text", "content-desc"):
        v = n.get(attr, "")
        if exact and v.lower() == needle.lower():
            return True
        if not exact and needle.lower() in v.lower():
            return True
    return False


def find(needle, index=0, exact=False):
    root = dump()
    hits = [n for n in nodes(root) if matches(n, needle, exact)]
    if len(hits) <= index:
        raise SystemExit(f"no node matching {needle!r} (found {len(hits)})")
    return parse_bounds(hits[index].get("bounds"))


def main():
    args = sys.argv[1:]
    index = 0
    exact = False
    if "--index" in args:
        i = args.index("--index")
        index = int(args[i + 1])
        del args[i:i + 2]
    if "--exact" in args:
        exact = True
        args.remove("--exact")
    if "--id" in args:
        global ID_FILTER
        i = args.index("--id")
        ID_FILTER = args[i + 1]
        del args[i:i + 2]
        args.append(ID_FILTER)  # placeholder needle so the command parsing below stays uniform
    if "--class" in args:
        global CLASS_FILTER
        i = args.index("--class")
        CLASS_FILTER = args[i + 1]
        del args[i:i + 2]
        args.append(CLASS_FILTER)
    cmd = args[0]
    if cmd == "dump":
        root = dump()
        for n in nodes(root):
            t, d, r, c, b = (n.get("text", ""), n.get("content-desc", ""),
                             n.get("resource-id", ""), n.get("class", ""), n.get("bounds", ""))
            if t or d:
                print(f"{t!r} | {d!r} | {r} | {c.split('.')[-1]} | {b}")
        return
    if cmd == "drag":
        # drag <text...> <to_x> <to_y>: long-press the node, then move it to the target and release
        # (Launcher-style icon drag), using discrete motion events so the long-press registers.
        to_x, to_y = int(args[-2]), int(args[-1])
        needle = " ".join(args[1:-2])
        x1, y1, x2, y2 = find(needle, index, exact)
        cx, cy = (x1 + x2) // 2, (y1 + y2) // 2
        sh(["shell", "input", "motionevent", "DOWN", str(cx), str(cy)])
        time.sleep(1.4)
        steps = 12
        for i in range(1, steps + 1):
            x = cx + (to_x - cx) * i // steps
            y = cy + (to_y - cy) * i // steps
            sh(["shell", "input", "motionevent", "MOVE", str(x), str(y)])
            time.sleep(0.08)
        time.sleep(0.8)
        sh(["shell", "input", "motionevent", "UP", str(to_x), str(to_y)])
        print(f"dragged {needle!r} from {cx},{cy} to {to_x},{to_y}")
        return
    if cmd == "wait":
        # wait <seconds> <text...>: poll until a matching node appears; exit 1 on timeout
        timeout = float(args[1])
        needle = " ".join(args[2:])
        deadline = time.time() + timeout
        while time.time() < deadline:
            try:
                root = dump()
            except SystemExit:
                root = None
            if root is not None and any(matches(n, needle, exact) for n in nodes(root)):
                print(f"found {needle!r} after {timeout - (deadline - time.time()):.0f}s")
                return
            time.sleep(3)
        raise SystemExit(f"timed out waiting for {needle!r}")
    needle = " ".join(args[1:])
    if cmd == "text":
        root = dump()
        hits = [n for n in nodes(root) if matches(n, needle, exact)]
        if len(hits) <= index:
            raise SystemExit(f"no node matching {needle!r}")
        print(hits[index].get("text", "") or hits[index].get("content-desc", ""))
        return
    if cmd == "checked":
        root = dump()
        hits = [n for n in nodes(root) if matches(n, needle, exact)]
        if len(hits) <= index:
            raise SystemExit(f"no node matching {needle!r}")
        print(hits[index].get("checked", "false"))
        return
    x1, y1, x2, y2 = find(needle, index, exact)
    cx, cy = (x1 + x2) // 2, (y1 + y2) // 2
    if cmd == "find":
        print(cx, cy)
    elif cmd == "bounds":
        print(x1, y1, x2, y2)
    elif cmd == "tap":
        sh(["shell", "input", "tap", str(cx), str(cy)])
        print(f"tapped {needle!r} at {cx},{cy}")
    else:
        raise SystemExit(f"unknown command {cmd}")


if __name__ == "__main__":
    main()
