#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
java_compiler="$(command -v javac)"
java_home="$(dirname "$(dirname "$(readlink -f "$java_compiler")")")"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/classes" "$work/src/dev/oreslang/runtime"

cc -Wall -Wextra -Werror -O2 -fPIC -shared \
  -I "$java_home/include" -I "$java_home/include/linux" \
  -o "$work/liboresarena_probe.so" "$repo_root/src/main/c/oresarena.c"

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
java -Dores.audit.lib="$work/liboresarena_probe.so" \
  -cp "$work/classes" dev.oreslang.runtime.ActorNativeArena
