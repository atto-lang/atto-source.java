#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
javac --release 21 -d "$out" \
  src/main/java/dev/atto/runtime/HttpResponseDraft.java \
  src/main/java/dev/atto/runtime/HttpResponseBodyOpenGate.java \
  src/main/java/dev/atto/runtime/HttpResponseWirePhase.java \
  src/main/java/dev/atto/runtime/HttpResponseCleanupDispatcher.java \
  src/test/java/dev/atto/runtime/HttpResponseCloseRaceCheck.java \
  src/test/java/dev/atto/runtime/HttpResponseBodyOpenGateCheck.java \
  src/test/java/dev/atto/runtime/HttpResponseBodyOpenSchedulingCheck.java \
  src/test/java/dev/atto/runtime/HttpResponseBodyOpenWireCheck.java \
  src/test/java/dev/atto/runtime/HttpResponseWireCommitCheck.java \
  src/test/java/dev/atto/runtime/HttpResponseWirePhaseCheck.java \
  src/test/java/dev/atto/runtime/HttpResponseCleanupDispatcherCheck.java \
  src/test/java/dev/atto/runtime/HttpResponseAbortRaceWireCheck.java \
  src/test/java/dev/atto/runtime/HttpResponseSupplierFailureCheck.java \
  src/test/java/dev/atto/runtime/HttpResponsePreflightCheck.java \
  src/test/java/dev/atto/runtime/HttpResponseDraftStandaloneCheck.java
for test in HttpResponseCleanupDispatcherCheck HttpResponseWirePhaseCheck HttpResponseWireCommitCheck HttpResponseBodyOpenSchedulingCheck HttpResponseBodyOpenGateCheck HttpResponseBodyOpenWireCheck HttpResponseCloseRaceCheck HttpResponseAbortRaceWireCheck HttpResponseSupplierFailureCheck HttpResponsePreflightCheck HttpResponseDraftStandaloneCheck; do
  java -cp "$out" "dev.atto.runtime.$test"
done
