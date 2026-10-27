#!/usr/bin/env bash
set -euo pipefail
# Supply an already-built launcher and its flags; this gate never builds or skips tests.
if (( $# == 0 )); then
  echo "usage: $0 LAUNCHER [FLAGS...]" >&2
  exit 2
fi
root="$(cd "$(dirname "$0")/.." && pwd -P)"
work="$(mktemp -d)"
trap 'rm -rf -- "$work"' EXIT
for case in mixed consumed failure; do
  case "$case" in
    mixed) expected=44 ;;
    consumed) expected=22 ;;
    failure) expected=true ;;
  esac
  timeout 120s "$@" "$root/examples/future-pipeline-$case.ores" > "$work/$case.out"
  if [[ "$case" == mixed ]]; then
    printf '%s\n' "$expected" > "$work/expected"
  else
    printf '%s' "$expected" > "$work/expected"
  fi
  cmp -s "$work/expected" "$work/$case.out" || {
    echo "Future pipeline $case: unexpected stdout" >&2
    cat "$work/$case.out" >&2
    exit 1
  }
  echo "future-pipeline-$case: ok"
done
