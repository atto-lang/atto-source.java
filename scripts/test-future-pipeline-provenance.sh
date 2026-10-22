#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd -P)"
work="$(mktemp -d)"
trap 'rm -rf -- "$work"' EXIT
javac --release 21 -sourcepath "$root/src/main/java" -d "$work/classes" \
  "$root/src/main/java/dev/oreslang/ast/Ast.java" \
  "$root/src/main/java/dev/oreslang/parser/Token.java" \
  "$root/src/main/java/dev/oreslang/parser/Lexer.java" \
  "$root/src/main/java/dev/oreslang/parser/Parser.java" \
  "$root/src/main/java/dev/oreslang/runtime/OresSymbol.java" \
  "$root/src/test/java/dev/oreslang/parser/FuturePipelineProvenanceCheck.java" \
  "$root/src/test/java/dev/oreslang/parser/FuturePipelineTypingCheck.java"
java -cp "$work/classes" dev.oreslang.parser.FuturePipelineProvenanceCheck
java -cp "$work/classes" dev.oreslang.parser.FuturePipelineTypingCheck

