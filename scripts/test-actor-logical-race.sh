#!/usr/bin/env bash
# Validates the CLI's bounded reference-model race analysis. This is *not*
# instrumented Oreslang guest execution and never proves data-race freedom.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
shim="${ORESLANG_TEST_SHIM:-oreslang}"
if ! command -v "$shim" >/dev/null 2>&1; then
  echo "oreslang test shim is not installed (set ORESLANG_TEST_SHIM); no race model ran" >&2
  exit 2
fi

"$shim" test --root "$root" --race --logical \
  --model tests/models/actor-atomic-counter.json --max-schedules 1000

# A known stale-read model MUST be reported as a logical race (exit 1),
# never as a success (exit 0) or invalid/inconclusive check (exit 2).
set +e
"$shim" test --root "$root" --race --logical \
  --model tests/models/actor-stale-read-counter.json --max-schedules 1000
result=$?
set -e
if [[ "$result" -ne 1 ]]; then
  echo "logical race negative-control expected exit=1, got exit=$result" >&2
  exit 2
fi
echo "Logical race negative-control detected correctly (model evidence only)"
