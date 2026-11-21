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
verify_blob src/main/java/dev/oreslang/runtime/SupervisorFailureEscrowRuntimeBridgeV1.java 046030137057deb814594f5d0c874563fa3d9818
verify_blob src/main/java/dev/oreslang/runtime/ActorRuntime.java 0760e04d1ccc57828317aebe9416324e08185b34
verify_blob src/test/java/dev/oreslang/runtime/SupervisorFailureEscrowRuntimeIntegrationTest.java bb2038f98522d4904632b2c67103ca8891dba169

# The existing dependency-free 557-assertion host lane remains mandatory.
bash scripts/verify-supervisor-failure-escrow-v1.sh

# Compile the actual full ActorRuntime source tree and run real JUnit tests.
mvn -B -q -Dtest=SupervisorFailureEscrowRuntimeIntegrationTest -DfailIfNoTests=true test

report=target/surefire-reports/TEST-dev.oreslang.runtime.SupervisorFailureEscrowRuntimeIntegrationTest.xml
[[ -s "$report" ]] || { echo 'MISSING_SUREFIRE_REPORT' >&2; exit 3; }
for metric in 'tests="3"' 'errors="0"' 'failures="0"' 'skipped="0"'; do
  grep -Fq "$metric" "$report" || { echo "BAD_SUREFIRE_METRIC: $metric" >&2; exit 4; }
done
echo 'PASS exact-source supervisor runtime bridge: 3 real ActorRuntime JUnit tests, 0 failed/errored/skipped'
