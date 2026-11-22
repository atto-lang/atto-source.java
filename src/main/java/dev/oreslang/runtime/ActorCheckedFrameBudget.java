package dev.oreslang.runtime;

import java.util.Objects;
import java.util.Optional;

/**
 * Bounded, compiler-facing host prototype of an actor's logical call-frame
 * quota. It is NOT an AttoLang guest call lowering, scheduler, or alternative
 * to checking stack usage before the physical JVM stack is exhausted.
 *
 * A terminal overflow is sticky even when foreign host code catches its Java
 * exception: the trusted actor dispatcher MUST inspect terminal_overflow()
 * after every callback and fail-stop the actor on the independent CONTROL path.
 * No ThreadLocal is used: logical actor continuations may migrate carriers.
 */
final class ActorCheckedFrameBudget {
    enum Limit { FRAME_COUNT, BYTE_CAPACITY }

    static final class Overflow extends IllegalStateException {
        private final Limit limit;
        private Overflow(Limit limit) {
            super(limit == Limit.FRAME_COUNT
                    ? "actor checked frame-count budget exhausted"
                    : "actor checked stack byte budget exhausted");
            this.limit = limit;
        }
        Limit limit() { return limit; }
    }

    static final class FrameToken {
        private final ActorCheckedFrameBudget owner;
        private final long serial;
        private final long previousSerial;
        private final int depth;
        private final long beforeBytes;
        private final long afterBytes;
        private FrameToken(ActorCheckedFrameBudget owner, long serial,
                           long previousSerial, int depth, long beforeBytes,
                           long afterBytes) {
            this.owner = Objects.requireNonNull(owner);
            this.serial = serial;
            this.previousSerial = previousSerial;
            this.depth = depth;
            this.beforeBytes = beforeBytes;
            this.afterBytes = afterBytes;
        }
    }

    private final long maxBytes;
    private final int maxFrames;
    private long usedBytes;
    private int depth;
    private long lastSerial;
    private long issued;
    private Limit terminal;

    ActorCheckedFrameBudget(long maxBytes, int maxFrames) {
        if (maxBytes <= 0 || maxFrames <= 0 || maxFrames > 1024) {
            throw new IllegalArgumentException("invalid actor checked frame budget");
        }
        this.maxBytes = maxBytes;
        this.maxFrames = maxFrames;
    }

    synchronized FrameToken enter_frame(int bytes) {
        if (terminal != null) throw new Overflow(terminal);
        if (bytes <= 0) throw new IllegalArgumentException("actor frame bytes must be positive");
        if (depth >= maxFrames) throw latch(Limit.FRAME_COUNT);
        if ((long) bytes > maxBytes - usedBytes) throw latch(Limit.BYTE_CAPACITY);
        if (issued == Long.MAX_VALUE) {
            throw new IllegalStateException("actor frame serial exhausted");
        }
        final long previous = lastSerial;
        final long serial = ++issued;
        final long before = usedBytes;
        usedBytes += bytes;
        ++depth;
        lastSerial = serial;
        return new FrameToken(this, serial, previous, depth, before, usedBytes);
    }

    synchronized void leave_frame(FrameToken token) {
        Objects.requireNonNull(token);
        if (token.owner != this || depth == 0 || token.depth != depth ||
                token.serial == 0 || token.serial != lastSerial ||
                token.afterBytes != usedBytes || token.beforeBytes >= usedBytes) {
            throw new IllegalStateException("foreign, stale or out-of-order actor frame");
        }
        lastSerial = token.previousSerial;
        usedBytes = token.beforeBytes;
        --depth;
        // Terminal state is deliberately not cleared during unwind.
    }

    synchronized Optional<Limit> terminal_overflow() { return Optional.ofNullable(terminal); }
    synchronized long used_bytes() { return usedBytes; }
    synchronized int active_frames() { return depth; }

    private Overflow latch(Limit reason) {
        terminal = reason;
        return new Overflow(reason);
    }
}
