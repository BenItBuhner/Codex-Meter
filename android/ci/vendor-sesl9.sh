#!/usr/bin/env bash
# Copies the SESL9 (One UI 9) artifacts the app build resolves into
# vendor/m2, so builds work offline without GitHub Packages access.
# Needs GH_USERNAME / GH_ACCESS_TOKEN with read:packages (GitHub Packages always requires auth).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CACHE="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1"

echo "Latest published SESL9 versions (for bumping the pins in app/build.gradle.kts):"
grep -oE '"sesl\.[a-z0-9.]+:[a-z0-9-]+"' "$ROOT/app/build.gradle.kts" | tr -d '"' | sort -u |
  while IFS=: read -r group module; do
    url="https://maven.pkg.github.com/tribalfs/oneui-design/${group//.//}/$module/maven-metadata.xml"
    latest="$(curl -fsS -u "$GH_USERNAME:$GH_ACCESS_TOKEN" "$url" 2>/dev/null |
      grep -oE '<version>[^<]*sesl9[^<]*</version>' | sed -E 's/<\/?version>//g' | sort -V | tail -1 || true)"
    echo "  $group:$module -> ${latest:-unknown}"
  done

# Drop old SESL9 copies so Gradle downloads the pinned ones (a vendored POM without its AAR
# would otherwise shadow GitHub Packages), then build so every artifact file is fetched.
find "$ROOT/vendor/m2/sesl" -type d -name '*sesl9*' -prune -exec rm -rf {} +
"$ROOT/gradlew" --project-dir "$ROOT" -q :app:assembleDebug

copied=0
# Copy exactly the pinned versions listed in app/build.gradle.kts.
while IFS='|' read -r group module version; do
  src="$(find "$CACHE/$group/$module/$version" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | head -1)"
  [[ -n "$src" ]] || { echo "missing from Gradle cache: $group:$module:$version" >&2; exit 1; }
  dest="$ROOT/vendor/m2/${group//.//}/$module/$version"
  mkdir -p "$dest"
  find "$CACHE/$group/$module/$version" -type f \( -name '*.aar' -o -name '*.jar' -o -name '*.pom' -o -name '*.module' \) \
    ! -name '*-sources.jar' ! -name '*-javadoc.jar' -exec cp {} "$dest/" \;
  copied=$((copied + 1))
done < <(grep -oE '"sesl\.[a-z0-9.]+:[a-z0-9-]+" to "[^"]+"' "$ROOT/app/build.gradle.kts" |
  sed -E 's/"([^:]+):([^"]+)" to "([^"]+)"/\1|\2|\3/')
echo "Vendored $copied SESL9 module versions into vendor/m2."
