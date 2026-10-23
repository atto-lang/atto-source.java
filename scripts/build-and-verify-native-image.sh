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
mvn -B -ntp -DskipTests "-P$profile" package

executable="./target/$binary"
if [ ! -s "$executable" ] || [ ! -x "$executable" ]; then
  echo "missing or non-executable native artifact: $executable" >&2
  exit 1
fi

# Catch wrappers or Java launch scripts being mistaken for Native Image outputs.
if command -v file >/dev/null 2>&1; then
  description="$(file "$executable")"
  printf '%s\n' "$description"
  case "$description" in
    *ELF*|*Mach-O*|*PE32*) ;;
    *) echo "artifact is not an OS-native executable" >&2; exit 1 ;;
  esac
fi

info="$("$executable" --build-info)"
printf '%s\n' "$info"
printf '%s\n' "$info" | grep -F "\"build_mode\":\"$mode\"" >/dev/null || {
  echo "native build mode does not match $mode" >&2; exit 1;
}
printf '%s\n' "$info" | grep -F "\"managed_reference_mode\":\"$refs\"" >/dev/null || {
  echo "native managed reference mode does not match $refs" >&2; exit 1;
}

# Exercise a REAL .ores file and packaged stdlib with Java build environment
# variables removed. Only a successful native build plus execution passes.
(
  unset JAVA_HOME CLASSPATH JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS
  "$executable" --check examples/callables.ores
  "$executable" "--mode=$mode" examples/together.ores
  "$executable" "--mode=$mode" examples/native-core-library.ores
)

# A native executable must never silently switch on JVM JIT at runtime.
set +e
"$executable" --mode=jit --check examples/callables.ores >/dev/null 2>&1
status="$?"
set -e
if [ "$status" -ne 2 ]; then
  echo "expected --mode=jit to be rejected with CLI status 2, got $status" >&2
  exit 1
fi

echo "verified GraalVM Native Image: $executable ($mode, $refs)"
