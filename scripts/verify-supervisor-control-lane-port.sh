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
[[ "$(grep -Ec '^[[:space:]]*@Test[[:space:]]*$' "$test_src")" -eq 6 ]] \
  || fail 'expected six JUnit regression methods'
grep -Fq 'private static final Object TRANSFER_COORDINATOR' "$lane_src" \
  || fail 'two-lane transfer arbitration missing'
grep -Fq 'Slot reserved = root.required(slot.child, slot.generation)' "$lane_src" \
  || fail 'whole-transfer root reservation preflight missing'
grep -Fq 'private Slot requiredDelivery(Delivery delivery)' "$lane_src" \
  || fail 'opaque per-poll settlement authority missing'

# A stale XML report from a previous source build cannot satisfy this gate.
rm -f "$report"
mvn -B -ntp -Dores.runtime.carriers=native \
  -Dtest=ActorSupervisorControlLanePortTest \
  -DfailIfNoTests=true test

[[ -s "$report" && ! -L "$report" ]] || fail 'actual Surefire test report missing'
# Parse actual testcase nodes rather than trusting the testsuite summary.
# Exact expected methods prevent a false green from a selected empty/other suite.
python3 -I -B - "$report" <<'PY'
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

path = Path(sys.argv[1])
root = ET.parse(path).getroot()
expected = {
    "missingSecondRootReservationRejectsWholeTransferWithoutPartialMove",
    "staleOrOccupiedRootReservationNeverConsumesParentNotice",
    "transferredInFlightNoticeCannotBeDroppedByParentAck",
    "opposingTransfersDoNotDeadlockAndRetainEveryNotice",
    "staleAndForeignSettlementLeasesCannotAckANewerDelivery",
    "deliveryTicketOverflowDoesNotDequeueOrLoseTheControlNotice",
}
if root.tag != "testsuite":
    raise SystemExit("SUPERVISOR_CONTROL_LANE_GATE_REJECTED: wrong XML root")
for field, count in (
    ("tests", len(expected)), ("errors", 0), ("failures", 0), ("skipped", 0)
):
    if root.get(field) != str(count):
        raise SystemExit("SUPERVISOR_CONTROL_LANE_GATE_REJECTED: invalid " + field)
cases = root.findall("testcase")
names = [case.get("name") for case in cases]
if len(names) != len(expected) or set(names) != expected:
    raise SystemExit("SUPERVISOR_CONTROL_LANE_GATE_REJECTED: missing/duplicate JUnit method")
if any(case.find("failure") is not None or
       case.find("error") is not None or
       case.find("skipped") is not None for case in cases):
    raise SystemExit("SUPERVISOR_CONTROL_LANE_GATE_REJECTED: skipped or failed JUnit method")
print("SUPERVISOR_CONTROL_LANE_JUNIT_PASS tests=6")
PY
