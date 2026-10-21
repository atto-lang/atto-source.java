#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
javac --release 21 -d "$out" \
  src/main/java/dev/oreslang/runtime/HttpResponseDraft.java \
  src/main/java/dev/oreslang/runtime/HttpResponseBodyOpenGate.java \
  src/main/java/dev/oreslang/runtime/HttpResponseWirePhase.java \
  src/main/java/dev/oreslang/runtime/HttpResponseCleanupDispatcher.java \
  src/test/java/dev/oreslang/runtime/HttpResponseCloseRaceCheck.java \
  src/test/java/dev/oreslang/runtime/HttpResponseBodyOpenGateCheck.java \
  src/test/java/dev/oreslang/runtime/HttpResponseBodyOpenSchedulingCheck.java \
  src/test/java/dev/oreslang/runtime/HttpResponseBodyOpenWireCheck.java \
  src/test/java/dev/oreslang/runtime/HttpResponseWireCommitCheck.java \
  src/test/java/dev/oreslang/runtime/HttpResponseWirePhaseCheck.java \
  src/test/java/dev/oreslang/runtime/HttpResponseCleanupDispatcherCheck.java \
  src/test/java/dev/oreslang/runtime/HttpResponseAbortRaceWireCheck.java \
  src/test/java/dev/oreslang/runtime/HttpResponseSupplierFailureCheck.java \
  src/test/java/dev/oreslang/runtime/HttpResponsePreflightCheck.java \
  src/test/java/dev/oreslang/runtime/HttpResponseDraftStandaloneCheck.java
for test in HttpResponseCleanupDispatcherCheck HttpResponseWirePhaseCheck HttpResponseWireCommitCheck HttpResponseBodyOpenSchedulingCheck HttpResponseBodyOpenGateCheck HttpResponseBodyOpenWireCheck HttpResponseCloseRaceCheck HttpResponseAbortRaceWireCheck HttpResponseSupplierFailureCheck HttpResponsePreflightCheck HttpResponseDraftStandaloneCheck; do
  java -cp "$out" "dev.oreslang.runtime.$test"
done
