package dev.oreslang.runtime;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Objects;

/**
 * Trusted evaluator bridge for one guest call descriptor and supported typed
 * scalar argument values inside ONE actor-owned managed frame.
 *
 * <p>Scalar arguments are copied into frame bytes and decoded before the
 * interpreter binds them. This does NOT lower guest local variables, reference
 * roots, return PCs, expression recursion or suspended SourceTask continuations.
 * The JVM carrier still executes the interpreter and remains bounded by the
 * independent native-call guard. No guest frame token escapes to guest source.
 */
public final class GuestInvocationFrame implements AutoCloseable {
    public static final int DESCRIPTOR_BYTES = 16;

    private final int carrierDepth;
    private final int reservedBytes;
    private final Thread carrier;
    private boolean closed;
    private boolean scalarSnapshot;
    private int scalarBytes;
    private int currentArity;

    private GuestInvocationFrame(int carrierDepth, int reservedBytes, int arity) {
        this.carrierDepth = carrierDepth;
        this.reservedBytes = reservedBytes;
        this.currentArity = arity;
        this.carrier = Thread.currentThread();
    }

    /** Compatibility entry: primitive call metadata only, one 32-byte frame. */
    public static GuestInvocationFrame enter(int kind, int arity) {
        return enterInternal(kind, arity, null);
    }

    /**
     * Evaluator entry: reserve enough bytes to represent typed scalar arguments
     * within the SAME guarded actor frame (no second frame/charge).
     * Unsupported reference arguments are never persisted as raw pointers.
     */
    public static GuestInvocationFrame enterWithArguments(int kind, List<?> arguments) {
        Objects.requireNonNull(arguments, "arguments");
        return enterInternal(kind, arguments.size(), GuestScalarFrameCodec.encode(arguments));
    }

    private static GuestInvocationFrame enterInternal(int kind, int arity, byte[] scalars) {
        if (kind < 0 || arity < 0) {
            throw new IllegalArgumentException("invalid guest invocation descriptor");
        }
        int required = scalars == null ? DESCRIPTOR_BYTES
                : Math.addExact(DESCRIPTOR_BYTES, scalars.length);
        int bytes = Math.max(GuestCallDepthGuard.MANAGED_FRAME_CHARGE_BYTES, required);
        int depth = GuestCallDepthGuard.enter(bytes);
        try {
            GuestCallDepthGuard.writeInvocationDescriptor(depth, descriptor(kind, arity, 0));
            GuestInvocationFrame lease = new GuestInvocationFrame(depth, bytes, arity);
            lease.writeScalarSnapshot(scalars);
            return lease;
        } catch (RuntimeException | Error failure) {
            // Failed metadata admission must not leak carrier depth or actor
            // bytes into another call or another actor on the shared worker.
            try {
                GuestCallDepthGuard.leave(depth);
            } catch (RuntimeException | Error cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    /** Legacy metadata-only tail rewrite, preserving compatibility tests. */
    public void tailTransfer(int kind, int arity, long hops) {
        ensureOpenOnCarrier();
        validateTail(kind, arity, hops);
        discardSnapshot();
        currentArity = arity;
        GuestCallDepthGuard.writeInvocationDescriptor(
                carrierDepth, descriptor(kind, arity, hops));
    }

    /**
     * Replace the scalar snapshot in place on a proper tail hop. A tail
     * transfer to a larger arity may not fit the original reserved frame.
     * In that case use the existing JVM arguments instead of allocating a
     * second frame or claiming they were stored in the actor arena.
     */
    public void tailTransferWithArguments(int kind, List<?> arguments, long hops) {
        Objects.requireNonNull(arguments, "arguments");
        ensureOpenOnCarrier();
        validateTail(kind, arguments.size(), hops);
        byte[] encoded = GuestScalarFrameCodec.encode(arguments);
        discardSnapshot();
        currentArity = arguments.size();
        GuestCallDepthGuard.writeInvocationDescriptor(
                carrierDepth, descriptor(kind, arguments.size(), hops));
        if (encoded != null && DESCRIPTOR_BYTES + encoded.length <= reservedBytes) {
            writeScalarSnapshot(encoded);
        }
    }

    /** Restore the exact wrapper types from actor-owned memory when possible. */
    public List<Object> restoreScalarArguments(List<Object> fallback) {
        Objects.requireNonNull(fallback, "fallback");
        ensureOpenOnCarrier();
        if (fallback.size() != currentArity) {
            GuestCallDepthGuard.poisonMalformedInvocation(carrierDepth);
            throw new IllegalStateException("guest call arity changed after frame admission");
        }
        if (!scalarSnapshot) return fallback;
        try {
            List<Object> stored = GuestScalarFrameCodec.decode(
                    GuestCallDepthGuard.readScalarSlots(carrierDepth, scalarBytes));
            if (stored.size() != fallback.size()) {
                GuestCallDepthGuard.poisonMalformedInvocation(carrierDepth);
                throw new IllegalStateException("typed guest scalar frame layout mismatch");
            }
            return stored;
        } catch (RuntimeException failure) {
            // The actor cannot continue after malformed trusted compiler
            // frame data, even when a host callback swallows the exception.
            GuestCallDepthGuard.poisonMalformedInvocation(carrierDepth);
            throw failure;
        }
    }

    private void writeScalarSnapshot(byte[] encoded) {
        if (encoded == null || !GuestCallDepthGuard.hasActorFrame(carrierDepth)) {
            scalarSnapshot = false;
            scalarBytes = 0;
            return;
        }
        GuestCallDepthGuard.writeScalarSlots(carrierDepth, encoded);
        scalarBytes = encoded.length;
        scalarSnapshot = true;
    }

    private void discardSnapshot() {
        if (scalarSnapshot && scalarBytes > 0) {
            // Deterministic clearing prevents stale scalar values remaining
            // visible after a tail transfer to an unsupported reference call.
            GuestCallDepthGuard.writeScalarSlots(carrierDepth, new byte[scalarBytes]);
        }
        scalarSnapshot = false;
        scalarBytes = 0;
    }

    private static void validateTail(int kind, int arity, long hops) {
        if (kind < 0 || arity < 0 || hops <= 0) {
            throw new IllegalArgumentException("invalid tail-transfer descriptor");
        }
    }

    /** Package-local diagnostic copy; never returns the actor frame's backing bytes. */
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
