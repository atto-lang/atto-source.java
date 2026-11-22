#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/classes"
"$JAVA_HOME/bin/javac" -Xlint:all -Werror -d "$work/classes" \
  src/compiler/async/AttoFuturePipelinePlan.java \
  tests/compiler/AttoFuturePipelinePlanProbe.java
"$JAVA_HOME/bin/java" -cp "$work/classes" atto.compiler.async.AttoFuturePipelinePlanProbe >"$work/jvm.out"
grep -Fx 'ATTO_FUTURE_PIPELINE_PREHIR_PASS checks=20' "$work/jvm.out"
(
  cd "$work"
  "$JAVA_HOME/bin/native-image" --no-fallback --gc=epsilon -H:Name=future-plan \
    -cp "$work/classes" atto.compiler.async.AttoFuturePipelinePlanProbe
)
"$work/future-plan" >"$work/native.out" 2>"$work/native.err"
grep -Fx 'ATTO_FUTURE_PIPELINE_PREHIR_PASS checks=20' "$work/native.out"
test ! -s "$work/native.err"
echo ATTO_FUTURE_PREHIR_HOST_JVM_NATIVE=PASS
echo ATTO_FUTURE_GUEST_SUSPENSION_AOT=NOT_VERIFIED
