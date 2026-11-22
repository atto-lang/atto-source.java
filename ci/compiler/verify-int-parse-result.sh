#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
bash ci/admission/exact-tree.sh src/compiler/adt/AttoIntParseRuntime.java src/compiler/adt/AttoAdtPatternPlanner.java tests/compiler/AttoIntParseRuntimeProbe.java ci/compiler/verify-int-parse-result.sh
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/classes"
"${JAVA_HOME}/bin/javac" -Xlint:all -Werror -d "$work/classes" \
  src/compiler/adt/AttoIntParseRuntime.java src/compiler/adt/AttoAdtPatternPlanner.java \
  tests/compiler/AttoIntParseRuntimeProbe.java
"${JAVA_HOME}/bin/java" -cp "$work/classes" atto.compiler.adt.AttoIntParseRuntimeProbe > "$work/jvm.out"
grep -Eq '^ATTO_INT_PARSE_HOST_JVM_PASS checks=[1-9][0-9]+$' "$work/jvm.out"
grep -Fxq 'ATTO_INT_PARSE_ATTO_GUEST=NOT_EXECUTED' "$work/jvm.out"
(
  cd "$work"
  "${JAVA_HOME}/bin/native-image" --no-fallback --gc=epsilon -H:Name=atto-int-parse-host \
    -cp "$work/classes" atto.compiler.adt.AttoIntParseRuntimeProbe
)
"$work/atto-int-parse-host" >"$work/native.out" 2>"$work/native.err"
cmp "$work/jvm.out" "$work/native.out"
test ! -s "$work/native.err"
echo ATTO_INT_PARSE_HOST_JVM_NATIVE=PASS
echo ATTO_INT_PARSE_ATTO_GUEST=NOT_EXECUTED
