package dev.oreslang.runtime;

import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * One actor-owned native allocation, closed deterministically on explicit block
 * close or actor finalization. When the FFM Arena API is available (including
 * GraalVM 21's preview API), it is used reflectively while compiling to Java 21.
 *
 * <p>The backing Arena is SHARED (not thread-confined) because actor continuations
 * may resume on a different carrier. This does not grant another actor access:
 * ActorRuntime performs actor-ID checks before exposing any bytes. Never expose
 * the underlying MemorySegment/ByteBuffer to Oreslang guest code.</p>
 *
 * <p>When the JDK 21 FFM classes are unavailable, allocateDirect is the
 * compatibility fallback and is NOT deterministically freed. The runtime must
 * not claim zero GC or prompt reclamation in that mode.
 * This class does not allocate Java/Truffle objects off heap.</p>
 */
final class ActorForeignMemory implements AutoCloseable {
    private final ByteBuffer memory;
    private final AutoCloseable arena;
    private volatile boolean closed;

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
            // Native Image must never silently fall back when FFM is missing.
            // Only JDK 21 installations without FFM use direct buffers.
            if (Runtime.version().feature() >= 22)
                throw new IllegalStateException("FFM Arena unavailable on JDK 22+; refuse unscoped allocation", unsupportedJdk);
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

    synchronized ByteBuffer memory() {
        if (closed) throw new IllegalStateException("actor native region is closed");
        return memory;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        // Mark closed before teardown; no new private-block operation can
        // obtain this memory, even if clearing it or closing the arena fails.
        closed = true;
        Throwable failure = null;
        try {
            ByteBuffer copy = memory.duplicate();
            copy.clear();
            while (copy.hasRemaining()) copy.put((byte) 0);
        } catch (Throwable zeroizationFailure) {
            failure = zeroizationFailure;
        }
        // Closing the Arena must be attempted even if zeroization fails:
        // otherwise a failed write could permanently leak native memory.
        if (arena != null) {
            try {
                arena.close();
            } catch (Throwable closeFailure) {
                if (failure != null) failure.addSuppressed(closeFailure);
                else failure = closeFailure;
            }
        }
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure != null)
            throw new IllegalStateException("cannot close actor native arena", failure);
    }
}
