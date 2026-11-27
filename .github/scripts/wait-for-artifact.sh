#!/usr/bin/env bash
# Waits until another job of this workflow run has uploaded an artifact, so a job can start its
# slow setup (booting a simulator) before the build it needs is done, without `needs:` holding the
# whole job back. Fails at once if the producing job ends without uploading it.
#
#   .github/scripts/wait-for-artifact.sh ARTIFACT "Producing job name" [timeout seconds]
#
# Needs GH_TOKEN (actions: read), GITHUB_REPOSITORY and GITHUB_RUN_ID.
set -uo pipefail
NAME="${1:?artifact name}"
JOB="${2:?producing job name}"
LIMIT="${3:-1200}"
: "${GITHUB_REPOSITORY:?}" "${GITHUB_RUN_ID:?}"
API="repos/$GITHUB_REPOSITORY/actions/runs/$GITHUB_RUN_ID"
SECONDS=0
while (( SECONDS < LIMIT )); do
  found="$(gh api "$API/artifacts?name=$NAME" -q '.total_count' 2>/dev/null)"
  if [[ "${found:-0}" != 0 ]]; then
    echo "$NAME is ready after ${SECONDS}s"
    exit 0
  fi
  state="$(gh api "$API/attempts/${GITHUB_RUN_ATTEMPT:-1}/jobs?per_page=100" \
    -q "[.jobs[] | select(.name == \"$JOB\")][0] | \"\(.status) \(.conclusion)\"" 2>/dev/null)"
  if [[ "$state" == completed* ]]; then
    # One more look: the upload may have landed just before the job finished.
    found="$(gh api "$API/artifacts?name=$NAME" -q '.total_count' 2>/dev/null)"
    [[ "${found:-0}" != 0 ]] && { echo "$NAME is ready after ${SECONDS}s"; exit 0; }
    echo "::error::$JOB finished (${state#completed }) without uploading $NAME" >&2
    exit 1
  fi
  sleep 10
done
echo "::error::$NAME did not appear within ${LIMIT}s" >&2
exit 1
