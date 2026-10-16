#!/usr/bin/env bash
set -euo pipefail

# A small, dependency-free compiler-descriptor smoke check. It is deliberately
# NOT a replacement for Maven/JUnit, GraalVM, actor runtime, or network tests.
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
registry="$root/src/main/java/dev/oreslang/types/PortableCodecRegistry.java"
harness="$root/src/test/standalone/dev/oreslang/types/PortableRegistryHarness.java"
[[ -f "$registry" && -f "$harness" ]] || {
  echo 'portable codec registry sources missing' >&2
  exit 1
}
command -v javac >/dev/null || { echo 'javac is required' >&2; exit 1; }
command -v java >/dev/null || { echo 'java is required' >&2; exit 1; }
output="$(mktemp -d)"
trap 'rm -rf "$output"' EXIT
javac -d "$output" "$registry" "$harness"
java -ea -cp "$output" dev.oreslang.types.PortableRegistryHarness
