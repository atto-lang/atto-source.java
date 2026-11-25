#!/usr/bin/env bash
set -euo pipefail
root="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf -- "$tmp"' EXIT
javac -Xlint:all -Werror -d "$tmp" \
  "$root/src/main/java/dev/oreslang/runtime/RuntimeBlockingGuard.java" \
  "$root/src/main/java/dev/oreslang/runtime/IpcReceiptCorrelation.java" \
  "$root/src/main/java/dev/oreslang/runtime/IpcReceiptCompletionBridge.java" \
  "$root/src/main/java/dev/oreslang/runtime/IpcPhysicalConnectionBroker.java" \
  "$root/src/main/java/dev/oreslang/runtime/IpcTransportSessionLease.java" \
  "$root/src/main/java/dev/oreslang/runtime/IpcAuthenticatedSocketTransport.java" \
  "$root/src/main/java/dev/oreslang/runtime/IpcSchemaBoundDataV1.java" \
  "$root/src/test/java/dev/oreslang/runtime/IpcSchemaBoundDataSelfTest.java"
java -cp "$tmp" dev.oreslang.runtime.IpcSchemaBoundDataSelfTest
echo GUEST_CONFORMANCE=NOT_EXECUTED
