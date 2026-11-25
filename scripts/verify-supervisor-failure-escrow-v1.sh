#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
source_path='src/main/java/dev/oreslang/runtime/SupervisorFailureEscrowV1.java'
test_path='src/test/java/dev/oreslang/runtime/SupervisorFailureEscrowV1Standalone.java'
source_blob='d68e0c5066b5842924d398a0c7bbecba382844ff'
test_blob='bfa4618c1e76041c8e5a90ad4950abdef73e1602'
race_path='src/test/java/dev/oreslang/runtime/SupervisorEscrowDataAdmissionRaceStandalone.java'
race_blob='0d8e5a4eba9eff407b4c610ce09f1e2a1cd827d0'
[[ "$(git hash-object "$source_path")" == "$source_blob" ]] || { echo 'SOURCE_BLOB_MISMATCH' >&2; exit 2; }
[[ "$(git hash-object "$test_path")" == "$test_blob" ]] || { echo 'TEST_BLOB_MISMATCH' >&2; exit 2; }
[[ "$(git hash-object "$race_path")" == "$race_blob" ]] || { echo 'RACE_BLOB_MISMATCH' >&2; exit 2; }
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
javac -Xlint:all -Werror -d "$work" "$source_path" "$test_path" "$race_path"
java -cp "$work" dev.oreslang.runtime.SupervisorFailureEscrowV1Standalone | tee "$work/result.txt"
grep -Fxq 'PASS SupervisorFailureEscrowV1Standalone assertions=557' "$work/result.txt"
java -cp "$work" dev.oreslang.runtime.SupervisorEscrowDataAdmissionRaceStandalone | tee "$work/race.txt"
grep -Fxq 'PASS SupervisorEscrowDataAdmissionRaceStandalone attempts=65536 checks=90' "$work/race.txt"
echo "VERIFIED exact-blob JDK host-only: $source_blob $test_blob $race_blob"
