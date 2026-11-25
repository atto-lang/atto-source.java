#!/usr/bin/env bash
# Compiler-guard-only smoke: does not replace parser/JUnit/Graal guest tests.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT
javac --release 21 -d "$BUILD_DIR" \
  "$ROOT/src/main/java/dev/oreslang/types/ReceiverNamespaceGate.java" \
  "$ROOT/tests/namespace-receiver-smoke/ReceiverNamespaceGateSmoke.java"
output="$(java -cp "$BUILD_DIR" dev.oreslang.types.ReceiverNamespaceGateSmoke)"
test "$output" = "RECEIVER_NAMESPACE_GUARD_PASS assertions=9 scope=isolated-production-helper-only-not-parser-or-guest"
printf '%s\n' "$output"
