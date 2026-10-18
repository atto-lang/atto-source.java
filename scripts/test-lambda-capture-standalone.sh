#!/usr/bin/env bash
set -euo pipefail

# Real Oreslang AST classes + explicit lexical capture planner.
# Does not claim guest-runtime reachability or Native Image conformance.
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
src="$root/src/main/java/dev/oreslang"
test="$root/src/test/standalone/dev/oreslang/nodes/LambdaCaptureStandaloneHarness.java"
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT

runtime_source="$root/src/main/java/dev/oreslang/nodes/OresEvalRootNode.java"
if grep -F '.snapshot()' "$runtime_source" >/dev/null; then
  printf 'ambient Env.snapshot() is forbidden; deferred code must project explicit captures\n' >&2
  exit 1
fi
for required in   'capturesSelect(select)'   'capturesSelect(selected)'   'capturesBlock('   'snapshotCaptures(LambdaCapturePlanner.captures(lambda))'
do
  grep -F "$required" "$runtime_source" >/dev/null || {
    printf 'missing projected capture runtime path: %s\n' "$required" >&2
    exit 1
  }
done
javac -d "$out" \
  "$src/ast/Ast.java" \
  "$src/nodes/LambdaCaptureExpressions.java" \
  "$src/nodes/LambdaCaptureStatements.java" \
  "$src/nodes/LambdaCapturePlanner.java" \
  "$test"
java -ea -cp "$out" dev.oreslang.nodes.LambdaCaptureStandaloneHarness
