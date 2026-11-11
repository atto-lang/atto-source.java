#!/usr/bin/env bash
# Real full Native Image with Epsilon: focused, bounded, native actor/JNI host soak.
# DO NOT call this an AttoLang guest AOT, production sandbox, or steady-state proof.
set -euo pipefail
repo="$(cd "$(dirname "$0")/.." && pwd)"
cd "$repo"
expected="${ATTO_EXPECTED_SOURCE_HEAD_SHA:-}"
if [[ ! "$expected" =~ ^[0-9a-f]{40}$ ]]; then
  echo 'GRAAL_EPSILON_NOT_EXECUTED: exact source SHA required' >&2; exit 2;
fi
if [[ "$(git rev-parse HEAD)" != "$expected" ]]; then
  echo 'GRAAL_EPSILON_NOT_EXECUTED: source checkout commit mismatch' >&2; exit 2;
fi
git diff --quiet HEAD -- || {
  echo 'GRAAL_EPSILON_NOT_EXECUTED: source checkout altered' >&2; exit 2;
}
test -z "$(git ls-files --others --exclude-standard)" || {
  echo 'GRAAL_EPSILON_NOT_EXECUTED: unexpected source files in checkout' >&2; exit 2;
}
echo "GRAAL_EXACT_SOURCE_COMMIT=$expected"if [[ -z "${JAVA_HOME:-}" || ! -x "$JAVA_HOME/bin/native-image" ]]; then
  echo 'GRAAL_EPSILON_NOT_EXECUTED: actual GraalVM Native Image toolchain required' >&2
  exit 2
fi
"$JAVA_HOME/bin/native-image" --version
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/classes"
cc -std=c11 -O2 -Wall -Wextra -Werror -fPIC -shared \
  -I "$JAVA_HOME/include" -I "$JAVA_HOME/include/linux" \
  src/main/c/oresarena.c -o "$tmp/liboresarena.so"
"$JAVA_HOME/bin/javac" -d "$tmp/classes" \
  src/main/java/dev/oreslang/runtime/ActorNativeArena.java \
  src/main/java/dev/oreslang/runtime/ActorLinearMemory.java \
  src/main/java/dev/oreslang/runtime/OresSymbol.java \
  src/main/java/dev/oreslang/runtime/NativeCarrierExecutor.java \
  src/test/java/dev/oreslang/runtime/ActorNativeArenaSoak.java
"$JAVA_HOME/bin/native-image" --no-fallback --gc=epsilon \
  -H:Name=atto-native-arena-epsilon -cp "$tmp/classes" \
  dev.oreslang.runtime.ActorNativeArenaSoak
test -s atto-native-arena-epsilon && test -x atto-native-arena-epsilon || {
  echo 'GRAAL_EPSILON_NOT_EXECUTED: no native executable' >&2; exit 1;
}
file atto-native-arena-epsilon | grep -E 'ELF|Mach-O' >/dev/null || {
  echo 'GRAAL_EPSILON_NOT_EXECUTED: artifact not a native OS binary' >&2; exit 1;
}
binary="$repo/atto-native-arena-epsilon"
mv "$binary" "$tmp/atto-native-arena-epsilon"
native_exit=0
(
  unset JAVA_HOME CLASSPATH JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS
  "$tmp/atto-native-arena-epsilon" "$tmp/liboresarena.so" > "$tmp/native-output" 2> "$tmp/native-stderr"
) || native_exit=$?
if (( native_exit != 0 )); then
  echo "GRAAL_EPSILON_NATIVE_RUNTIME_FAILED: exit_code=$native_exit" >&2
  cat "$tmp/native-output" >&2
  cat "$tmp/native-stderr" >&2
  exit "$native_exit"
fi
# JDK25 may emit one precise native-access warning when System.load() is
# called by this standalone unnamed-module JNI host. Do not suppress stderr
# globally: whitelist ONLY that exact four-line warning and optional empty LF.
if [[ -s "$tmp/native-stderr" ]]; then
  python3 - "$tmp/native-stderr" <<'PY'
import pathlib, re, sys
lines=pathlib.Path(sys.argv[1]).read_text().splitlines()
expected=[
    r"WARNING: A restricted method in java\.lang\.System has been called",
    r"WARNING: java\.lang\.System::load has been called by dev\.oreslang\.runtime\.ActorNativeArenaSoak in an unnamed module \(file:/.+\)",
    r"WARNING: Use --enable-native-access=ALL-UNNAMED to avoid a warning for callers in this module",
    r"WARNING: Restricted methods will be blocked in a future release unless native access is enabled",
]
if len(lines)==5 and lines[4]=="":
    lines=lines[:4]
if len(lines)!=4 or any(re.fullmatch(pattern,line) is None for pattern,line in zip(expected,lines)):
    print("GRAAL_EPSILON_UNEXPECTED_NATIVE_STDERR",file=sys.stderr)
    print(pathlib.Path(sys.argv[1]).read_text(),file=sys.stderr)
    sys.exit(1)
print("GRAAL_NATIVE_ACCESS_WARNING_EXACTLY_MATCHED=1")
PY
fi
cat "$tmp/native-output"
grep -Fx 'ATTO_NATIVE_ARENA_SOAK=PASS' "$tmp/native-output"
grep -Fx 'actor_operations=102400' "$tmp/native-output"
grep -Fx 'actor_arena_restarts=128' "$tmp/native-output"
grep -Fx 'native_charge_after=0' "$tmp/native-output"
grep -Fx 'gc_observed=true' "$tmp/native-output"
grep -Fx 'gc_collection_delta=0' "$tmp/native-output"
sampled_rss="$(awk -F= '$1=="rss_peak_sampled_kib"{print $2}' "$tmp/native-output")"
if [[ ! "$sampled_rss" =~ ^[0-9]+$ ]] || (( sampled_rss > 262144 )); then
  echo "GRAAL_EPSILON_NOT_EXECUTED: sampled peak RSS missing or above 256MiB budget" >&2; exit 1;
fi
echo "EPSILON_SAMPLED_RSS_KIB=$sampled_rss"
grep -Fx 'tenant_restarts=NOT_TESTED' "$tmp/native-output"
grep -Fx 'EVIDENCE_SCOPE=JNI_NATIVE_ARENA_HOST_ONLY_NO_ATTO_GUEST_OR_GRAAL_EPSILON_PROOF' "$tmp/native-output"
echo 'GRAAL_EPSILON_NATIVE_IMAGE_HOST_JNI_SOAK=PASSED'
echo 'EVIDENCE_SCOPE=REAL_EPSILON_NATIVE_IMAGE_HOST_ONLY_NO_ATTO_GUEST_OR_TENANT_PARITY'
