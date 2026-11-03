#!/usr/bin/env bash
# Execute the PRODUCTION Java/Graal supervisor tests, not a test-only runtime shim.
# Must run in the source checkout, or on an independently authorized runner.
# Example: bash scripts/verify-supervisor-real-actor-runtime.sh "$(git rev-parse HEAD)"
set -euo pipefail

if [[ "${1:-}" != "" && "${#1}" -eq 40 && "$1" =~ ^[[:xdigit:]]+$ ]]; then
  expected="$1"
else
  echo "SUPERVISOR_REAL_RUNTIME_DENIED: exact expected 40-hex commit required" >&2
  exit 2
fi
if ! command -v git >/dev/null || ! command -v javac >/dev/null || ! command -v mvn >/dev/null; then
  echo "SUPERVISOR_REAL_RUNTIME_DENIED: Git, JDK and Maven are required" >&2
  exit 2
fi
cd "$(git rev-parse --show-toplevel)"
actual="$(git rev-parse --verify HEAD)"
if [[ "$actual" != "$expected" ]]; then
  echo "SUPERVISOR_REAL_RUNTIME_DENIED: checkout is not the approved exact head" >&2
  exit 2
fi
if ! git diff --quiet HEAD -- || ! git diff --cached --quiet; then
  echo "SUPERVISOR_REAL_RUNTIME_DENIED: tracked source has uncommitted changes" >&2
  exit 2
fi
if ! javac -version 2>&1 | grep -Eq '^javac 25([.+-]|$)'; then
  echo "SUPERVISOR_REAL_RUNTIME_DENIED: JDK 25 required for runtime proof" >&2
  exit 2
fi

# Full, real ActorRuntime and compiler source tests. Intentionally require
# all selected test classes to be found, not a successful zero-step workflow.
tests=(
  SourceSupervisorAwaitStopTest
  SourceSupervisorStopLoweringTest
  SourceSupervisorTypeBoundaryTest
  SupervisorChildRegistryTest
  SupervisorDecisionCommitFenceTest
  SupervisorControlLivenessTest
  SupervisorFailureControlTest
  SupervisorHardeningTest
  SupervisorProcessTest
  SupervisorStopOnlyControlProofTest
)
selector="$(IFS=,; echo "${tests[*]}")"
mvn -B -ntp -Dores.runtime.carriers=native \
  -Dsurefire.failIfNoSpecifiedTests=true \
  "-Dtest=$selector" test

# This marker may only be printed after the actual Maven process exits zero.
echo "SUPERVISOR_REAL_ACTOR_RUNTIME_EXACT_HEAD=PASS sha=$actual tests=${#tests[@]}"
