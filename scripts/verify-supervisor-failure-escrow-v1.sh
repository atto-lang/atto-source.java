#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
source_path='src/main/java/dev/oreslang/runtime/SupervisorFailureEscrowV1.java'
test_path='src/test/java/dev/oreslang/runtime/SupervisorFailureEscrowV1Standalone.java'
source_blob='1286b22fcd65da3c6546d5c4c9eb87149dcc5d39'
test_blob='b0b752af617fb29663977221e61a6a8c6f3e13c9'
race_path='src/test/java/dev/oreslang/runtime/SupervisorEscrowDataAdmissionRaceStandalone.java'
race_blob='4f44466d69b4440400d1d5de533917d1b7257521'
atomic_path='src/test/java/dev/oreslang/runtime/SupervisorAtomicChildAdmissionStandalone.java'
atomic_blob='284532af34835a01fca70cf682c0206c39b5af69'
[[ "$(git hash-object "$source_path")" == "$source_blob" ]] || { echo 'SOURCE_BLOB_MISMATCH' >&2; exit 2; }
[[ "$(git hash-object "$test_path")" == "$test_blob" ]] || { echo 'TEST_BLOB_MISMATCH' >&2; exit 2; }
[[ "$(git hash-object "$race_path")" == "$race_blob" ]] || { echo 'RACE_BLOB_MISMATCH' >&2; exit 2; }
[[ "$(git hash-object "$atomic_path")" == "$atomic_blob" ]] || { echo 'ATOMIC_BLOB_MISMATCH' >&2; exit 2; }
workspace_path='src/main/java/dev/oreslang/runtime/SupervisorRecoveryWorkspaceV1.java'
workspace_blob='11b998eda8b2a235b594c2cdd0e94da2818b0200'
[[ "$(git hash-object "$workspace_path")" == "$workspace_blob" ]] || { echo 'WORKSPACE_BLOB_MISMATCH' >&2; exit 2; }
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
javac -Xlint:all -Werror -d "$work" "$workspace_path" "$source_path" "$test_path" "$race_path" "$atomic_path"
java -cp "$work" dev.oreslang.runtime.SupervisorFailureEscrowV1Standalone | tee "$work/result.txt"
grep -Fxq 'PASS SupervisorFailureEscrowV1Standalone assertions=74304' "$work/result.txt"
grep -Fxq 'SATURATED_CONTROL_NODES=4096 DATA_PERMITS=65536 FAILURE_PUBLICATION_ALLOCATED_BYTES=0' "$work/result.txt"
grep -Fxq 'CONTROL_TAKEOVERS=62 DELIVERY_ACK_ALLOCATED_BYTES=0' "$work/result.txt"
java -cp "$work" dev.oreslang.runtime.SupervisorEscrowDataAdmissionRaceStandalone | tee "$work/race.txt"
grep -Fxq 'PASS SupervisorEscrowDataAdmissionRaceStandalone attempts=65536 checks=83' "$work/race.txt"
java -cp "$work" dev.oreslang.runtime.SupervisorAtomicChildAdmissionStandalone | tee "$work/atomic.txt"
grep -Eq "PASS SupervisorAtomicChildAdmissionStandalone attempts=16384 checks=[1-9][0-9]*" "$work/atomic.txt"
echo "VERIFIED exact-blob JDK host-only: $source_blob $test_blob $race_blob $atomic_blob"
