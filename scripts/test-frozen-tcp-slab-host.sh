#!/usr/bin/env bash
set -euo pipefail
# Minimal Java host-only immutable slab proof, no Oreslang guest code.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
javac -d "$WORK" \
  "$ROOT/src/main/java/dev/oreslang/runtime/FrozenTcpSlab.java" \
  "$ROOT/src/test/java/dev/oreslang/runtime/FrozenTcpSlabHostSmoke.java"
java -cp "$WORK" dev.oreslang.runtime.FrozenTcpSlabHostSmoke
