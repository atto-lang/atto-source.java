package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Objects;

/**
 * Bounded, primitive-only guest-frame storage authenticated to one physical actor turn.
 * This is a restricted staging substrate, not a resumable SourceTask stack or an
 * independently allocated actor heap. Frames are JVM byte arrays and never escape
 * into guest values, DATA mailboxes, or other actor execution turns.
 */
final class ActorScalarFrameStore implements AutoCloseable {
    static final int DEFAULT_MAX_FRAMES = 64;
    static final int DEFAULT_MAX_BYTES = 64 * 1024;

    record Scalar(byte tag, long bits) {
        static final byte INT = 1;
        static final byte BOOL = 2;

        Scalar {
            if (tag != INT && tag != BOOL) {
                throw new IllegalArgumentException("unknown scalar frame tag");
            }
            if (tag == BOOL && bits != 0 && bits != 1) {
                throw new IllegalArgumentException("noncanonical scalar boolean");
            }
        }

        static Scalar ofLong(long value) { return new Scalar(INT, value); }
        static Scalar ofBoolean(boolean value) { return new Scalar(BOOL, value ? 1 : 0); }
        long asLong() {
            if (tag != INT) throw new IllegalStateException("not an int scalar");
            return bits;
        }
        boolean asBoolean() {
            if (tag != BOOL) throw new IllegalStateException("not a bool scalar");
            return bits == 1;
        }
    }

    final class Frame {
        private final ScalarFrameLayout layout;
        private final byte[] bytes;
        private boolean retired;

        private Frame(ScalarFrameLayout layout) {
            this.layout = layout;
            this.bytes = new byte[layout.bytes()];
        }

        int pc() {
            requireTop(this);
            long pc = readLong(bytes, 0);
            if (pc < 0 || pc > Integer.MAX_VALUE) {
                throw new IllegalStateException("invalid scalar frame PC");
            }
            return (int) pc;
        }

        void setPc(int pc) {
            requireTop(this);
            if (pc < 0) throw new IllegalArgumentException("negative scalar PC");
            writeLong(bytes, 0, pc);
        }

        Scalar local(int index) {
            requireTop(this);
            return readScalar(layout.localOffset(index));
        }

        void putLocal(int index, Scalar value) {
            requireTop(this);
            writeScalar(layout.localOffset(index), value);
        }

        int operandDepth() {
            requireTop(this);
            long height = readLong(bytes, 8);
            if (height < 0 || height > layout.operands()) {
                throw new IllegalStateException("invalid scalar operand height");
            }
            return (int) height;
        }

        void push(Scalar value) {
            requireTop(this);
            int depth = operandDepth();
            if (depth == layout.operands()) {
                throw new IllegalStateException("scalar operand quota exceeded");
            }
            writeScalar(layout.operandOffset(depth), value);
            writeLong(bytes, 8, depth + 1);
        }

        Scalar pop() {
            requireTop(this);
            int depth = operandDepth();
            if (depth == 0) throw new IllegalStateException("scalar operand underflow");
            int offset = layout.operandOffset(depth - 1);
            Scalar value = readScalar(offset);
            Arrays.fill(bytes, offset, offset + ScalarFrameLayout.SLOT_BYTES, (byte) 0);
            writeLong(bytes, 8, depth - 1);
            return value;
        }

        private void writeScalar(int offset, Scalar value) {
            Objects.requireNonNull(value, "scalar");
            Arrays.fill(bytes, offset, offset + ScalarFrameLayout.SLOT_BYTES, (byte) 0);
            bytes[offset] = value.tag();
            writeLong(bytes, offset + 8, value.bits());
        }

        private Scalar readScalar(int offset) {
            // Fail closed on uninitialized or corrupted typed slots.
            for (int i = 1; i < 8; i++) {
                if (bytes[offset + i] != 0) {
                    throw new IllegalStateException("noncanonical scalar padding");
                }
            }
            try {
                return new Scalar(bytes[offset], readLong(bytes, offset + 8));
            } catch (IllegalArgumentException corrupt) {
                throw new IllegalStateException("invalid or uninitialized scalar slot", corrupt);
            }
        }
    }

    private final ActorRuntime runtime;
    private final ExecutionContext execution;
    private final Object physicalSegment;
    private final Thread carrier;
    private final int maxFrames;
    private final int maxBytes;
    private final ArrayDeque<Frame> frames = new ArrayDeque<>();
    private int usedBytes;
    private boolean closed;

    ActorScalarFrameStore() {
        this(DEFAULT_MAX_FRAMES, DEFAULT_MAX_BYTES);
    }

    ActorScalarFrameStore(int maxFrames, int maxBytes) {
        if (maxFrames < 1 || maxFrames > 4096
                || maxBytes < ScalarFrameLayout.HEADER_BYTES || maxBytes > (1 << 24)) {
            throw new IllegalArgumentException("scalar frame budgets outside bounded range");
        }
        ExecutionContext current = ActorRuntime.currentActorExecutionContext();
        ActorRuntime owner = ActorRuntime.currentActorRuntime();
        if (current == null || owner == null
                || current.allocationDomain().kind() != ExecutionContext.AllocationKind.ACTOR
                || ActorRuntime.currentActorKind() == ActorRuntime.ActorKind.UNTRUSTED) {
            throw new SecurityException("scalar frames require a trusted physical actor turn");
        }
        this.runtime = owner;
        this.execution = current;
        this.physicalSegment = ActorRuntime.currentMutexExecutionSegment();
        this.carrier = Thread.currentThread();
        this.maxFrames = maxFrames;
        this.maxBytes = maxBytes;
    }

    Frame enter(int locals, int operands) {
        requireTurn();
        requireOpen();
        ScalarFrameLayout layout = ScalarFrameLayout.checked(locals, operands);
        if (frames.size() >= maxFrames || layout.bytes() > maxBytes - usedBytes) {
            throw new IllegalStateException("scalar frame store quota exceeded");
        }
        Frame frame = new Frame(layout);
        frames.addLast(frame);
        usedBytes += layout.bytes();
        return frame;
    }

    void leave(Frame frame) {
        requireTop(frame);
        // Zero the entire frame before dropping the last store reference.
        Arrays.fill(frame.bytes, (byte) 0);
        frame.retired = true;
        usedBytes -= frame.bytes.length;
        frames.removeLast();
    }

    int liveFrames() { requireTurn(); return frames.size(); }
    int liveBytes() { requireTurn(); return usedBytes; }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("scalar frame store closed");
    }

    private void requireTurn() {
        if (Thread.currentThread() != carrier
                || ActorRuntime.currentActorRuntime() != runtime
                || ActorRuntime.currentActorExecutionContext() != execution
                || ActorRuntime.currentMutexExecutionSegment() != physicalSegment) {
            throw new SecurityException("scalar frame accessed outside its owning physical turn");
        }
    }

    private void requireTop(Frame frame) {
        requireTurn();
        requireOpen();
        if (frame == null || frame.retired || frames.peekLast() != frame) {
            throw new IllegalStateException("stale or out-of-order scalar frame");
        }
    }

    private static long readLong(byte[] bytes, int offset) {
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (bytes[offset + i] & 0xffL);
        }
        return value;
    }

    private static void writeLong(byte[] bytes, int offset, long value) {
        for (int i = 7; i >= 0; i--) {
            bytes[offset + i] = (byte) value;
            value >>>= 8;
        }
    }

    @Override
    public void close() {
        requireTurn();
        if (closed) return;
        while (!frames.isEmpty()) {
            Frame frame = frames.removeLast();
            Arrays.fill(frame.bytes, (byte) 0);
            frame.retired = true;
        }
        usedBytes = 0;
        closed = true;
    }
}
