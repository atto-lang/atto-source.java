#!/usr/bin/env bash
set -euo pipefail

# Trust only the current checkout: never mirror or fetch private source.
# Compiles the production checked runtime's three source files unchanged,
# plus its dependency-free BigInteger differential smoke harness.
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
scratch="$(mktemp -d)"
trap 'rm -rf "$scratch"' EXIT

javac -encoding UTF-8 -d "$scratch" \
  "$root/src/main/java/dev/oreslang/runtime/CheckedI64Arithmetic.java" \
  "$root/src/main/java/dev/oreslang/runtime/CheckedI64OverflowException.java" \
  "$root/src/main/java/dev/oreslang/runtime/CheckedI64DivisionByZeroException.java" \
  "$root/src/main/java/dev/oreslang/parser/CheckedI64SourceHeader.java" \
  "$root/src/test/java/dev/oreslang/CheckedI64StandaloneSmoke.java" \
  "$root/src/test/java/dev/oreslang/CheckedI64HeaderStandaloneSmoke.java"

java -cp "$scratch" dev.oreslang.CheckedI64StandaloneSmoke
java -cp "$scratch" dev.oreslang.CheckedI64HeaderStandaloneSmoke
