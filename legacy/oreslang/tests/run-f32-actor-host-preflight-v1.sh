#!/usr/bin/env bash
# JDK 21 package-private *host admission only*. Does not execute compiler,
# GraalVM Native Image, OresLLM tensor code, or any untrusted guest.
set -Eeuo pipefail
cd "$(dirname "$0")/.."
if ! command -v javac >/dev/null 2>&1 || ! command -v java >/dev/null 2>&1; then
  echo "F32_JAVA_PREFLIGHT_REJECTED: JDK required" >&2
  exit 2
fi
tmp="$(mktemp -d)"
trap 'rm -rf -- "$tmp"' EXIT
javac --release 21 -d "$tmp" \
  src/main/java/dev/oreslang/runtime/ExecutionContext.java \
  src/main/java/dev/oreslang/runtime/F32ActorHostPreflightV1.java \
  tests/F32ActorHostPreflightV1Smoke.java \
  tests/F32ActorTurnLeaseV1Smoke.java
java -ea -cp "$tmp" dev.oreslang.runtime.F32ActorHostPreflightV1Smoke
java -ea -cp "$tmp" dev.oreslang.runtime.F32ActorTurnLeaseV1Smoke
echo "ORES_F32_JAVA_ADMISSION_MATRIX_PASS|jdk=21|guest=NOT_EXECUTED|native_image=NOT_EXECUTED"
