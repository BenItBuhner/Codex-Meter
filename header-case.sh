#!/usr/bin/env bash
# Headers keep the case they are written in, on every platform and design: Settings → Appearance
# reads "Theme", "Design", "Liquid Glass", never "THEME". This fails CI if app code forces text
# into capitals again, or if an Apple app root loses the `.textCase(nil)` that stops SwiftUI from
# upper-casing List and Form section headers. Write a header the way it should read instead.
#
# Allowed: capitalizing only a first letter ("weekly" → "Weekly"), and the files listed in ALLOWED
# below, which upper-case API values or path commands, not text a person reads as a header.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"
fail=0
bad() { echo "header-case: $*" >&2; fail=1; }

# Ways to force capitals: whole-string upper-casing, Android allCaps, SwiftUI textCase, WPF and
# Avalonia capitals typography, CSS text-transform.
FORCED='\.toUpperCase\(|\.uppercase\(\)|\.uppercased\(\)|\.ToUpper(Invariant)?\(\)|\.upper\(\)|setAllCaps\(true\)|textAllCaps="true"|textAllCaps">true|textCase\(\.uppercase\)|Typography\.Capitals="(All)?(Small|Petite)?Caps|Capitals="AllCaps"|text-transform: *uppercase|TextTransform="Uppercase"'
# Upper-casing one character (a first letter, a path command) is not a header in capitals.
ONE_CHAR='(substring\(0, *1\)|charAt\(0\)|prefix\(1\)|first|\[\.\.1\]|\[0\]|char)\.?(toUpperCase|uppercased|ToUpperInvariant|ToUpper)|Character\.toUpperCase|char\.ToUpper'

# Files that upper-case values to match an API or a shape, never a header:
ALLOWED=(
  android/app/src/main/java/dev/bennett/codexmeter/ProviderUsageParsers.java   # plan tiers and units from provider APIs
  ios/CodexMeterCore/Sources/CodexMeterCore/ProviderUsageParsers.swift         # the same, iOS
  windows/src/CodexMeter.Core/Parsing/ProviderUsageParsers.cs                  # the same, Windows and Linux
  windows/tools/gen_provider_icons.py                                          # SVG path commands
  # Home and lock screen widget tags ("CODEX", "SIGN IN") are a widget design of their own, not headers.
  android/app/src/main/java/dev/bennett/codexmeter/SamsungLockWidgetSupport.java
  android/app/src/main/java/dev/bennett/codexmeter/WidgetProviders.java
  android/app/src/main/java/dev/bennett/codexmeter/WidgetRenderer.java
)
is_allowed() { local f; for f in "${ALLOWED[@]}"; do [[ "$1" == "$f" ]] && return 0; done; return 1; }

while IFS= read -r file; do
  is_allowed "$file" && continue
  while IFS= read -r hit; do
    line="${hit#*:}"
    grep -Eq -- "$ONE_CHAR" <<< "$line" && continue
    bad "$file:${hit%%:*} forces capitals: $(sed -E 's/^[[:space:]]+//' <<< "$line" | cut -c1-120)"
  done < <(grep -nE -- "$FORCED" "$file" || true)
done < <(git ls-files 'android/app/src/main' 'android/glass/src/main' 'android/wear/src/main' \
           'android/shared/src/main' 'android/oneui9compat/src/main' 'ios' 'macos' 'windows/src' 'linux/src' 'windows/tools' \
         | grep -E '\.(java|kt|xml|swift|cs|xaml|axaml|py|css|html)$' | grep -vE '/(Tests?|UITests|tests?)/|Tests?\.(swift|cs|java|kt)$')

# SwiftUI upper-cases List and Form section headers on its own: every app root turns that off.
for root in ios/CodexMeter/CodexMeterApp.swift ios/CodexMeterWatch/CodexMeterWatchApp.swift \
            macos/CodexMeterMac/CodexMeterMacApp.swift; do
  grep -Fq '.textCase(nil)' "$root" || bad "$root: the app root lost .textCase(nil), so SwiftUI section headers turn to capitals"
done

if [[ "$fail" != 0 ]]; then
  echo 'header-case: headers keep the case they are written in ("Appearance", never "APPEARANCE"). Drop the forced capitals.' >&2
  exit 1
fi
echo "header-case: no forced capitals in headers"
