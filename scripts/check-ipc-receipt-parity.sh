#!/usr/bin/env bash
# Host-only receipt parity runner. Does not download source or move code across orgs.
set -euo pipefail
root="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
: "${JAVAC:=javac}"
: "${JAVA:=java}"
tmp="$(mktemp -d)"
trap 'rm -rf -- "$tmp"' EXIT
"$JAVAC" -Xlint:all -Werror -d "$tmp" \
  "$root/src/main/java/dev/oreslang/runtime/RuntimeBlockingGuard.java" \
  "$root/src/main/java/dev/oreslang/runtime/IpcReceiptCorrelation.java" \
  "$root/src/main/java/dev/oreslang/runtime/IpcReceiptCompletionBridge.java" \
  "$root/src/main/java/dev/oreslang/runtime/IpcTransportSessionLease.java" \
  "$root/src/main/java/dev/oreslang/runtime/IpcAuthenticatedSocketTransport.java" \
  "$root/src/test/java/dev/oreslang/runtime/IpcReceiptCorrelationSelfTest.java" \
  "$root/src/test/java/dev/oreslang/runtime/IpcReceiptCompletionBridgeSelfTest.java" \
  "$root/src/test/java/dev/oreslang/runtime/IpcReceiptPhysicalBindingSelfTest.java" \
  "$root/src/test/java/dev/oreslang/runtime/IpcSessionAdmissionCloseRaceSelfTest.java" \
  "$root/src/test/java/dev/oreslang/runtime/IpcTransportSessionLeaseSelfTest.java" \
  "$root/src/test/java/dev/oreslang/runtime/IpcAuthenticatedSocketTransportSelfTest.java" \
  "$root/src/test/java/dev/oreslang/runtime/IpcAuthenticatedSocketTwoProcessSelfTest.java" \
  "$root/src/test/java/dev/oreslang/runtime/IpcBlockingGuardSelfTest.java"
"$JAVA" -cp "$tmp" dev.oreslang.runtime.IpcReceiptCorrelationSelfTest
"$JAVA" -cp "$tmp" dev.oreslang.runtime.IpcReceiptCompletionBridgeSelfTest
"$JAVA" -cp "$tmp" dev.oreslang.runtime.IpcReceiptPhysicalBindingSelfTest
"$JAVA" -cp "$tmp" dev.oreslang.runtime.IpcSessionAdmissionCloseRaceSelfTest
"$JAVA" -cp "$tmp" dev.oreslang.runtime.IpcTransportSessionLeaseSelfTest
"$JAVA" -cp "$tmp" dev.oreslang.runtime.IpcAuthenticatedSocketTransportSelfTest
"$JAVA" -cp "$tmp" dev.oreslang.runtime.IpcAuthenticatedSocketTwoProcessSelfTest
"$JAVA" -cp "$tmp" dev.oreslang.runtime.IpcBlockingGuardSelfTest
printf 'JAVA_RECEIPTS_HOST_ONLY_PASS (not remote IPC or guest compilation)\n'
