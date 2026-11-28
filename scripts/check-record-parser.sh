#!/usr/bin/env bash
set -euo pipefail

# Parser/AST-only smoke test: no Maven, JUnit, GraalVM or external repository
# checkout. This does not prove type checking, ownership or guest execution.
root="$(cd "$(dirname "$0")/.." && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

javac --release 21 -encoding UTF-8 -d "$tmp" \
  "$root/src/main/java/dev/oreslang/ast/Ast.java" \
  "$root/src/main/java/dev/oreslang/parser/Token.java" \
  "$root/src/main/java/dev/oreslang/parser/Lexer.java" \
  "$root/src/main/java/dev/oreslang/parser/Parser.java" \
  "$root/src/test/java/dev/oreslang/RecordParserSmoke.java"
java -ea -cp "$tmp" dev.oreslang.RecordParserSmoke
