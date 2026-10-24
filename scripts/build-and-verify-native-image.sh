#!/bin/sh
# Reproducible GraalVM Native Image build with executable, source-level smoke.
# Usage: sh scripts/build-and-verify-native-image.sh [aot|hybrid] [compressed32|native64]
set -eu

if [ "$#" -gt 2 ]; then
  echo "usage: $0 [aot|hybrid] [compressed32|native64]" >&2
  exit 2
fi

mode="${1:-aot}"
refs="${2:-compressed32}"

case "$mode" in
  aot) profile=native-aot; binary=ores-aot ;;
  hybrid) profile=native-hybrid; binary=ores-hybrid ;;
  *) echo "invalid image mode: $mode" >&2; exit 2 ;;
esac
case "$refs" in
  compressed32) ;;
  native64) profile="$profile,native-ref64" ;;
  *) echo "invalid reference mode: $refs" >&2; exit 2 ;;
esac

# The Maven profile invokes JAVA_HOME/bin/native-image, not the JVM's javac.
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/native-image" ]; then
  echo "JAVA_HOME must point to a GraalVM JDK with bin/native-image" >&2
  exit 2
fi
if ! command -v mvn >/dev/null 2>&1; then
  echo "Maven (mvn) is required to build the native image" >&2
  exit 2
fi

"$JAVA_HOME/bin/native-image" --version
mvn -B -ntp -DskipTests "-P$profile" clean package

executable="./target/$binary"
if [ ! -s "$executable" ] || [ ! -x "$executable" ]; then
  echo "missing or non-executable native artifact: $executable" >&2
  exit 1
fi

# Catch wrappers or Java launch scripts being mistaken for Native Image outputs.
# Do not downgrade verification when the OS file classifier is missing.
if ! command -v file >/dev/null 2>&1; then
  echo "file command required for native executable verification" >&2
  exit 2
fi

description="$(file "$executable")"
printf '%s\n' "$description"
case "$description" in
  *ELF*|*Mach-O*|*PE32*) ;;
  *) echo "artifact is not an OS-native executable" >&2; exit 1 ;;
esac

info="$("$executable" --build-info)"
printf '%s\n' "$info"
# Requiring the complete, canonical manifest prevents substring or warning-line
# matches from certifying a wrong build mode/reference encoding.
case "$mode" in
  aot) guest_execution=interpreter-no-jit ;;
  hybrid) guest_execution=runtime-selected ;;
esac
expected="{\"build_mode\":\"$mode\",\"default_execution_mode\":\"$mode\",\"managed_reference_mode\":\"$refs\",\"guest_execution\":\"$guest_execution\"}"
if [ "$info" != "$expected" ]; then
  echo "native build-info does not match canonical $mode/$refs manifest" >&2
  exit 1
fi

# Exercise a REAL .ores file and packaged stdlib with Java build environment
# variables removed. Only a successful native build plus execution passes.
(
  unset JAVA_HOME CLASSPATH JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS
  "$executable" --check examples/callables.ores
  "$executable" "--mode=$mode" examples/together.ores
)

# Verify exact Oreslang semantics and packaged stdlib resolution, not merely
# a successful process exit. This output is from the checked-in native core
# library fixture (also observed in the previously successful AOT CI run).
core_output="$(
  unset JAVA_HOME CLASSPATH JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS
  "$executable" "--mode=$mode" examples/native-core-library.ores
)"
expected_core_output='9007199254740992|-999999999999999999|10'
if [ "$core_output" != "$expected_core_output" ]; then
  echo "native Oreslang core-library output differs from expected value" >&2
  exit 1
fi
printf '%s\n' "$core_output"

# A native image that merely exits zero without executing guest code is NOT
# sufficient evidence. Require the real Oreslang test framework's known output.
# Capture the command first (no pipeline that could hide its exit status).
test_output="$(
  unset JAVA_HOME CLASSPATH JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS
  "$executable" "--mode=$mode" examples/native-testing.ores
)"
printf '%s\n' "$test_output"
expected_summary='ORES_TEST|SUMMARY|total=4|passed=3|failed=0|skipped=1|filtered=0'
if ! printf '%s\n' "$test_output" | grep -Fx "$expected_summary" >/dev/null; then
  echo "native executable failed real Oreslang test-summary proof" >&2
  exit 1
fi

# A native executable must never silently switch on JVM JIT at runtime.
set +e
(
  unset JAVA_HOME CLASSPATH JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS
  "$executable" --mode=jit --check examples/callables.ores
) >/dev/null 2>&1
status="$?"
set -e
if [ "$status" -ne 2 ]; then
  echo "expected --mode=jit to be rejected with CLI status 2, got $status" >&2
  exit 1
fi

echo "verified GraalVM Native Image: $executable ($mode, $refs)"
