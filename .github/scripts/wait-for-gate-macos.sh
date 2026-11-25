#!/usr/bin/env bash
# Holds an optional macOS workflow (iOS CI, macOS CI) on a cheap Ubuntu runner until the required
# "CI gate" workflow's macOS jobs for the same commit have picked up runners. The account gets five
# macOS runners at once; when an iOS change opened eight macOS jobs together, the gate's three
# waited 11-14 minutes behind galleries nobody had to wait for. Proceeds anyway after 20 minutes.
#
# Needs GH_TOKEN (actions: read), GITHUB_REPOSITORY and HEAD_SHA.
set -uo pipefail
: "${HEAD_SHA:?}" "${GITHUB_REPOSITORY:?}"
SECONDS=0
while (( SECONDS < 1200 )); do
  run="$(gh api "repos/$GITHUB_REPOSITORY/actions/runs?head_sha=$HEAD_SHA&event=pull_request&per_page=50" \
    -q '[.workflow_runs[] | select(.name == "CI")] | max_by(.run_number) | .id // empty' 2>/dev/null)"
  if [[ -n "$run" ]]; then
    # Jobs on macOS runners that haven't started. Skipped or finished jobs don't count.
    waiting="$(gh api "repos/$GITHUB_REPOSITORY/actions/runs/$run/jobs?per_page=100" -q '
      [.jobs[] | select((.labels | any(startswith("macos"))) and (.status == "queued" or .status == "waiting" or .status == "pending"))] | length' 2>/dev/null)"
    changes="$(gh api "repos/$GITHUB_REPOSITORY/actions/runs/$run/jobs?per_page=100" -q '
      [.jobs[] | select(.name == "Changed areas")][0].status // "missing"' 2>/dev/null)"
    if [[ "$changes" == "completed" && "$waiting" == "0" ]]; then
      echo "The gate's macOS jobs have runners (or were skipped) after ${SECONDS}s"
      exit 0
    fi
    echo "CI run $run: Changed areas $changes, $waiting gate macOS job(s) waiting for a runner"
  else
    echo "No CI run for $HEAD_SHA yet"
  fi
  sleep 15
done
echo "::notice::Stopped waiting for the gate's macOS jobs after 20 minutes"
