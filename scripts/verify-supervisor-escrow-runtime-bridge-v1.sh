#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

verify_blob() {
  local path="$1" expected="$2" actual
  actual="$(git hash-object "$path")"
  if [[ "$actual" != "$expected" ]]; then
    printf 'BLOB_MISMATCH %s expected=%s got=%s\n' "$path" "$expected" "$actual" >&2
    exit 2
  fi
}
verify_blob src/main/java/dev/oreslang/runtime/SupervisorFailureEscrowV1.java 3cab55b6afcd01377d6f59adf232c29e6f74deaf
verify_blob src/main/java/dev/oreslang/runtime/SupervisorFailureEscrowRuntimeBridgeV1.java 5804f70c1e00920c1e09c40693975c35d36ef034
verify_blob src/main/java/dev/oreslang/runtime/ActorRuntime.java 58362d91aee2edfb62547ead4dfa5c4f6addb008
verify_blob src/main/java/dev/oreslang/runtime/SupervisorEscrowSubtreeRecoveryV1.java 223a817d2249755445904c752b7d75565a4f2ae1
verify_blob src/test/java/dev/oreslang/runtime/SupervisorFailureEscrowRuntimeIntegrationTest.java ee0c4d9b48fc7664e6985e84e07e2f77a6cec818
verify_blob src/test/java/dev/oreslang/runtime/SupervisorEscrowSubtreeRecoveryIntegrationTest.java 79c8bf1ec6245ff1f45de04accfc6d8832a7a1e5
verify_blob src/test/java/dev/oreslang/runtime/SupervisorEscrowDataAdmissionRaceStandalone.java 9eb87ba39008840c7a4704e561b2fd94a7dd1fbd
verify_blob src/test/java/dev/oreslang/runtime/SupervisorEscrowAdmissionLinearizationIntegrationTest.java d0298b90f0c2f12e213bc25bd43bf682b9497280

# The existing dependency-free 557-assertion host lane remains mandatory.
bash scripts/verify-supervisor-failure-escrow-v1.sh

# Compile the actual full ActorRuntime source tree and run real JUnit tests.
mvn -B -q -Dtest=SupervisorFailureEscrowRuntimeIntegrationTest,SupervisorEscrowSubtreeRecoveryIntegrationTest,SupervisorEscrowAdmissionLinearizationIntegrationTest -DfailIfNoTests=true test

for spec in 'SupervisorFailureEscrowRuntimeIntegrationTest:3' 'SupervisorEscrowSubtreeRecoveryIntegrationTest:4' 'SupervisorEscrowAdmissionLinearizationIntegrationTest:5'; do
  suite="${spec%%:*}"
  count="${spec##*:}"
  report="target/surefire-reports/TEST-dev.oreslang.runtime.${suite}.xml"
  [[ -s "$report" ]] || { echo "MISSING_SUREFIRE_REPORT: $suite" >&2; exit 3; }
  for metric in "tests=\"$count\"" 'errors="0"' 'failures="0"' 'skipped="0"'; do
    grep -Fq "$metric" "$report" || { echo "BAD_SUREFIRE_METRIC: $suite $metric" >&2; exit 4; }
  done
done
echo 'PASS exact-source supervisor runtime bridge: 12 real ActorRuntime JUnit tests, 0 failed/errored/skipped'
