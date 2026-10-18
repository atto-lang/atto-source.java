#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
javac --release 21 -d "$out" \
  src/main/java/dev/oreslang/runtime/HttpResponseDraft.java \
  src/test/java/dev/oreslang/runtime/HttpResponseCloseRaceCheck.java \
  src/test/java/dev/oreslang/runtime/HttpResponseAbortRaceWireCheck.java \
  src/test/java/dev/oreslang/runtime/HttpResponseSupplierFailureCheck.java \
  src/test/java/dev/oreslang/runtime/HttpResponsePreflightCheck.java \
  src/test/java/dev/oreslang/runtime/HttpResponseDraftStandaloneCheck.java
for test in HttpResponseCloseRaceCheck HttpResponseAbortRaceWireCheck HttpResponseSupplierFailureCheck HttpResponsePreflightCheck HttpResponseDraftStandaloneCheck; do
  java -cp "$out" "dev.oreslang.runtime.$test"
done
