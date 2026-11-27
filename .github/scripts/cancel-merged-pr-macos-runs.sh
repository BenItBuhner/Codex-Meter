#!/usr/bin/env bash
# Frees macOS runners for the CI gate on a push to alpha or main. The account gets five macOS
# runners at once, and a pull request's optional iOS CI and macOS CI runs (galleries, UI suites,
# ~35 minutes) keep going after the pull request merges. On the alpha.37 push they held the
# runners and the gate's three macOS jobs queued for 6.5 minutes. This cancels those runs, but
# only for pull requests that are already merged: their results can no longer change anything.
# Open pull requests' runs are left alone.
#
# Needs GH_TOKEN (actions: write, pull-requests: read) and GITHUB_REPOSITORY.
set -uo pipefail
: "${GITHUB_REPOSITORY:?}"
R="$GITHUB_REPOSITORY"
for workflow in ios-ci.yml macos-ci.yml; do
  for status in queued in_progress waiting; do
    gh api "repos/$R/actions/workflows/$workflow/runs?event=pull_request&status=$status&per_page=50" \
      -q '.workflow_runs[] | "\(.id) \(.head_sha)"' 2>/dev/null
  done | sort -u | while read -r run sha; do
    [[ -n "$run" ]] || continue
    merged="$(gh api "repos/$R/commits/$sha/pulls" \
      -q "[.[] | select(.merged_at != null and .head.sha == \"$sha\") | .number] | first // empty" 2>/dev/null)"
    if [[ -n "$merged" ]]; then
      echo "Cancelling $workflow run $run: pull request #$merged is already merged"
      gh api -X POST "repos/$R/actions/runs/$run/cancel" >/dev/null 2>&1 || echo "  (could not cancel $run)"
    fi
  done
done
echo "Done"
