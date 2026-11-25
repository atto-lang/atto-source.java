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
verify_blob src/main/java/dev/oreslang/runtime/SupervisorFailureEscrowV1.java 6934a266090a16361154a7fa24859d99c46a6470
verify_blob src/main/java/dev/oreslang/runtime/SupervisorFailureEscrowRuntimeBridgeV1.java 55f4feb55c4892c43612dbcfffe1127666299750
verify_blob src/main/java/dev/oreslang/runtime/ActorRuntime.java 0760e04d1ccc57828317aebe9416324e08185b34
verify_blob src/main/java/dev/oreslang/runtime/SupervisorEscrowSubtreeRecoveryV1.java 47ad87bb8112b5b7adf3512e6158598892ff203e
verify_blob src/test/java/dev/oreslang/runtime/SupervisorFailureEscrowRuntimeIntegrationTest.java ee0c4d9b48fc7664e6985e84e07e2f77a6cec818
verify_blob src/test/java/dev/oreslang/runtime/SupervisorEscrowSubtreeRecoveryIntegrationTest.java b5236a806e1047aa7fca0d7bf8c283c3542e8c21

verify_blob src/main/java/dev/oreslang/runtime/ActorSupervisorRestartCircuit.java d553dd40c160cd20c3e711827eb2e489c32b3aaa

# The existing dependency-free 557-assertion host lane remains mandatory.
bash scripts/verify-supervisor-failure-escrow-v1.sh

# Compile the actual full ActorRuntime source tree and run real JUnit tests.
mvn -B -q -Dtest=SupervisorFailureEscrowRuntimeIntegrationTest,SupervisorEscrowSubtreeRecoveryIntegrationTest -DfailIfNoTests=true test

for spec in 'SupervisorFailureEscrowRuntimeIntegrationTest:3' 'SupervisorEscrowSubtreeRecoveryIntegrationTest:7'; do
  suite="${spec%%:*}"
  count="${spec##*:}"
  report="target/surefire-reports/TEST-dev.oreslang.runtime.${suite}.xml"
  [[ -s "$report" ]] || { echo "MISSING_SUREFIRE_REPORT: $suite" >&2; exit 3; }
  for metric in "tests=\"$count\"" 'errors="0"' 'failures="0"' 'skipped="0"'; do
    grep -Fq "$metric" "$report" || { echo "BAD_SUREFIRE_METRIC: $suite $metric" >&2; exit 4; }
  done
done
echo 'PASS exact-source supervisor runtime bridge: 10 real ActorRuntime JUnit tests, 0 failed/errored/skipped'
