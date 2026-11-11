#!/usr/bin/env bash
# Exact-source, non-skippable JDK25 JUnit gate for the staged CONTROL ledger.
# Not a live ActorRuntime supervisor dispatch or compiled guest proof.
set -euo pipefail

test_src=src/test/java/dev/oreslang/runtime/ActorSupervisorControlLanePortTest.java
runtime_src=src/main/java/dev/oreslang/runtime/ActorRuntime.java
lane_src=src/main/java/dev/oreslang/runtime/ActorSupervisorControlLane.java
workflow=.github/workflows/supervisor-control-lane-port.yml
report=target/surefire-reports/TEST-dev.oreslang.runtime.ActorSupervisorControlLanePortTest.xml

fail() { printf 'SUPERVISOR_CONTROL_LANE_GATE_REJECTED: %s\n' "$1" >&2; exit 66; }

for path in "$test_src" "$runtime_src" "$lane_src" "$workflow"; do
  [[ -f "$path" && ! -L "$path" ]] || fail "missing or symlinked required source: $path"
done

# The CI workflow must invoke this verifier and must not allow failure,
# skip its Maven invocation, or downgrade the JDK.
grep -Fq 'bash scripts/verify-supervisor-control-lane-port.sh' "$workflow" \
  || fail 'workflow does not invoke the verifier'
grep -Eq "java-version: ['\"]25['\"]" "$workflow" \
  || fail 'workflow is not pinned to JDK25'
if grep -Eq 'continue-on-error:|if:[[:space:]]*false|-DskipTests|-Dmaven.test.skip' "$workflow"; then
  fail 'workflow contains an obvious test skip or soft-fail'
fi
[[ "$(grep -Ec '^[[:space:]]*@Test[[:space:]]*$' "$test_src")" -eq 4 ]] \
  || fail 'expected four JUnit regression methods'
grep -Fq 'private static final Object TRANSFER_COORDINATOR' "$lane_src" \
  || fail 'two-lane transfer arbitration missing'
grep -Fq 'Slot reserved = root.required(slot.child, slot.generation)' "$lane_src" \
  || fail 'whole-transfer root reservation preflight missing'

# A stale XML report from a previous source build cannot satisfy this gate.
rm -f "$report"
mvn -B -ntp -Dores.runtime.carriers=native \
  -Dtest=ActorSupervisorControlLanePortTest \
  -DfailIfNoTests=true test

[[ -s "$report" && ! -L "$report" ]] || fail 'actual Surefire test report missing'
# A method may compile but never run if the selector silently drifts.
head -n 3 "$report" | grep -Eq 'tests="4"' \
  || fail 'Surefire did not execute exactly four methods'
head -n 3 "$report" | grep -Eq 'errors="0"' \
  || fail 'Surefire errors were nonzero'
head -n 3 "$report" | grep -Eq 'failures="0"' \
  || fail 'Surefire failures were nonzero'
head -n 3 "$report" | grep -Eq 'skipped="0"' \
  || fail 'some JUnit cases were skipped'
printf 'SUPERVISOR_CONTROL_LANE_JUNIT_PASS tests=4\n'
