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

# Carrier CI prepares and verifies dependencies in an isolated, source-free
# step. When requested, prohibit Maven network dependency resolution while
# processing the private Oreslang checkout. Reject misspelled mode values.
case "${ORES_NATIVE_IMAGE_OFFLINE:-0}" in
  0) set -- ;;
  1) set -- -o ;;
  *) echo "ORES_NATIVE_IMAGE_OFFLINE must be 0 or 1" >&2; exit 2 ;;
esac
mvn -B -ntp "$@" -DskipTests "-P$profile" clean package

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
# Admission must NEVER execute the guest entry point. The same callables
# fixture prints 120:9:13 when executed, but --check must emit no guest output.
check_output="$(
  unset JAVA_HOME CLASSPATH JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS
  "$executable" --check examples/callables.ores
)"
if [ -n "$check_output" ]; then
  echo "native --check unexpectedly emitted guest output" >&2
  exit 1
fi

# Running the checked source must execute its main routine. In addition to
# the stdlib test below, verify factorial, lexical and nlexical closures.
callables_output="$(
  unset JAVA_HOME CLASSPATH JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS
  "$executable" "--mode=$mode" examples/callables.ores
)"
if [ "$callables_output" != '120:9:13' ]; then
  echo "native callables guest output differs from expected value" >&2
  exit 1
fi
printf '%s\n' "$callables_output"

# Validate the linked actor-isolation admission boundary inside the *native*
# executable as well. Positive shared/private declarations must typecheck,
# while private actors must not launder SharedMutex/share_readonly authority
# through imported types, helpers, or callbacks.
actor_check_output="$(
  unset JAVA_HOME CLASSPATH JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS
  "$executable" --check examples/shared-private-actor-isolation.ores
)"
if [ -n "$actor_check_output" ]; then
  echo "native actor --check unexpectedly emitted guest output" >&2
  exit 1
fi
if [ ! -f examples/private-actor-sharing-invalid.ores ]; then
  echo "missing private actor capability-invalid fixture" >&2
  exit 1
fi
# A parse error, missing import, or crash is NOT proof of private-actor
# isolation. Only the expected capability-denial diagnostic establishes that
# the linked checker reached and enforced the protected actor boundary.
# OresMain returns status 1 for rejected --check admission. A crash
# (signal status) or CLI usage failure (2) is not a capability-denial proof.
invalid_actor_status=0
invalid_actor_diagnostic="$(
  unset JAVA_HOME CLASSPATH JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS
  "$executable" --check examples/private-actor-sharing-invalid.ores 2>&1
)" || invalid_actor_status=$?
if [ "$invalid_actor_status" -ne 1 ]; then
  echo "expected private actor capability denial with CLI status 1, got $invalid_actor_status" >&2
  exit 1
fi
case "$invalid_actor_diagnostic" in
  *"Oreslang isolate denies capability SHARED_MEMORY"*|\
  *"Oreslang isolate denies capability ACTOR_SHARE_READONLY"*) ;;
  *)
    echo "private actor negative fixture failed without a capability-denial diagnostic" >&2
    exit 1
    ;;
esac

(
  unset JAVA_HOME CLASSPATH JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS
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
# Require each exact guest test identity once; counts alone can be forged by
# duplicating one PASS event while omitting another. Tolerate detail text only
# after the test name, preserving the checked-in reporting format.
if ! printf '%s\n' "$test_output" | awk -F '|' -v expected="$expected_summary" '
  /^ORES_TEST\|/ {
    if ($0 == expected) { summaries++; next }
    if ($1 != "ORES_TEST") { bad=1; next }
    if ($2 == "PASS" && $3 == "core/arithmetic/adds") { adds++; next }
    if ($2 == "PASS" && $3 == "core/float/tolerance") { floats++; next }
    if ($2 == "PASS" && $3 == "core/property/square") { properties++; next }
    if ($2 == "SKIP" && $3 == "core/future/parallel-runner") { skips++; next }
    bad=1
  }
  END {
    if (bad || summaries != 1 || adds != 1 || floats != 1 ||
        properties != 1 || skips != 1) exit 1
  }
'; then
  echo "native executable did not produce the exact unique PASS/SKIP guest tests and summary" >&2
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
