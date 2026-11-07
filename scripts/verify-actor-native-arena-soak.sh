#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
if [[ -z "${JAVA_HOME:-}" || ! -x "$JAVA_HOME/bin/javac" || ! -x "$JAVA_HOME/bin/java" ]]; then
  echo "NATIVE_ARENA_NOT_EXECUTED: JAVA_HOME JDK 21+ required" >&2; exit 2
fi
command -v cc >/dev/null || { echo "NATIVE_ARENA_NOT_EXECUTED: C compiler required" >&2; exit 2; }
if [[ "${ATTO_EXPECTED_SOURCE_HEAD:-}" != "" ]]; then
  [[ "$(git rev-parse HEAD)" == "$ATTO_EXPECTED_SOURCE_HEAD" ]] || {
    echo "NATIVE_ARENA_NOT_EXECUTED: exact Git source HEAD mismatch" >&2; exit 2;
  }
  git diff --quiet HEAD -- || {
    echo "NATIVE_ARENA_NOT_EXECUTED: source Git checkout modified" >&2; exit 2;
  }
fi
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/classes"
cc -std=c11 -O2 -Wall -Wextra -Werror -fPIC -shared \
  -I "$JAVA_HOME/include" -I "$JAVA_HOME/include/linux" \
  src/main/c/oresarena.c -o "$work/liboresarena.so"
"$JAVA_HOME/bin/javac" -d "$work/classes" \
  src/main/java/dev/oreslang/runtime/ActorNativeArena.java \
  src/main/java/dev/oreslang/runtime/ActorLinearMemory.java \
  src/test/java/dev/oreslang/runtime/ActorNativeArenaSoak.java
"$JAVA_HOME/bin/java" -cp "$work/classes" \
  dev.oreslang.runtime.ActorNativeArenaSoak "$work/liboresarena.so" | tee "$work/soak.txt"
grep -Fx 'ATTO_NATIVE_ARENA_SOAK=PASS' "$work/soak.txt"
grep -Fx 'actor_operations=102400' "$work/soak.txt"
grep -Fx 'actor_arena_restarts=128' "$work/soak.txt"
grep -Fx 'tenant_restarts=NOT_TESTED' "$work/soak.txt"
grep -Fx 'native_charge_after=0' "$work/soak.txt"
grep -Fx 'EVIDENCE_SCOPE=JNI_NATIVE_ARENA_HOST_ONLY_NO_ATTO_GUEST_OR_GRAAL_EPSILON_PROOF' "$work/soak.txt"
echo 'ATTO_JAVA_JNI_ACTOR_MEMORY_SOAK_GATE=PASSED'
