#!/usr/bin/env python3
"""Assemble an APK and align all uncompressed entries to a four-byte boundary."""
from __future__ import print_function
import argparse
import struct
import zipfile
from pathlib import Path

ALIGNMENT_EXTRA_ID = 0xD935


def clone_info(source):
    target = zipfile.ZipInfo(source.filename, source.date_time)
    target.comment = source.comment
    target.create_system = source.create_system
    target.create_version = source.create_version
    target.extract_version = source.extract_version
    target.reserved = source.reserved
    target.flag_bits = source.flag_bits
    target.volume = source.volume
    target.internal_attr = source.internal_attr
    target.external_attr = source.external_attr
    target.compress_type = source.compress_type
    # AAPT2 base archives currently contain no entry extras. Preserve any valid extras.
    target.extra = source.extra or b""
    return target


def utf8_name_length(info):
    # Android resource paths are ASCII. Honour the UTF-8 flag for completeness.
    return len(info.filename.encode("utf-8" if info.flag_bits & 0x800 else "cp437"))


def add_alignment_extra(info, current_offset, alignment=4):
    if info.compress_type != zipfile.ZIP_STORED:
        return
    base = current_offset + 30 + utf8_name_length(info) + len(info.extra)
    if base % alignment == 0:
        return
    # Extra fields consist of a 4-byte header followed by the payload.
    payload_len = (-(base + 4)) % alignment
    info.extra += struct.pack("<HH", ALIGNMENT_EXTRA_ID, payload_len) + b"\0" * payload_len


def verify(path, alignment=4):
    failures = []
    with zipfile.ZipFile(path, "r") as archive:
        for info in archive.infolist():
            if info.compress_type != zipfile.ZIP_STORED:
                continue
            data_offset = info.header_offset + 30 + len(info.filename.encode("utf-8")) + len(info.extra)
            if data_offset % alignment:
                failures.append((info.filename, data_offset))
    return failures


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("base_apk")
    parser.add_argument("classes_dex")
    parser.add_argument("output_apk")
    args = parser.parse_args()
    base = Path(args.base_apk)
    dex = Path(args.classes_dex)
    output = Path(args.output_apk)
    output.parent.mkdir(parents=True, exist_ok=True)
    if output.exists():
        output.unlink()

    with zipfile.ZipFile(base, "r") as source, zipfile.ZipFile(
            output, "w", allowZip64=True) as target:
        target.comment = source.comment
        for source_info in source.infolist():
            info = clone_info(source_info)
            add_alignment_extra(info, target.fp.tell())
            target.writestr(info, source.read(source_info.filename), compress_type=info.compress_type)

        dex_info = zipfile.ZipInfo("classes.dex", (1980, 1, 1, 0, 0, 0))
        dex_info.compress_type = zipfile.ZIP_STORED
        dex_info.external_attr = 0o644 << 16
        add_alignment_extra(dex_info, target.fp.tell())
        target.writestr(dex_info, dex.read_bytes(), compress_type=zipfile.ZIP_STORED)

    failures = verify(output)
    if failures:
        raise SystemExit("alignment verification failed: %r" % failures)
    print("Assembled and aligned %s" % output)


if __name__ == "__main__":
    main()
