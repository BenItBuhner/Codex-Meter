#!/usr/bin/env bash
# Copies the SESL9 (One UI 9) artifacts that the One UI 9 preview build resolves into
# vendor/m2, so `-PoneUi9=true` builds work offline like the SESL8 build does.
# Needs GH_USERNAME / GH_ACCESS_TOKEN with read:packages (GitHub Packages always requires auth).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CACHE="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1"

echo "Latest published SESL9 versions (for bumping the pins in app/build.gradle.kts):"
grep -oE '"sesl\.[a-z.]+:[a-z0-9-]+"' "$ROOT/app/build.gradle.kts" | tr -d '"' | sort -u |
  while IFS=: read -r group module; do
    url="https://maven.pkg.github.com/tribalfs/oneui-design/${group//.//}/$module/maven-metadata.xml"
    latest="$(curl -fsS -u "$GH_USERNAME:$GH_ACCESS_TOKEN" "$url" 2>/dev/null |
      grep -oE '<version>[^<]*sesl9[^<]*</version>' | sed -E 's/<\/?version>//g' | sort -V | tail -1 || true)"
    echo "  $group:$module -> ${latest:-unknown}"
  done

"$ROOT/gradlew" --project-dir "$ROOT" -PoneUi9=true -q \
  :app:dependencies --configuration releaseRuntimeClasspath > /dev/null

copied=0
for groupDir in "$CACHE"/sesl.*; do
  group="$(basename "$groupDir")"
  for versionDir in "$groupDir"/*/*sesl9*; do
    [[ -d "$versionDir" ]] || continue
    module="$(basename "$(dirname "$versionDir")")"
    version="$(basename "$versionDir")"
    dest="$ROOT/vendor/m2/${group//.//}/$module/$version"
    mkdir -p "$dest"
    find "$versionDir" -type f \( -name '*.aar' -o -name '*.jar' -o -name '*.pom' -o -name '*.module' \) \
      ! -name '*-sources.jar' ! -name '*-javadoc.jar' -exec cp {} "$dest/" \;
    copied=$((copied + 1))
  done
done
echo "Vendored $copied SESL9 module versions into vendor/m2."
