#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

actual_head="$(git rev-parse HEAD)"
if [[ -n "${EXPECTED_HEAD:-}" && "$actual_head" != "$EXPECTED_HEAD" ]]; then
  printf 'WRONG_TESTED_HEAD expected=%s actual=%s\n' "$EXPECTED_HEAD" "$actual_head" >&2
  exit 5
fi
printf 'SUPERVISOR_TESTED_COMMIT=%s\n' "$actual_head"
java -version 2>&1 | head -n 3
javac -version 2>&1
mvn -version

verify_blob() {
  local path="$1" expected="$2" actual
  actual="$(git hash-object "$path")"
  if [[ "$actual" != "$expected" ]]; then
    printf 'BLOB_MISMATCH %s expected=%s got=%s\n' "$path" "$expected" "$actual" >&2
    exit 2
  fi
}
verify_blob src/main/java/dev/oreslang/runtime/SupervisorFailureEscrowV1.java d68e0c5066b5842924d398a0c7bbecba382844ff
verify_blob src/main/java/dev/oreslang/runtime/SupervisorFailureEscrowRuntimeBridgeV1.java 8509358b7646db6a2efae76dfbcddee347617c36
verify_blob src/main/java/dev/oreslang/runtime/ActorRuntime.java b8ae572328743ecedc1be7206f0a5b5608782e19
verify_blob src/main/java/dev/oreslang/runtime/SupervisorEscrowSubtreeRecoveryV1.java 9f5a7f863484e23fbab47e8a549765a8ea0bb14d
verify_blob src/test/java/dev/oreslang/runtime/SupervisorFailureEscrowRuntimeIntegrationTest.java 86e8bf385165c194355d151275bb7738571db9ae
verify_blob src/test/java/dev/oreslang/runtime/SupervisorEscrowSubtreeRecoveryIntegrationTest.java 79c8bf1ec6245ff1f45de04accfc6d8832a7a1e5
verify_blob src/test/java/dev/oreslang/runtime/SupervisorEscrowDataAdmissionRaceStandalone.java 0d8e5a4eba9eff407b4c610ce09f1e2a1cd827d0
verify_blob src/test/java/dev/oreslang/runtime/SupervisorEscrowAdmissionLinearizationIntegrationTest.java a2449b5b96b214f3e5417c4f47e923dbeee8e6dc
verify_blob src/test/java/dev/oreslang/runtime/SupervisorEscrowInheritedChildRaceIntegrationTest.java 484c70422b5b59e77e5482d3b23dc75257fe0294

# The existing dependency-free 557-assertion host lane remains mandatory.
bash scripts/verify-supervisor-failure-escrow-v1.sh

# Compile the actual full ActorRuntime source tree and run real JUnit tests.
mvn -B -q -Dtest=SupervisorFailureEscrowRuntimeIntegrationTest,SupervisorEscrowSubtreeRecoveryIntegrationTest,SupervisorEscrowAdmissionLinearizationIntegrationTest,SupervisorEscrowInheritedChildRaceIntegrationTest -DfailIfNoTests=true test

for spec in 'SupervisorFailureEscrowRuntimeIntegrationTest:4' 'SupervisorEscrowSubtreeRecoveryIntegrationTest:4' 'SupervisorEscrowAdmissionLinearizationIntegrationTest:4' 'SupervisorEscrowInheritedChildRaceIntegrationTest:7'; do
  suite="${spec%%:*}"
  count="${spec##*:}"
  report="target/surefire-reports/TEST-dev.oreslang.runtime.${suite}.xml"
  [[ -s "$report" ]] || { echo "MISSING_SUREFIRE_REPORT: $suite" >&2; exit 3; }
  for metric in "tests=\"$count\"" 'errors="0"' 'failures="0"' 'skipped="0"'; do
    grep -Fq "$metric" "$report" || { echo "BAD_SUREFIRE_METRIC: $suite $metric" >&2; exit 4; }
  done
done
echo 'PASS exact-source supervisor runtime bridge: 19 real ActorRuntime JUnit tests, 0 failed/errored/skipped'
test "$(git rev-parse HEAD)" = "$actual_head" || {
  echo 'GIT_HEAD_CHANGED_DURING_TEST' >&2; exit 6;
}
printf 'SUPERVISOR_VERIFIED_EXACT_HEAD=%s\n' "$actual_head"
