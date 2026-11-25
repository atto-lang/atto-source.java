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
verify_blob src/main/java/dev/oreslang/runtime/SupervisorFailureEscrowV1.java 09d58a348fd5a2de4ab025f1f1ede458a5a0041d
verify_blob src/main/java/dev/oreslang/runtime/SupervisorFailureEscrowRuntimeBridgeV1.java 472b6b3baaa8feeb31be46b69b7574be7d544bd1
verify_blob src/main/java/dev/oreslang/runtime/ActorRuntime.java 815e1ab2a11c356bdb1f0a39c17b2d8b52813f84
verify_blob src/main/java/dev/oreslang/runtime/SupervisorEscrowSubtreeRecoveryV1.java d8522a059af3b4afefd69f8ea10f1d7a6d77c389
verify_blob src/test/java/dev/oreslang/runtime/SupervisorFailureEscrowRuntimeIntegrationTest.java ee0c4d9b48fc7664e6985e84e07e2f77a6cec818
verify_blob src/test/java/dev/oreslang/runtime/SupervisorEscrowSubtreeRecoveryIntegrationTest.java 79c8bf1ec6245ff1f45de04accfc6d8832a7a1e5
verify_blob src/test/java/dev/oreslang/runtime/SupervisorEscrowDataAdmissionRaceStandalone.java 4f44466d69b4440400d1d5de533917d1b7257521
verify_blob src/test/java/dev/oreslang/runtime/SupervisorEscrowAdmissionLinearizationIntegrationTest.java b1198abedece2d0783617749208024a8cab9adbd

# The existing dependency-free 557-assertion host lane remains mandatory.
bash scripts/verify-supervisor-failure-escrow-v1.sh

# Compile the actual full ActorRuntime source tree and run real JUnit tests.
mvn -B -q -Dtest=SupervisorFailureEscrowRuntimeIntegrationTest,SupervisorEscrowSubtreeRecoveryIntegrationTest,SupervisorEscrowAdmissionLinearizationIntegrationTest -DfailIfNoTests=true test

for spec in 'SupervisorFailureEscrowRuntimeIntegrationTest:3' 'SupervisorEscrowSubtreeRecoveryIntegrationTest:4' 'SupervisorEscrowAdmissionLinearizationIntegrationTest:3'; do
  suite="${spec%%:*}"
  count="${spec##*:}"
  report="target/surefire-reports/TEST-dev.oreslang.runtime.${suite}.xml"
  [[ -s "$report" ]] || { echo "MISSING_SUREFIRE_REPORT: $suite" >&2; exit 3; }
  for metric in "tests=\"$count\"" 'errors="0"' 'failures="0"' 'skipped="0"'; do
    grep -Fq "$metric" "$report" || { echo "BAD_SUREFIRE_METRIC: $suite $metric" >&2; exit 4; }
  done
done
echo 'PASS exact-source supervisor runtime bridge: 10 real ActorRuntime JUnit tests, 0 failed/errored/skipped'
