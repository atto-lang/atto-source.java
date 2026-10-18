#!/usr/bin/env bash
set -euo pipefail

# Type-algebra smoke only. Full parser, ownership, JUnit, GraalVM and native
# runtime parity must pass separately on an authorized exact-head runner.
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
type_source="$root/src/main/java/dev/oreslang/types/Types.java"
harness="$root/src/test/standalone/dev/oreslang/types/AnyCallableTypeHarness.java"
[[ -f "$type_source" && -f "$harness" ]] || {
  printf 'missing AnyCallable type algebra sources\n' >&2
  exit 1
}
command -v java >/dev/null || { printf 'java is required\n' >&2; exit 1; }
command -v javac >/dev/null || { printf 'javac is required\n' >&2; exit 1; }
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
javac -d "$out" "$type_source" "$harness"
java -ea -cp "$out" dev.oreslang.types.AnyCallableTypeHarness
