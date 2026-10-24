#!/usr/bin/env bash
# Regression tests for private CI policy denial. No GitHub credentials or transfers.
set -euo pipefail
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
policy="$root/.github/private-ci-approved-repositories.txt"
[[ -f "$policy" && ! -L "$policy" ]] || { echo "FAIL: missing or symlinked private CI policy" >&2; exit 1; }
# This is a current-policy sentinel, not a permanent prohibition on approved destinations.
if grep -Eq '^[[:space:]]*[^#[:space:]]' "$policy"; then
  echo "FAIL: destination entries require independent approval and an updated regression fixture" >&2
  exit 1
fi
echo "PASS: no authorized destination in current policy"
bash "$root/scripts/test-private-ci-destination.sh"
echo "PASS: private CI destination policy regression suite"
