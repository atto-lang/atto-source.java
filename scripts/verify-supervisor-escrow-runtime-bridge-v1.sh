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
verify_blob src/main/java/dev/oreslang/runtime/SupervisorFailureEscrowV1.java ae70ed809b3c8c4760ed954fce5009cdb4d8057d
verify_blob src/main/java/dev/oreslang/runtime/SupervisorFailureEscrowRuntimeBridgeV1.java 4580dde144fbc8a00a0fc01836e8c2ed63f0c73f
verify_blob src/main/java/dev/oreslang/runtime/ActorRuntime.java 0760e04d1ccc57828317aebe9416324e08185b34
verify_blob src/test/java/dev/oreslang/runtime/SupervisorFailureEscrowRuntimeIntegrationTest.java ec5e572336b58415daf2792a93fc3db49ee42b82

# The existing dependency-free 554-assertion host lane remains mandatory.
bash scripts/verify-supervisor-failure-escrow-v1.sh

# Compile the actual full ActorRuntime source tree and run real JUnit tests.
mvn -B -q -Dtest=SupervisorFailureEscrowRuntimeIntegrationTest -DfailIfNoTests=true test

report=target/surefire-reports/TEST-dev.oreslang.runtime.SupervisorFailureEscrowRuntimeIntegrationTest.xml
[[ -s "$report" ]] || { echo 'MISSING_SUREFIRE_REPORT' >&2; exit 3; }
for metric in 'tests="3"' 'errors="0"' 'failures="0"' 'skipped="0"'; do
  grep -Fq "$metric" "$report" || { echo "BAD_SUREFIRE_METRIC: $metric" >&2; exit 4; }
done
echo 'PASS exact-source supervisor runtime bridge: 3 real ActorRuntime JUnit tests, 0 failed/errored/skipped'
