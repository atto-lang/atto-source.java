package dev.oreslang.runtime;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Objects;

/**
 * Compiler-only, typed primitive frame storage for the admitted stackless
 * scalar fnc subset. Never exposed to guest code or actor transports.
 *
 * Each frame holds PC and operand height (two 64-bit words), plus typed
 * 16-byte scalar local/operand slots. In actor execution ALL of these bytes
 * live in the incarnation-owned ActorFrameArena, under the exact SourceTask
 * execution identity when present. Non-actor calls have a bounded local
 * byte buffer. No guest objects, host pointers or callbacks are stored.
 *
 * This is still managed JVM memory; it does not switch native stacks.
 */
public final class ScalarFrameStorage implements AutoCloseable {
    private static final int HEADER_BYTES = 16;
    private static final int SLOT_BYTES = 16;
    private static final int MAX_SLOTS = 256;
    private static final int MAX_ROOT_FRAMES = 2048;

    public record Scalar(byte tag, long bits) {
        public static final byte INT = 1;
        public static final byte BOOL = 2;
        public Scalar {
            if (tag != INT && tag != BOOL) throw new IllegalArgumentException("bad scalar tag");
            if (tag == BOOL && bits != 0 && bits != 1) {
                throw new IllegalArgumentException("bad boolean representation");
            }
        }
        public static Scalar ofLong(long v) { return new Scalar(INT, v); }
        public static Scalar ofBoolean(boolean v) { return new Scalar(BOOL, v ? 1 : 0); }
        public Object boxed() { return tag == INT ? (Object) Long.valueOf(bits) : bits == 1; }
        public long asLong() {
            if (tag != INT) throw new IllegalArgumentException("expected scalar int");
            return bits;
        }
        public boolean asBoolean() {
            if (tag != BOOL) throw new IllegalArgumentException("expected scalar bool");
            return bits == 1;
        }
        public static Scalar from(Object v) {
            if (v instanceof Boolean b) return ofBoolean(b);
            if (v instanceof Byte || v instanceof Short || v instanceof Integer || v instanceof Long) {
                return ofLong(((Number) v).longValue());
            }
            throw new IllegalArgumentException("unsupported scalar argument or result");
        }
    }

    public final class Frame {
        private final ActorFrameArena.FrameRef lease;
        private final byte[] local;
        private final int locals;
        private final int maxOperands;
        private boolean closed;

        private Frame(ActorFrameArena.FrameRef lease, byte[] local, int locals, int maxOperands) {
            this.lease = lease;
            this.local = local;
            this.locals = locals;
            this.maxOperands = maxOperands;
        }

        public int pc() {
            checkTop(this);
            return readBoundedInt(this, 0, Integer.MAX_VALUE, "program counter");
        }
        public void setPc(int nextPc) {
            checkTop(this);
            if (nextPc < 0) throw new IllegalArgumentException("negative program counter");
            storeLong(this, 0, nextPc);
        }
        public void putLocal(int index, Scalar scalar) {
            checkTop(this);
            if (index < 0 || index >= locals) throw new IndexOutOfBoundsException("local slot");
            writeSlot(this, index, scalar);
        }
        public Scalar local(int index) {
            checkTop(this);
            if (index < 0 || index >= locals) throw new IndexOutOfBoundsException("local slot");
            return readSlot(this, index);
        }
        public void push(Scalar scalar) {
            checkTop(this);
            int depth = operandDepth();
            if (depth >= maxOperands) throw new IllegalStateException("operand stack quota exceeded");
            writeSlot(this, locals + depth, scalar);
            storeLong(this, 8, depth + 1);
        }
        public Scalar pop() {
            checkTop(this);
            int depth = operandDepth();
            if (depth <= 0) throw new IllegalStateException("operand stack underflow");
            int slot = locals + depth - 1;
            Scalar result = readSlot(this, slot);
            writeBytes(this, HEADER_BYTES + slot * SLOT_BYTES, new byte[SLOT_BYTES]);
            storeLong(this, 8, depth - 1);
            return result;
        }
        public int operandDepth() {
            checkTop(this);
            return readBoundedInt(this, 8, maxOperands, "operand height");
        }
        public int localsCount() { return locals; }
    }

    private final ActorFrameArena arena;
    private final ExecutionContext execution;
    private final ArrayDeque<Frame> frames = new ArrayDeque<>();
    private int peakFrames;
    private boolean closed;

    public ScalarFrameStorage() {
        this.arena = ActorRuntime.currentActorFrameArena();
        if (arena == null) {
            execution = null;
        } else {
            ExecutionContext guarded = GuestCallDepthGuard.currentInvocationExecutionContext();
            ExecutionContext scheduled = OresScheduler.currentExecutionContext();
            if (guarded != null) {
                if (!arena.allocationDomain().equals(guarded.allocationDomain())) {
                    arena.poisonFromGuestGuard();
                    throw new SecurityException("foreign invocation frame domain");
                }
                execution = guarded;
            } else if (scheduled != null
                    && arena.allocationDomain().equals(scheduled.allocationDomain())) {
                execution = scheduled;
            } else if (scheduled != null
                    && scheduled.allocationDomain().kind()
                        == ExecutionContext.AllocationKind.ACTOR) {
                arena.poisonFromGuestGuard();
                throw new SecurityException("foreign actor scheduler allocation domain");
            } else {
                execution = arena.newSynchronousExecution();
            }
        }
    }

    public Frame enter(int localSlots, int operandSlots) {
        if (closed) throw new IllegalStateException("closed scalar frame storage");
        if (localSlots < 0 || operandSlots < 0
                || localSlots + operandSlots > MAX_SLOTS) {
            throw new IllegalArgumentException("invalid scalar frame layout");
        }
        if (arena == null && frames.size() >= MAX_ROOT_FRAMES) {
            throw new IllegalStateException("bounded non-actor scalar call depth exceeded");
        }
        int byteSize = HEADER_BYTES + SLOT_BYTES * (localSlots + operandSlots);
        ActorFrameArena.FrameRef lease = arena == null ? null : arena.push(execution, byteSize);
        Frame frame = new Frame(lease, arena == null ? new byte[byteSize] : null,
                localSlots, operandSlots);
        frames.addLast(frame);
        peakFrames = Math.max(peakFrames, frames.size());
        return frame;
    }

    public void leave(Frame frame) {
        checkTop(frame);
        // ActorFrameArena.pop authenticates the logical execution and zeroes
        // the whole frame even when an actor is poisoned by a quota failure.
        if (arena != null) arena.pop(execution, frame.lease);
        else Arrays.fill(frame.local, (byte) 0);
        frame.closed = true;
        frames.removeLast();
    }

    public int liveFrames() { return frames.size(); }
    public int peakFrames() { return peakFrames; }
    public boolean actorOwned() { return arena != null; }

    private void checkTop(Frame frame) {
        Objects.requireNonNull(frame);
        if (closed || frame.closed || frames.peekLast() != frame) {
            // A host callback must not suppress a scalar frame authority
            // violation and silently resume the next actor mailbox turn.
            if (arena != null) arena.poisonFromGuestGuard();
            throw new IllegalStateException("stale or out-of-order scalar frame lease");
        }
    }

    private void writeSlot(Frame frame, int slot, Scalar scalar) {
        Objects.requireNonNull(scalar);
        byte[] word = new byte[SLOT_BYTES];
        word[0] = scalar.tag();
        ByteBuffer.wrap(word).order(ByteOrder.BIG_ENDIAN).putLong(8, scalar.bits());
        writeBytes(frame, HEADER_BYTES + slot * SLOT_BYTES, word);
    }

    private Scalar readSlot(Frame frame, int slot) {
        byte[] word = readBytes(frame, HEADER_BYTES + slot * SLOT_BYTES, SLOT_BYTES);
        try {
            return new Scalar(word[0],
                    ByteBuffer.wrap(word).order(ByteOrder.BIG_ENDIAN).getLong(8));
        } catch (IllegalArgumentException invalidTag) {
            if (arena != null) arena.poisonFromGuestGuard();
            throw invalidTag;
        }
    }

    private int readBoundedInt(Frame frame, int offset, int max, String field) {
        long value = ByteBuffer.wrap(readBytes(frame, offset, 8))
                .order(ByteOrder.BIG_ENDIAN).getLong();
        if (value < 0 || value > max) {
            if (arena != null) arena.poisonFromGuestGuard();
            throw new IllegalStateException("corrupt scalar " + field + " in owned frame");
        }
        return (int) value;
    }

    private void storeLong(Frame frame, int offset, long value) {
        writeBytes(frame, offset, ByteBuffer.allocate(8)
                .order(ByteOrder.BIG_ENDIAN).putLong(value).array());
    }

    private void writeBytes(Frame frame, int offset, byte[] bytes) {
        if (arena != null) arena.write(execution, frame.lease, offset, bytes);
        else System.arraycopy(bytes, 0, frame.local, offset, bytes.length);
    }
    private byte[] readBytes(Frame frame, int offset, int len) {
        if (arena != null) return arena.read(execution, frame.lease, offset, len);
        return Arrays.copyOfRange(frame.local, offset, offset + len);
    }

    @Override public void close() {
        if (closed) return;
        try {
            while (!frames.isEmpty()) leave(frames.peekLast());
        } finally {
            closed = true;
        }
    }
}
