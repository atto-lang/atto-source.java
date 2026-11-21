#!/usr/bin/env bash
set -euo pipefail

# Executable JDK-only record/type/ownership smoke. No Maven, GraalVM, external
# network, alternate organization, or private source export. This is NOT
# Native Image or guest-runtime verification.
root="$(cd "$(dirname "$0")/.." && pwd -P)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

javac --release 21 -encoding UTF-8 -Xlint:all -Werror -d "$tmp" \
  "$root/src/main/java/dev/oreslang/ast/Ast.java" \
  "$root/src/main/java/dev/oreslang/ast/AnnotationExpander.java" \
  "$root/src/main/java/dev/oreslang/ast/CallableSelector.java" \
  "$root/src/main/java/dev/oreslang/imports/ImportRules.java" \
  "$root/src/main/java/dev/oreslang/parser/Token.java" \
  "$root/src/main/java/dev/oreslang/parser/Lexer.java" \
  "$root/src/main/java/dev/oreslang/parser/Parser.java" \
  "$root/src/main/java/dev/oreslang/runtime/OresSymbol.java" \
  "$root/src/main/java/dev/oreslang/types/Types.java" \
  "$root/src/main/java/dev/oreslang/types/AggregateConstructionPlan.java" \
  "$root/src/main/java/dev/oreslang/types/ReturnCleanupChecker.java" \
  "$root/src/main/java/dev/oreslang/types/OwnershipChecker.java" \
  "$root/src/main/java/dev/oreslang/types/TypeChecker.java" \
  "$root/src/test/java/dev/oreslang/RecordFrontendSmoke.java" \
  "$root/src/test/java/dev/oreslang/AggregateConstructionPlanSmoke.java"
java -Xmx256m -ea -cp "$tmp" dev.oreslang.RecordFrontendSmoke
java -Xmx256m -ea -cp "$tmp" dev.oreslang.AggregateConstructionPlanSmoke
