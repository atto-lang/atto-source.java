package dev.oreslang.runtime;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * One actor-owned native allocation, closed deterministically on explicit block
 * close or actor finalization. On JDK 22+ this uses the standard Foreign Memory
 * API via reflection so the Java 21 language/runtime compatibility remains.
 *
 * <p>The backing Arena is SHARED (not thread-confined) because actor continuations
 * may resume on a different carrier. This does not grant another actor access:
 * ActorRuntime performs actor-ID checks before exposing any bytes. Never expose
 * the underlying MemorySegment/ByteBuffer to Oreslang guest code.</p>
 *
 * <p>JDK 21 compatibility mode uses allocateDirect, which is NOT deterministically
 * freed. The runtime must not claim zero GC or prompt reclamation in that mode.
 * This class does not allocate Java/Truffle objects off heap.</p>
 */
final class ActorForeignMemory implements AutoCloseable {
    private final ByteBuffer memory;
    private final AutoCloseable arena;
    private boolean closed;

    private ActorForeignMemory(ByteBuffer memory, AutoCloseable arena) {
        this.memory = memory.order(ByteOrder.LITTLE_ENDIAN);
        this.arena = arena;
    }

    static ActorForeignMemory allocate(int bytes) {
        if (bytes < 0) throw new IllegalArgumentException("negative arena allocation");
        try {
            Class<?> arenaType = Class.forName("java.lang.foreign.Arena");
            Class<?> segmentType = Class.forName("java.lang.foreign.MemorySegment");
            Object owner = arenaType.getMethod("ofShared").invoke(null);
            AutoCloseable arena = (AutoCloseable) owner;
            try {
                // Reserve one byte for an empty logical segment. Zero is never
                // exposed and accounting still uses the requested size.
                Object segment = arenaType.getMethod("allocate", long.class, long.class)
                        .invoke(owner, Math.max(1L, (long) bytes), 1L);
                ByteBuffer buffer = (ByteBuffer) segmentType.getMethod("asByteBuffer").invoke(segment);
                buffer.limit(bytes);
                return new ActorForeignMemory(buffer.slice(), arena);
            } catch (Throwable failure) {
                try {
                    arena.close();
                } catch (Exception suppressed) {
                    failure.addSuppressed(suppressed);
                }
                throw failure;
            }
        } catch (ClassNotFoundException unsupportedJdk) {
            // The repository's current Maven release is 21. JDK 22+ supplies
            // java.lang.foreign, while JDK 21 stays an explicit compatibility mode.
            return new ActorForeignMemory(ByteBuffer.allocateDirect(bytes), null);
        } catch (InvocationTargetException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof Error error) throw error;
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("unable to allocate actor FFM arena", cause);
        } catch (RuntimeException | Error failed) {
            throw failed;
        } catch (Throwable failure) {
            throw new IllegalStateException("unable to allocate actor FFM arena", failure);
        }
    }

    boolean deterministicReclamation() {
        return arena != null;
    }

    ByteBuffer memory() {
        if (closed) throw new IllegalStateException("actor native region is closed");
        return memory;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        // Zero while the FFM scope is live. MemorySegment/ByteBuffer checks
        // enforce liveness after arena.close on supported JDKs.
        ByteBuffer copy = memory.duplicate();
        copy.clear();
        while (copy.hasRemaining()) copy.put((byte) 0);
        if (arena != null) {
            try {
                arena.close();
            } catch (Exception failure) {
                throw new IllegalStateException("cannot close actor native arena", failure);
            }
        }
    }
}
