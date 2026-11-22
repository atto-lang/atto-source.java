#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/classes"
"$JAVA_HOME/bin/javac" -Xlint:all -Werror -d "$work/classes" \
  src/compiler/channel/AttoChannelReadPlan.java \
  tests/compiler/AttoChannelReadPlanProbe.java
"$JAVA_HOME/bin/java" -cp "$work/classes" atto.compiler.channel.AttoChannelReadPlanProbe >"$work/jvm.out"
grep -Fx 'ATTO_CHANNEL_READ_PREHIR_PASS checks=20' "$work/jvm.out"
(
 cd "$work"
 "$JAVA_HOME/bin/native-image" --no-fallback --gc=epsilon -H:Name=channel-plan \
   -cp "$work/classes" atto.compiler.channel.AttoChannelReadPlanProbe
)
"$work/channel-plan" >"$work/native.out" 2>"$work/native.err"
grep -Fx 'ATTO_CHANNEL_READ_PREHIR_PASS checks=20' "$work/native.out"
test ! -s "$work/native.err"
echo ATTO_CHANNEL_PREHIR_HOST_JVM_NATIVE=PASS
echo ATTO_CHANNEL_IO_GUEST_EXECUTION=NOT_VERIFIED
