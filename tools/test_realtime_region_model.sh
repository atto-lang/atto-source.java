#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if ! command -v javac >/dev/null 2>&1 || ! command -v java >/dev/null 2>&1; then
  echo "JDK 21+ is required (javac and java)" >&2
  exit 2
fi
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/classes"
javac --release 21 -d "$WORK/classes" \
  "$ROOT/src/main/java/dev/oreslang/ast/Ast.java" \
  "$ROOT/src/main/java/dev/oreslang/compiler/RealtimeRegionEffectModel.java" \
  "$ROOT/src/test/java/dev/oreslang/compiler/RealtimeRegionEffectModelSelfTest.java" \
  "$ROOT/src/main/java/dev/oreslang/compiler/RealtimeActorSourceEffects.java" \
  "$ROOT/src/test/java/dev/oreslang/compiler/RealtimeActorSourceEffectsSelfTest.java"
java -ea -cp "$WORK/classes" dev.oreslang.compiler.RealtimeRegionEffectModelSelfTest
java -ea -cp "$WORK/classes" dev.oreslang.compiler.RealtimeActorSourceEffectsSelfTest
