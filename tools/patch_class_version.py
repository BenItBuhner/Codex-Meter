#!/usr/bin/env python3
"""Downgrade Java 8 classfile headers to Java 7 after compiling lambda-free source.

The bundled legacy dx accepts classfile version 51. Codex Meter intentionally avoids
Java 8 bytecode features, so only the classfile version marker needs changing.
"""
from __future__ import print_function
import pathlib
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: patch_class_version.py <classes-directory>")
root = pathlib.Path(sys.argv[1])
count = 0
for path in root.rglob("*.class"):
    data = bytearray(path.read_bytes())
    if data[:4] != b"\xca\xfe\xba\xbe":
        raise SystemExit("not a class file: %s" % path)
    major = int.from_bytes(data[6:8], "big")
    if major == 52:
        data[6:8] = (51).to_bytes(2, "big")
        path.write_bytes(data)
        count += 1
    elif major > 51:
        raise SystemExit("unsupported classfile version %d: %s" % (major, path))
print("Patched %d class files to version 51." % count)
