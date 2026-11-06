#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd -P)"
work="$(mktemp -d)"
trap 'rm -rf -- "$work"' EXIT
# All standalone inputs are listed below. Do not discover module-info.java,
# which would turn this dependency-free gate into a GraalVM module build.
mkdir -p "$work/sourcepath"
ln -s "$root/src/main/java/dev" "$work/sourcepath/dev"
javac --release 21 -sourcepath "$work/sourcepath" -d "$work/classes" \
  "$root/src/main/java/dev/atto/ast/Ast.java" \
  "$root/src/main/java/dev/atto/parser/Token.java" \
  "$root/src/main/java/dev/atto/parser/Lexer.java" \
  "$root/src/main/java/dev/atto/parser/Parser.java" \
  "$root/src/main/java/dev/atto/runtime/OresSymbol.java" \
  "$root/src/test/java/dev/atto/parser/FuturePipelineProvenanceCheck.java" \
  "$root/src/test/java/dev/atto/parser/FuturePipelineTypingCheck.java"
java -cp "$work/classes" dev.atto.parser.FuturePipelineProvenanceCheck
java -cp "$work/classes" dev.atto.parser.FuturePipelineTypingCheck

