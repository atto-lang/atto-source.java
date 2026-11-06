#!/usr/bin/env bash
set -euo pipefail

# Executable JDK-only record/type/ownership smoke. No Maven, GraalVM, external
# network, alternate organization, or private source export. This is NOT
# Native Image or guest-runtime verification.
root="$(cd "$(dirname "$0")/.." && pwd -P)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

javac --release 21 -encoding UTF-8 -Xlint:all -Werror -d "$tmp" \
  "$root/src/main/java/dev/atto/ast/Ast.java" \
  "$root/src/main/java/dev/atto/ast/AnnotationExpander.java" \
  "$root/src/main/java/dev/atto/ast/CallableSelector.java" \
  "$root/src/main/java/dev/atto/imports/ImportRules.java" \
  "$root/src/main/java/dev/atto/parser/Token.java" \
  "$root/src/main/java/dev/atto/parser/Lexer.java" \
  "$root/src/main/java/dev/atto/parser/Parser.java" \
  "$root/src/main/java/dev/atto/runtime/OresSymbol.java" \
  "$root/src/main/java/dev/atto/types/Types.java" \
  "$root/src/main/java/dev/atto/types/ReturnCleanupChecker.java" \
  "$root/src/main/java/dev/atto/types/OwnershipChecker.java" \
  "$root/src/main/java/dev/atto/types/TypeChecker.java" \
  "$root/src/test/java/dev/atto/RecordFrontendSmoke.java"
java -Xmx256m -ea -cp "$tmp" dev.atto.RecordFrontendSmoke
