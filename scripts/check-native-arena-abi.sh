#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
os="$(uname -s)"

if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/javac" ]]; then
  java_home="$JAVA_HOME"
  java_compiler="$JAVA_HOME/bin/javac"
else
  java_compiler="$(command -v javac || true)"
  if [[ -z "$java_compiler" ]]; then
    echo "javac is unavailable; set JAVA_HOME or put javac on PATH" >&2
    exit 2
  fi
  if command -v realpath >/dev/null 2>&1; then
    java_compiler="$(realpath "$java_compiler")"
    java_home="$(cd "$(dirname "$java_compiler")/.." && pwd)"
  elif [[ "$os" == Darwin && -x /usr/libexec/java_home ]]; then
    java_home="$(/usr/libexec/java_home)"
  elif command -v readlink >/dev/null 2>&1 && readlink -f "$java_compiler" >/dev/null 2>&1; then
    java_compiler="$(readlink -f "$java_compiler")"
    java_home="$(cd "$(dirname "$java_compiler")/.." && pwd)"
  else
    echo "cannot resolve JAVA_HOME from javac; set JAVA_HOME explicitly" >&2
    exit 2
  fi
fi

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/classes" "$work/src/dev/oreslang/runtime"

case "$os" in
  Linux)
    jni_os="linux"
    probe_lib="$work/liboresarena_probe.so"
    shared_flags=(-shared)
    ;;
  Darwin)
    jni_os="darwin"
    probe_lib="$work/liboresarena_probe.dylib"
    shared_flags=(-dynamiclib)
    ;;
  *)
    echo "native arena ABI probe supports Linux and macOS only" >&2
    exit 2
    ;;
esac

"${CC:-cc}" -std=c11 -Wall -Wextra -Werror -O2 -fPIC "${shared_flags[@]}" \
  -I "$java_home/include" -I "$java_home/include/$jni_os" \
  -o "$probe_lib" "$repo_root/src/main/c/oresarena.c"

cat > "$work/src/dev/oreslang/runtime/ActorNativeArena.java" <<'JAVA'
package dev.oreslang.runtime;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;
/** Isolated ABI contract for the real src/main/c/oresarena.c JNI entry points. */
public final class ActorNativeArena {
    private static native ByteBuffer allocateNative(int bytes);
    private static native void releaseNative(ByteBuffer buffer);
    static { System.load(System.getProperty("ores.audit.lib")); }

    public static void main(String[] args) {
        if (allocateNative(-1) != null || allocateNative(0) != null) {
            throw new AssertionError("invalid allocation accepted");
        }
        Random rng = new Random(28182818L);
        for (int round = 0; round < 500; round++) {
            int lenA = 1 + rng.nextInt(128 * 1024);
            int lenB = 1 + rng.nextInt(128 * 1024);
            ByteBuffer a = allocateNative(lenA);
            ByteBuffer b = allocateNative(lenB);
            if (a == null || b == null || !a.isDirect() || !b.isDirect()
                    || a.capacity() != lenA || b.capacity() != lenB) {
                throw new AssertionError("native buffer allocation failed");
            }
            for (int j = 0; j < 10; j++) {
                if (a.get(rng.nextInt(lenA)) != 0 || b.get(rng.nextInt(lenB)) != 0) {
                    throw new AssertionError("native buffer not zero-initialized");
                }
            }
            int first = rng.nextInt(lenA);
            int other = rng.nextInt(lenB);
            a.put(first, (byte) 0x5a);
            if (a.get(first) != (byte) 0x5a || b.get(other) != 0) {
                throw new AssertionError("native buffers alias each other");
            }
            b.order(ByteOrder.LITTLE_ENDIAN);
            if (lenB >= Long.BYTES) {
                int last = lenB - Long.BYTES;
                b.putLong(last, 0x0102030405060708L);
                if (b.get(last) != (byte) 8 || b.get(last + 7) != (byte) 1
                        || b.getLong(last) != 0x0102030405060708L) {
                    throw new AssertionError("native buffer word layout mismatch");
                }
            }
            releaseNative(a);
            releaseNative(b);
        }
        System.out.println("PASS source-native-JNI ABI: 1000 allocations, zero-fill, isolation, word layout, release");
    }
}
JAVA

javac -d "$work/classes" "$work/src/dev/oreslang/runtime/ActorNativeArena.java"
java -Dores.audit.lib="$probe_lib" \
  -cp "$work/classes" dev.oreslang.runtime.ActorNativeArena
