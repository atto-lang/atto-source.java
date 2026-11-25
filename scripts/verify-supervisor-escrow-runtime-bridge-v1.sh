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
verify_blob src/main/java/dev/oreslang/runtime/SupervisorFailureEscrowV1.java 09d58a348fd5a2de4ab025f1f1ede458a5a0041d
verify_blob src/main/java/dev/oreslang/runtime/SupervisorFailureEscrowRuntimeBridgeV1.java 472b6b3baaa8feeb31be46b69b7574be7d544bd1
verify_blob src/main/java/dev/oreslang/runtime/ActorRuntime.java f589b9628bf09d62a3b1231e14b980be814f81e2
verify_blob src/main/java/dev/oreslang/runtime/SupervisorEscrowSubtreeRecoveryV1.java d8522a059af3b4afefd69f8ea10f1d7a6d77c389
verify_blob src/test/java/dev/oreslang/runtime/SupervisorFailureEscrowRuntimeIntegrationTest.java ee0c4d9b48fc7664e6985e84e07e2f77a6cec818
verify_blob src/test/java/dev/oreslang/runtime/SupervisorEscrowSubtreeRecoveryIntegrationTest.java 79c8bf1ec6245ff1f45de04accfc6d8832a7a1e5
verify_blob src/test/java/dev/oreslang/runtime/SupervisorEscrowDataAdmissionRaceStandalone.java 4f44466d69b4440400d1d5de533917d1b7257521
verify_blob src/test/java/dev/oreslang/runtime/SupervisorEscrowAdmissionLinearizationIntegrationTest.java b1198abedece2d0783617749208024a8cab9adbd

verify_blob src/main/java/dev/oreslang/runtime/ActorDataDecodeEscrowV1.java bf446a36d0f542b1d959aea8f7431545b323beb9
verify_blob src/main/java/dev/oreslang/types/PortableCodecRegistry.java 714390ba6383f8494327563b09d29af59073145e
verify_blob src/main/java/dev/oreslang/runtime/MessagePackWireV2.java 4f0ac0236d97af104eb166ecaa9a8a9c9bfb1673
verify_blob src/test/java/dev/oreslang/types/ActorDataDescriptorFixture.java 994515f6f0c1b9d0985456da486300e468ddfb4d
verify_blob src/test/java/dev/oreslang/runtime/ActorSerializedI64AdmissionTest.java 4700d07709968e939bf3ecfb2d85febcfee08f62
verify_blob src/test/java/dev/oreslang/runtime/SupervisorSerializedDataIntegrationTest.java 7c74d91933372e636d78f6eae1ae31fbd00c6445
verify_blob src/test/standalone/dev/oreslang/runtime/ActorDataDecodeEscrowHarness.java 733f8a3ded0fcceccb94df131cfb1c4c146a8017

# The existing dependency-free 557-assertion host lane remains mandatory.
bash scripts/verify-supervisor-failure-escrow-v1.sh

# Run the real MessagePack decoder/descriptor/escrow host harness first.
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
javac -d "$out" \
  src/main/java/dev/oreslang/runtime/ActorDataDecodeEscrowV1.java \
  src/main/java/dev/oreslang/types/PortableCodecRegistry.java \
  src/main/java/dev/oreslang/runtime/MessagePackWireV2.java \
  src/test/java/dev/oreslang/types/ActorDataDescriptorFixture.java \
  src/test/standalone/dev/oreslang/runtime/ActorDataDecodeEscrowHarness.java
java -ea -cp "$out" dev.oreslang.runtime.ActorDataDecodeEscrowHarness

# Compile the actual full ActorRuntime source tree and run real JUnit tests.
mvn -B -q -Dtest=SupervisorFailureEscrowRuntimeIntegrationTest,SupervisorEscrowSubtreeRecoveryIntegrationTest,SupervisorEscrowAdmissionLinearizationIntegrationTest,ActorSerializedI64AdmissionTest,SupervisorSerializedDataIntegrationTest -DfailIfNoTests=true test

for spec in 'SupervisorFailureEscrowRuntimeIntegrationTest:3' 'SupervisorEscrowSubtreeRecoveryIntegrationTest:4' 'SupervisorEscrowAdmissionLinearizationIntegrationTest:3' 'ActorSerializedI64AdmissionTest:1' 'SupervisorSerializedDataIntegrationTest:3'; do
  suite="${spec%%:*}"
  count="${spec##*:}"
  report="target/surefire-reports/TEST-dev.oreslang.runtime.${suite}.xml"
  [[ -s "$report" ]] || { echo "MISSING_SUREFIRE_REPORT: $suite" >&2; exit 3; }
  for metric in "tests=\"$count\"" 'errors="0"' 'failures="0"' 'skipped="0"'; do
    grep -Fq "$metric" "$report" || { echo "BAD_SUREFIRE_METRIC: $suite $metric" >&2; exit 4; }
  done
done
echo 'PASS exact-source supervisor runtime bridge: 14 real ActorRuntime JUnit tests, 0 failed/errored/skipped'
