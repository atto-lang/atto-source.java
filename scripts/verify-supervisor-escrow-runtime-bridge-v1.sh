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
verify_blob src/main/java/dev/oreslang/runtime/SupervisorFailureEscrowV1.java d68e0c5066b5842924d398a0c7bbecba382844ff
verify_blob src/main/java/dev/oreslang/runtime/SupervisorFailureEscrowRuntimeBridgeV1.java 8028c94842b960857dc23d060fff296d908163ab
verify_blob src/main/java/dev/oreslang/runtime/ActorRuntime.java 238ecd538341a243d9bf3004b76fa6000f3fb9e2
verify_blob src/main/java/dev/oreslang/runtime/SupervisorEscrowSubtreeRecoveryV1.java fbf508205e0e1544855158067a1cf96f55c4c29f
verify_blob src/test/java/dev/oreslang/runtime/SupervisorFailureEscrowRuntimeIntegrationTest.java ee0c4d9b48fc7664e6985e84e07e2f77a6cec818
verify_blob src/test/java/dev/oreslang/runtime/SupervisorEscrowSubtreeRecoveryIntegrationTest.java 79c8bf1ec6245ff1f45de04accfc6d8832a7a1e5
verify_blob src/test/java/dev/oreslang/runtime/SupervisorEscrowDataAdmissionRaceStandalone.java 4f44466d69b4440400d1d5de533917d1b7257521
verify_blob src/test/java/dev/oreslang/runtime/SupervisorEscrowAdmissionLinearizationIntegrationTest.java 2f7e31ef7bc5050f171dd4ffaee44efe7586071b
verify_blob src/test/java/dev/oreslang/runtime/SupervisorEscrowInheritedChildRaceIntegrationTest.java e04fff95cc27b71fbf978f0a9c0638fbec738257

# The existing dependency-free 557-assertion host lane remains mandatory.
bash scripts/verify-supervisor-failure-escrow-v1.sh

# Compile the actual full ActorRuntime source tree and run real JUnit tests.
mvn -B -q -Dtest=SupervisorFailureEscrowRuntimeIntegrationTest,SupervisorEscrowSubtreeRecoveryIntegrationTest,SupervisorEscrowAdmissionLinearizationIntegrationTest,SupervisorEscrowInheritedChildRaceIntegrationTest -DfailIfNoTests=true test

for spec in 'SupervisorFailureEscrowRuntimeIntegrationTest:3' 'SupervisorEscrowSubtreeRecoveryIntegrationTest:4' 'SupervisorEscrowAdmissionLinearizationIntegrationTest:4' 'SupervisorEscrowInheritedChildRaceIntegrationTest:2'; do
  suite="${spec%%:*}"
  count="${spec##*:}"
  report="target/surefire-reports/TEST-dev.oreslang.runtime.${suite}.xml"
  [[ -s "$report" ]] || { echo "MISSING_SUREFIRE_REPORT: $suite" >&2; exit 3; }
  for metric in "tests=\"$count\"" 'errors="0"' 'failures="0"' 'skipped="0"'; do
    grep -Fq "$metric" "$report" || { echo "BAD_SUREFIRE_METRIC: $suite $metric" >&2; exit 4; }
  done
done
echo 'PASS exact-source supervisor runtime bridge: 13 real ActorRuntime JUnit tests, 0 failed/errored/skipped'
