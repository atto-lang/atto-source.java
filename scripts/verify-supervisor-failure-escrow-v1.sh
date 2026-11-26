#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
source_path='src/main/java/dev/oreslang/runtime/SupervisorFailureEscrowV1.java'
test_path='src/test/java/dev/oreslang/runtime/SupervisorFailureEscrowV1Standalone.java'
source_blob='008d1a52f44545f995533b2f1acdf938c091c080'
test_blob='8b34514c677cca124d0f3928d9a0da28e05b860d'
race_path='src/test/java/dev/oreslang/runtime/SupervisorEscrowDataAdmissionRaceStandalone.java'
race_blob='4f44466d69b4440400d1d5de533917d1b7257521'
atomic_path='src/test/java/dev/oreslang/runtime/SupervisorAtomicChildAdmissionStandalone.java'
atomic_blob='284532af34835a01fca70cf682c0206c39b5af69'
[[ "$(git hash-object "$source_path")" == "$source_blob" ]] || { echo 'SOURCE_BLOB_MISMATCH' >&2; exit 2; }
[[ "$(git hash-object "$test_path")" == "$test_blob" ]] || { echo 'TEST_BLOB_MISMATCH' >&2; exit 2; }
[[ "$(git hash-object "$race_path")" == "$race_blob" ]] || { echo 'RACE_BLOB_MISMATCH' >&2; exit 2; }
[[ "$(git hash-object "$atomic_path")" == "$atomic_blob" ]] || { echo 'ATOMIC_BLOB_MISMATCH' >&2; exit 2; }
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
javac -Xlint:all -Werror -d "$work" "$source_path" "$test_path" "$race_path" "$atomic_path"
java -cp "$work" dev.oreslang.runtime.SupervisorFailureEscrowV1Standalone | tee "$work/result.txt"
grep -Fxq 'PASS SupervisorFailureEscrowV1Standalone assertions=74300' "$work/result.txt"
grep -Fxq 'SATURATED_CONTROL_NODES=4096 DATA_PERMITS=65536 FAILURE_PUBLICATION_ALLOCATED_BYTES=0' "$work/result.txt"
java -cp "$work" dev.oreslang.runtime.SupervisorEscrowDataAdmissionRaceStandalone | tee "$work/race.txt"
grep -Fxq 'PASS SupervisorEscrowDataAdmissionRaceStandalone attempts=65536 checks=83' "$work/race.txt"
java -cp "$work" dev.oreslang.runtime.SupervisorAtomicChildAdmissionStandalone | tee "$work/atomic.txt"
grep -Eq "PASS SupervisorAtomicChildAdmissionStandalone attempts=16384 checks=[1-9][0-9]*" "$work/atomic.txt"
echo "VERIFIED exact-blob JDK host-only: $source_blob $test_blob $race_blob $atomic_blob"
