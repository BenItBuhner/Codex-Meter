#!/usr/bin/env bash
# Prints the PR's payload table as Markdown: each wire payload's worst-case size on main and on
# this PR, the change, and the ceiling the payload test enforces.
# Usage: payload.sh PR_CHECKOUT MAIN_CHECKOUT JSON_JAR
# Both trees are measured with this PR's PayloadBudgetSelfTest and fixtures, compiled against each
# tree's own sources, so the numbers compare like with like. A tree that can't build the test (main
# before the test existed) shows a dash.
set -uo pipefail
PR="$1"; MAIN="$2"; JSON_JAR="$3"
TEST="$PR/android/tests/PayloadBudgetSelfTest.java"
FIXTURES="$PR/android/tests/fixtures"

measure() {
  local tree="$1" out
  out="$(mktemp -d)"
  if javac -encoding UTF-8 -nowarn -cp "$JSON_JAR" -d "$out" \
      -sourcepath "$tree/android/shared/src/main/java:$tree/android/app/src/main/java" "$TEST" 2>/dev/null; then
    java -ea -Dcodex.fixtures="$FIXTURES" -cp "$out:$JSON_JAR" dev.bennett.codexmeter.PayloadBudgetSelfTest 2>/dev/null \
      | awk '$1 == "PAYLOAD" { print $2, $3, $4 }'
  fi
  rm -rf "$out"
}

declare -A main_bytes
while read -r name bytes _; do main_bytes[$name]=$bytes; done < <(measure "$MAIN")
pr_rows="$(measure "$PR")"
if [[ -z "$pr_rows" ]]; then
  echo "::error::PayloadBudgetSelfTest did not run on this PR" >&2
  exit 1
fi

echo "| Payload | Main | This PR | Impact | Ceiling |"
echo "|---|---:|---:|---:|---:|"
while read -r name bytes ceiling; do
  base="${main_bytes[$name]:-}"
  if [[ -n "$base" ]]; then
    delta=$((bytes - base))
    sign=""; (( delta > 0 )) && sign="+"
    impact="${sign}${delta} B"
    base="$base B"
  else
    base="—"; impact="—"
  fi
  echo "| \`$name\` | $base | $bytes B | $impact | $ceiling B |"
done <<< "$pr_rows"
