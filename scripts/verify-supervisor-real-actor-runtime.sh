#!/usr/bin/env bash
# Execute the PRODUCTION Java/Graal supervisor tests, not a test-only runtime shim.
# Must run in the source checkout, or on an independently authorized runner.
# Example: bash scripts/verify-supervisor-real-actor-runtime.sh "$(git rev-parse HEAD)"
set -euo pipefail

if [[ "${1:-}" =~ ^[a-f0-9]{40}$ ]]; then
  expected="$1"
else
  echo "SUPERVISOR_REAL_RUNTIME_DENIED: exact expected 40-hex commit required" >&2
  exit 2
fi
if ! command -v git >/dev/null || ! command -v javac >/dev/null || ! command -v mvn >/dev/null || ! command -v python3 >/dev/null; then
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

# Maven may succeed with a partial test selector or skipped classes. Require
# a positive, unskipped Surefire execution report for EVERY selected class.
python3 - "${tests[@]}" <<'PY_SUPERVISOR_REPORTS'
import glob
import sys
import xml.etree.ElementTree as ET

for simple_name in sys.argv[1:]:
    reports = glob.glob(f"target/surefire-reports/TEST-*.{simple_name}.xml")
    if len(reports) != 1:
        raise SystemExit(
            f"SUPERVISOR_REAL_RUNTIME_DENIED: missing/ambiguous Surefire report for {simple_name}")
    try:
        suite = ET.parse(reports[0]).getroot()
        total = int(suite.get("tests", "0"))
        failed = int(suite.get("failures", "0"))
        errors = int(suite.get("errors", "0"))
        skipped = int(suite.get("skipped", "0"))
    except (ET.ParseError, ValueError) as exc:
        raise SystemExit(
            f"SUPERVISOR_REAL_RUNTIME_DENIED: unreadable Surefire report for {simple_name}: {exc}")
    if total < 1 or failed or errors or skipped:
        raise SystemExit(
            f"SUPERVISOR_REAL_RUNTIME_DENIED: incomplete {simple_name} tests={total} "
            f"failures={failed} errors={errors} skipped={skipped}")
    print(f"SUPERVISOR_REAL_RUNTIME_CLASS=PASS class={simple_name} tests={total}")
PY_SUPERVISOR_REPORTS

# This marker may only be printed after the actual Maven process exits zero.
echo "SUPERVISOR_REAL_ACTOR_RUNTIME_EXACT_HEAD=PASS sha=$actual tests=${#tests[@]}"
