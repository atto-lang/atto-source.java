package dev.oreslang.runtime;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Trusted evaluator bridge for one guest call descriptor within an existing
 * actor-owned frame reserved by GuestCallDepthGuard.
 *
 * <p>There is ONE 32-byte actor frame per non-tail call. Only 16 bytes store
 * source call kind, arity and tail-transfer count. The native JVM stack still
 * holds return addresses and guest locals until stackless lowering lands.
 * This Java-only bridge never exposes a frame capability to guest code.
 */
public final class GuestInvocationFrame implements AutoCloseable {
    public static final int DESCRIPTOR_BYTES = 16;

    private final int carrierDepth;
    private final Thread carrier;
    private boolean closed;

    private GuestInvocationFrame(int carrierDepth) {
        this.carrierDepth = carrierDepth;
        this.carrier = Thread.currentThread();
    }

    /** A successful entry consumes exactly one existing guarded guest frame. */
    public static GuestInvocationFrame enter(int kind, int arity) {
        if (kind < 0 || arity < 0) {
            throw new IllegalArgumentException("invalid guest invocation descriptor");
        }
        int depth = GuestCallDepthGuard.enter();
        try {
            GuestCallDepthGuard.writeInvocationDescriptor(
                    depth, descriptor(kind, arity, 0));
            return new GuestInvocationFrame(depth);
        } catch (RuntimeException | Error failure) {
            // Descriptor setup is transactional; do not strand an actor frame
            // or carrier depth on a reused worker after partial admission.
            try {
                GuestCallDepthGuard.leave(depth);
            } catch (RuntimeException | Error cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    /** Rewrite the same guarded frame; tail calls never grow actor bytes. */
    public void tailTransfer(int kind, int arity, long hops) {
        ensureOpenOnCarrier();
        if (kind < 0 || arity < 0 || hops <= 0) {
            throw new IllegalArgumentException("invalid tail-transfer descriptor");
        }
        GuestCallDepthGuard.writeInvocationDescriptor(
                carrierDepth, descriptor(kind, arity, hops));
    }

    /** Package-local diagnostic copy: no guest frame backing array escapes. */
    byte[] snapshotDescriptor() {
        ensureOpenOnCarrier();
        return GuestCallDepthGuard.readInvocationDescriptor(carrierDepth);
    }

    private static byte[] descriptor(int kind, int arity, long hops) {
        return ByteBuffer.allocate(DESCRIPTOR_BYTES)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(kind)
                .putInt(arity)
                .putLong(hops)
                .array();
    }

    private void ensureOpenOnCarrier() {
        if (Thread.currentThread() != carrier) {
            throw new SecurityException("guest invocation frame used on foreign carrier");
        }
        if (closed) throw new IllegalStateException("guest invocation frame already closed");
    }

    @Override
    public void close() {
        ensureOpenOnCarrier();
        closed = true;
        GuestCallDepthGuard.leave(carrierDepth);
    }
}
