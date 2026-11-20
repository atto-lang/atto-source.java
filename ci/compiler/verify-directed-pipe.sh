#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/classes"
javac -Xlint:all -Werror -d "$work/classes" \
  src/compiler/pipe/AttoDirectedPipePlan.java \
  tests/compiler/AttoDirectedPipePlanProbe.java
java -cp "$work/classes" atto.compiler.pipe.AttoDirectedPipePlanProbe >"$work/jvm.log"
grep -Fx 'ATTO_DIRECTED_PIPE_PREHIR_PASS checks=28' "$work/jvm.log"
echo 'ATTO_DIRECTED_PIPE_JVM_HOST_ONLY=PASS'
if [[ "${ATTO_REQUIRE_NATIVE_IMAGE:-0}" == "1" ]]; then
  command -v native-image >/dev/null || { echo 'GraalVM native-image missing' >&2; exit 1; }
  native-image --no-fallback \
    -cp "$work/classes" atto.compiler.pipe.AttoDirectedPipePlanProbe \
    -o "$work/directed-pipe"
  "$work/directed-pipe" >"$work/native.log"
  grep -Fx 'ATTO_DIRECTED_PIPE_PREHIR_PASS checks=28' "$work/native.log"
  echo 'ATTO_DIRECTED_PIPE_NATIVE_IMAGE_HOST_ONLY=PASS'
fi
echo 'ATTO_DIRECTED_PIPE_ATTO_GUEST=NOT_EXECUTED'
