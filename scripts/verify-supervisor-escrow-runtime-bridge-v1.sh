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
verify_blob src/main/java/dev/oreslang/runtime/SupervisorFailureEscrowV1.java 53fafa708ad2bc6c13e36e2739335379efccb532
verify_blob src/main/java/dev/oreslang/runtime/SupervisorFailureEscrowRuntimeBridgeV1.java eb84fea8ce35f786bdae3de8626c784f03cbfbe8
verify_blob src/main/java/dev/oreslang/runtime/ActorRuntime.java b307f5cbd8c9a460aedd5fd2fbe162784d2fcfbd
verify_blob src/main/java/dev/oreslang/runtime/SupervisorEscrowSubtreeRecoveryV1.java 7a02125bb3f97533a0eeb10a6fc1359368bf03bd
verify_blob src/test/java/dev/oreslang/runtime/SupervisorFailureEscrowRuntimeIntegrationTest.java 73e7fe37e1f5cbc45c07bb8bf7c30db6bb6ab532
verify_blob src/test/java/dev/oreslang/runtime/SupervisorEscrowSubtreeRecoveryIntegrationTest.java 09f2060a6697db2a8af02ea725140b588acb80c8
verify_blob src/test/java/dev/oreslang/runtime/SupervisorEscrowDataAdmissionRaceStandalone.java 4f44466d69b4440400d1d5de533917d1b7257521
verify_blob src/test/java/dev/oreslang/runtime/SupervisorEscrowAdmissionLinearizationIntegrationTest.java 31e72b7afa224d6d9351be478bf9dd03d0943186

verify_blob src/main/java/dev/oreslang/runtime/SupervisorRecoveryQueueV1.java 600bdd6e7b6fac7a3de29041fcdc210317bc29f3
verify_blob src/test/java/dev/oreslang/runtime/SupervisorRecoveryQueueV1Standalone.java aa007218749826af9bfca935d2b5c7616dbb60b5

# The existing dependency-free saturated-allocation host lane remains mandatory.
bash scripts/verify-supervisor-failure-escrow-v1.sh
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
javac -Xlint:all -Werror -d "$work" src/main/java/dev/oreslang/runtime/SupervisorFailureEscrowV1.java src/main/java/dev/oreslang/runtime/SupervisorRecoveryQueueV1.java src/test/java/dev/oreslang/runtime/SupervisorRecoveryQueueV1Standalone.java
java -cp "$work" dev.oreslang.runtime.SupervisorRecoveryQueueV1Standalone

# Compile the actual full ActorRuntime source tree and run real JUnit tests.
mvn -B -q -Dtest=SupervisorFailureEscrowRuntimeIntegrationTest,SupervisorEscrowSubtreeRecoveryIntegrationTest,SupervisorEscrowAdmissionLinearizationIntegrationTest -DfailIfNoTests=true test

for spec in 'SupervisorFailureEscrowRuntimeIntegrationTest:4' 'SupervisorEscrowSubtreeRecoveryIntegrationTest:5' 'SupervisorEscrowAdmissionLinearizationIntegrationTest:6'; do
  suite="${spec%%:*}"
  count="${spec##*:}"
  report="target/surefire-reports/TEST-dev.oreslang.runtime.${suite}.xml"
  [[ -s "$report" ]] || { echo "MISSING_SUREFIRE_REPORT: $suite" >&2; exit 3; }
  for metric in "tests=\"$count\"" 'errors="0"' 'failures="0"' 'skipped="0"'; do
    grep -Fq "$metric" "$report" || { echo "BAD_SUREFIRE_METRIC: $suite $metric" >&2; exit 4; }
  done
done
echo 'PASS exact-source supervisor runtime bridge: 15 real ActorRuntime JUnit tests, 0 failed/errored/skipped'
