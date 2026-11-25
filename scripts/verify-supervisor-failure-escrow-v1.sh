#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
source_path='src/main/java/dev/oreslang/runtime/SupervisorFailureEscrowV1.java'
test_path='src/test/java/dev/oreslang/runtime/SupervisorFailureEscrowV1Standalone.java'
source_blob='09d58a348fd5a2de4ab025f1f1ede458a5a0041d'
test_blob='bfa4618c1e76041c8e5a90ad4950abdef73e1602'
race_path='src/test/java/dev/oreslang/runtime/SupervisorEscrowDataAdmissionRaceStandalone.java'
race_blob='4f44466d69b4440400d1d5de533917d1b7257521'
[[ "$(git hash-object "$source_path")" == "$source_blob" ]] || { echo 'SOURCE_BLOB_MISMATCH' >&2; exit 2; }
[[ "$(git hash-object "$test_path")" == "$test_blob" ]] || { echo 'TEST_BLOB_MISMATCH' >&2; exit 2; }
[[ "$(git hash-object "$race_path")" == "$race_blob" ]] || { echo 'RACE_BLOB_MISMATCH' >&2; exit 2; }
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
javac -Xlint:all -Werror -d "$work" "$source_path" "$test_path" "$race_path"
java -cp "$work" dev.oreslang.runtime.SupervisorFailureEscrowV1Standalone | tee "$work/result.txt"
grep -Fxq 'PASS SupervisorFailureEscrowV1Standalone assertions=557' "$work/result.txt"
java -cp "$work" dev.oreslang.runtime.SupervisorEscrowDataAdmissionRaceStandalone | tee "$work/race.txt"
grep -Fxq 'PASS SupervisorEscrowDataAdmissionRaceStandalone attempts=65536 checks=83' "$work/race.txt"
echo "VERIFIED exact-blob JDK host-only: $source_blob $test_blob $race_blob"
