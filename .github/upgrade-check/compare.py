#!/usr/bin/env python3
"""Compares what the old build (v2.8.0) and the upgraded build read from the same app data.

usage: compare.py before.json after.json [summary.md]

Fails (exit 1) when sign-in, usage, history, reset credits or any setting reads differently after
the upgrade, or when a preferences key the old build wrote is gone. Keys the new build drops on
purpose go in DROPPED_ON_PURPOSE with the reason.
"""
import json
import sys

# file -> {key: reason}. Only keys the new build removes deliberately.
DROPPED_ON_PURPOSE = {}

SECTIONS = ["auth", "onboarding_complete", "usage", "history", "reset_credits", "settings"]


def main():
    before = json.load(open(sys.argv[1]))
    after = json.load(open(sys.argv[2]))
    summary = sys.argv[3] if len(sys.argv) > 3 else None
    problems, rows = [], []

    # The seed must actually have produced data, or the check proves nothing.
    if not before["auth"].get("signed_in"):
        problems.append("seed: the old build is not signed in")
    if not before["usage"].get("present"):
        problems.append("seed: the old build has no saved usage")
    if len(before["history"].get("five_hour", [])) < 2:
        problems.append("seed: the old build has fewer than 2 five-hour history samples")
    if not before["reset_credits"].get("present"):
        problems.append("seed: the old build has no saved reset credits")

    def walk(path, a, b):
        if isinstance(a, dict) and isinstance(b, dict):
            for k in sorted(set(a) | set(b)):
                walk(path + [k], a.get(k, "<missing>"), b.get(k, "<missing>"))
            return
        ok = a == b
        rows.append((".".join(path), a, b, ok))
        if not ok:
            problems.append(f"{'.'.join(path)}: 2.8.0 read {a!r}, upgraded build reads {b!r}")

    for section in SECTIONS:
        walk([section], before.get(section), after.get(section))

    for name, keys in before.get("raw", {}).items():
        kept = after.get("raw", {}).get(name, {})
        for key in keys:
            if key in kept:
                continue
            reason = DROPPED_ON_PURPOSE.get(name, {}).get(key)
            if reason:
                rows.append((f"raw.{name}.{key}", "present", f"dropped: {reason}", True))
            else:
                problems.append(f"raw.{name}.{key}: written by 2.8.0, gone after the upgrade")
                rows.append((f"raw.{name}.{key}", "present", "<missing>", False))

    if summary:
        with open(summary, "a") as out:
            out.write("## 2.8.0 to current upgrade check\n\n")
            out.write(f"{sum(1 for r in rows if r[3])} of {len(rows)} values kept.\n\n")
            if problems:
                out.write("### Problems\n\n" + "".join(f"- {p}\n" for p in problems) + "\n")
            out.write("| Value | 2.8.0 | Upgraded |\n|---|---|---|\n")
            for path, a, b, ok in rows:
                mark = "" if ok else " ❌"
                out.write(f"| `{path}`{mark} | `{str(a)[:80]}` | `{str(b)[:80]}` |\n")

    for p in problems:
        print("::error::" + p)
    print(f"{len(rows)} values compared, {len(problems)} problems")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
