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
verify_blob src/main/java/dev/oreslang/runtime/SupervisorFailureEscrowV1.java 9ba01dfc981395d8ef206059da44c717a564b434
verify_blob src/main/java/dev/oreslang/runtime/SupervisorFailureEscrowRuntimeBridgeV1.java eb84fea8ce35f786bdae3de8626c784f03cbfbe8
verify_blob src/main/java/dev/oreslang/runtime/ActorRuntime.java b307f5cbd8c9a460aedd5fd2fbe162784d2fcfbd
verify_blob src/main/java/dev/oreslang/runtime/SupervisorEscrowSubtreeRecoveryV1.java e6849c0d825f8af4b1ac140e5ac65822e49ee1da
verify_blob src/test/java/dev/oreslang/runtime/SupervisorFailureEscrowRuntimeIntegrationTest.java 73e7fe37e1f5cbc45c07bb8bf7c30db6bb6ab532
verify_blob src/test/java/dev/oreslang/runtime/SupervisorEscrowSubtreeRecoveryIntegrationTest.java 09f2060a6697db2a8af02ea725140b588acb80c8
verify_blob src/test/java/dev/oreslang/runtime/SupervisorEscrowDataAdmissionRaceStandalone.java 9eb87ba39008840c7a4704e561b2fd94a7dd1fbd
verify_blob src/test/java/dev/oreslang/runtime/SupervisorEscrowAdmissionLinearizationIntegrationTest.java 52ba83a7122b74696dc52d00da729403031f769f

# The existing dependency-free saturated-allocation host lane remains mandatory.
bash scripts/verify-supervisor-failure-escrow-v1.sh

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
