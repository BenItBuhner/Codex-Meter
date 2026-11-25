#!/usr/bin/env bash
# Runs a command in the background of a CI job, so it can overlap later steps (such as a cache
# restore), and waits on it later with its output and exit status.
#
#   android/ci/bg.sh start <name> <command...>
#   android/ci/bg.sh wait <name>
set -euo pipefail
DIR="${RUNNER_TEMP:-/tmp}/bg"
mkdir -p "$DIR"
action="${1:?start or wait}"; name="${2:?name}"; shift 2
case "$action" in
  start)
    rm -f "$DIR/$name.status"
    nohup bash -c '"$@" > "$0.log" 2>&1; echo $? > "$0.status"' "$DIR/$name" "$@" >/dev/null 2>&1 &
    echo "Started $name in the background"
    ;;
  wait)
    SECONDS=0
    until [[ -e "$DIR/$name.status" ]]; do sleep 1; done
    echo "Waited ${SECONDS}s for $name"
    cat "$DIR/$name.log"
    exit "$(cat "$DIR/$name.status")"
    ;;
  *) echo "usage: $0 start|wait <name> [command...]" >&2; exit 2 ;;
esac
