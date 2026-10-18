#!/usr/bin/env bash
set -euo pipefail

# Real Oreslang AST classes + explicit lexical capture planner.
# Does not claim guest-runtime reachability or Native Image conformance.
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
src="$root/src/main/java/dev/oreslang"
test="$root/src/test/standalone/dev/oreslang/nodes/LambdaCaptureStandaloneHarness.java"
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
javac -d "$out" \
  "$src/ast/Ast.java" \
  "$src/nodes/LambdaCaptureExpressions.java" \
  "$src/nodes/LambdaCaptureStatements.java" \
  "$src/nodes/LambdaCapturePlanner.java" \
  "$test"
java -ea -cp "$out" dev.oreslang.nodes.LambdaCaptureStandaloneHarness
