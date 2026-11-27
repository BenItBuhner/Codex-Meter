#!/usr/bin/env bash
# Decides whether a release build can skip the tests its commit already passed.
#
# Release tags point at a commit that was pushed to alpha (or main) first, where the required CI
# gate ran every check on that exact commit. Re-running the unit tests, UI tests, screenshot runs
# and galleries on the tag only repeats them, so a release build whose commit passed the given
# push workflows just builds, packages and launch-checks the files it ships. Anything else (an
# ordinary CI run, no passing push run for the commit, a failed one) keeps every check.
#
#   .github/scripts/release-tested.sh [workflow file...]    (default: ci.yml)
#
# Writes full=true|false to $GITHUB_OUTPUT. Waits (up to RELEASE_TESTED_WAIT seconds, default
# 1500) while a run of a listed workflow for the commit is still going. Needs GH_TOKEN
# (actions: read), GITHUB_REPOSITORY and RELEASE_TAG (empty for an ordinary CI run); the commit
# is SHA, or HEAD of the checkout.
set -uo pipefail
: "${GITHUB_REPOSITORY:?}"
WORKFLOWS=("$@")
(( ${#WORKFLOWS[@]} )) || WORKFLOWS=(ci.yml)
LIMIT="${RELEASE_TESTED_WAIT:-1500}"

decide() {
  echo "$2"
  [[ -n "${GITHUB_OUTPUT:-}" ]] && echo "full=$1" >> "$GITHUB_OUTPUT"
  if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
    echo "**Checks:** $2" >> "$GITHUB_STEP_SUMMARY"
  fi
  exit 0
}

[[ -n "${RELEASE_TAG:-}" ]] || decide true "Not a release build: running every check."
sha="${SHA:-$(git rev-parse HEAD 2>/dev/null)}"
[[ -n "$sha" ]] || decide true "No commit to look up: running every check."

SECONDS=0
while :; do
  pending=()
  for wf in "${WORKFLOWS[@]}"; do
    # Newest push run of this workflow for the commit: success, failure, or still going.
    state="$(gh api "repos/$GITHUB_REPOSITORY/actions/workflows/$wf/runs?head_sha=$sha&event=push&per_page=20" \
      -q '[.workflow_runs[] | select(.head_branch == "alpha" or .head_branch == "main")]
          | if any(.conclusion == "success") then "success"
            elif any(.status != "completed") then "running"
            elif length == 0 then "none" else "failed" end' 2>/dev/null)"
    case "$state" in
      success) ;;
      running) pending+=("$wf") ;;
      *) decide true "$wf has no passing push run for ${sha:0:7} (${state:-lookup failed}): running every check." ;;
    esac
  done
  (( ${#pending[@]} )) || decide false "${sha:0:7} already passed ${WORKFLOWS[*]} on its push: building, packaging and launch-checking the release files only."
  if (( SECONDS >= LIMIT )); then
    decide true "${pending[*]} still running for ${sha:0:7} after ${SECONDS}s: running every check."
  fi
  echo "Waiting for ${pending[*]} on ${sha:0:7} (${SECONDS}s)"
  sleep 20
done
