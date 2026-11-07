package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded, actor-incarnation-owned storage for compiler-lowered guest frames.
 *
 * <p>There is no native stack switching here: the bytes are in managed JVM
 * arrays until a backend lowers frame locals and return continuations into
 * this store. No guest pointer or heap object may be stored in a frame. The
 * opaque FrameRef is a host-only lease tied to this arena and one logical
 * ExecutionContext. A suspension retains all charged bytes while releasing
 * the carrier. Actor retirement requires the scheduler to prove quiescence.
 */
final class ActorFrameArena {
    static final int DEFAULT_MAX_FRAMES = 64;
    static final int DEFAULT_MAX_BYTES = 64 * 1024;

    /** Non-serializable frame capability; never expose to guest or transport. */
    static final class FrameRef {
        private final ActorFrameArena arena;
        private final ExecutionContext.ExecutionId execution;
        private final long nonce;

        private FrameRef(ActorFrameArena arena, ExecutionContext.ExecutionId execution,
                         long nonce) {
            this.arena = arena;
            this.execution = execution;
            this.nonce = nonce;
        }
    }

    private static final class Frame {
        final FrameRef ref;
        final byte[] bytes;
        Frame(FrameRef ref, int size) {
            this.ref = ref;
            this.bytes = new byte[size];
        }
    }

    private final ExecutionContext.AllocationDomain owner;
    private final int maxFrames;
    private final int maxBytes;
    private final Map<ExecutionContext.ExecutionId, ArrayDeque<Frame>> stacks = new HashMap<>();
    private final Map<ExecutionContext.ExecutionId, FrameRef> suspended = new HashMap<>();
    private long nonce = 1;
    private int frames;
    private int usedBytes;
    private int peakBytes;
    private boolean poisoned;
    private boolean retired;

    ActorFrameArena(ExecutionContext.AllocationDomain owner, int maxFrames, int maxBytes) {
        this.owner = Objects.requireNonNull(owner, "owner");
        if (owner.kind() != ExecutionContext.AllocationKind.ACTOR)
            throw new IllegalArgumentException("actor frame arena needs an actor domain");
        if (maxFrames < 1 || maxFrames > 4096 || maxBytes < 1 || maxBytes > (1 << 24))
            throw new IllegalArgumentException("frame/byte quotas outside bounded range");
        this.maxFrames = maxFrames;
        this.maxBytes = maxBytes;
    }

    /** Runtime-only domain for minting a matching logical ExecutionContext. */
    ExecutionContext.AllocationDomain allocationDomain() { return owner; }

    /**
     * Mint one logical identity for a synchronous interpreter call chain.
     * Never reuse an execution ID within an actor incarnation. This is NOT a
     * SourceTask continuation identity and must not be resumed across await.
     */
    private long nextSynchronousExecution = 1L;

    synchronized ExecutionContext newSynchronousExecution() {
        if (retired) throw new IllegalStateException("retired actor frame arena");
        requireExecutable();
        if (nextSynchronousExecution == Long.MAX_VALUE) {
            poisoned = true;
            throw new BudgetExceeded("actor synchronous execution IDs exhausted");
        }
        return ExecutionContext.actor(owner.domain(), nextSynchronousExecution++,
                owner.domain(), owner.owner(), owner.generation(), 0, 0);
    }

    private IllegalStateException invalidProtocol(String reason) {
        poisoned = true;
        return new IllegalStateException(reason);
    }

    private void requireOwner(ExecutionContext execution) {
        Objects.requireNonNull(execution, "execution");
        if (!owner.equals(execution.allocationDomain()))
            throw new SecurityException("foreign or stale actor frame domain");
        if (retired) throw new IllegalStateException("retired actor frame arena");
    }

    private void requireExecutable() {
        if (poisoned) throw new BudgetExceeded("poisoned actor frame arena");
    }

    private ArrayDeque<Frame> requireStack(ExecutionContext execution) {
        var stack = stacks.get(execution.id());
        if (stack == null || stack.isEmpty())
            throw invalidProtocol("missing or retired logical execution frames");
        return stack;
    }

    private Frame requireTop(ExecutionContext execution, FrameRef ref, boolean requireRunning) {
        requireOwner(execution);
        Objects.requireNonNull(ref, "ref");
        if (ref.arena != this || !ref.execution.equals(execution.id()))
            throw new SecurityException("foreign execution frame capability");
        var stack = requireStack(execution);
        Frame frame = stack.peekLast();
        if (frame.ref != ref)
            throw invalidProtocol("non-LIFO or stale frame capability");
        boolean asleep = suspended.containsKey(execution.id());
        if (requireRunning && asleep)
            throw invalidProtocol("suspended execution cannot access frames");
        return frame;
    }

    synchronized FrameRef push(ExecutionContext execution, int byteCount) {
        requireOwner(execution);
        if (poisoned) throw new BudgetExceeded("poisoned actor frame arena");
        if (suspended.containsKey(execution.id()))
            throw invalidProtocol("cannot push onto a suspended logical stack");
        if (byteCount <= 0) throw new IllegalArgumentException("frame must have positive byte size");
        if (frames >= maxFrames || byteCount > maxBytes - usedBytes || nonce == Long.MAX_VALUE) {
            poisoned = true;
            throw new BudgetExceeded("actor frame or byte quota exhausted");
        }
        FrameRef ref = new FrameRef(this, execution.id(), nonce++);
        Frame frame = new Frame(ref, byteCount);
        stacks.computeIfAbsent(execution.id(), unused -> new ArrayDeque<>()).addLast(frame);
        frames++;
        usedBytes += byteCount;
        peakBytes = Math.max(peakBytes, usedBytes);
        return ref;
    }

    synchronized void write(ExecutionContext execution, FrameRef ref, int offset, byte[] bytes) {
        requireOwner(execution);
        requireExecutable();
        Frame frame = requireTop(execution, ref, true);
        Objects.requireNonNull(bytes, "bytes");
        if (offset < 0 || offset > frame.bytes.length || bytes.length > frame.bytes.length - offset)
            throw new IndexOutOfBoundsException("frame write exceeds capacity");
        System.arraycopy(bytes, 0, frame.bytes, offset, bytes.length);
    }

    synchronized byte[] read(ExecutionContext execution, FrameRef ref, int offset, int length) {
        requireOwner(execution);
        requireExecutable();
        Frame frame = requireTop(execution, ref, true);
        if (offset < 0 || length < 0 || offset > frame.bytes.length || length > frame.bytes.length - offset)
            throw new IndexOutOfBoundsException("frame read exceeds capacity");
        return Arrays.copyOfRange(frame.bytes, offset, offset + length);
    }

    /** Suspend a whole logical execution: parent frames stay allocated and charged. */
    synchronized void suspend(ExecutionContext execution, FrameRef top) {
        requireOwner(execution);
        requireExecutable();
        requireTop(execution, top, true);
        suspended.put(execution.id(), top);
    }

    /** Resume only the exact head frame and logical identity, on any carrier. */
    synchronized void resume(ExecutionContext execution, FrameRef top) {
        requireTop(execution, top, false);
        if (poisoned) throw new BudgetExceeded("poisoned actor frame arena");
        if (suspended.get(execution.id()) != top)
            throw invalidProtocol("continuation resume without exact suspension");
        suspended.remove(execution.id());
    }

    synchronized void pop(ExecutionContext execution, FrameRef ref) {
        Frame frame = requireTop(execution, ref, true);
        var stack = requireStack(execution);
        stack.removeLast();
        Arrays.fill(frame.bytes, (byte) 0);
        frames--;
        usedBytes -= frame.bytes.length;
        if (stack.isEmpty()) stacks.remove(execution.id());
    }

    /** Scheduler-owned cancellation; releases every frame of the named task once. */
    synchronized void cancel(ExecutionContext execution) {
        requireOwner(execution);
        suspended.remove(execution.id());
        var stack = stacks.remove(execution.id());
        if (stack == null) return;
        for (var frame : stack) {
            Arrays.fill(frame.bytes, (byte) 0);
            frames--;
            usedBytes -= frame.bytes.length;
        }
    }

    /** Called only after scheduler quiescence, never during a physical turn. */
    synchronized void retireQuiesced() {
        if (retired) return;
        for (var stack : stacks.values()) {
            for (var frame : stack) Arrays.fill(frame.bytes, (byte) 0);
        }
        stacks.clear();
        suspended.clear();
        frames = 0;
        usedBytes = 0;
        poisoned = true;
        retired = true;
    }

    synchronized int usedBytes() { return usedBytes; }
    synchronized int liveFrames() { return frames; }
    synchronized int peakBytes() { return peakBytes; }
    /** A synchronous guest guard breach is terminal for the same incarnation. */
    synchronized void poisonFromGuestGuard() {
        if (!retired) poisoned = true;
    }

    synchronized boolean isPoisoned() { return poisoned; }
    synchronized boolean isRetired() { return retired; }

    /** Stackless terminal runtime budget failure, not JVM StackOverflowError. */
    static final class BudgetExceeded extends RuntimeException {
        private static final long serialVersionUID = 1L;
        BudgetExceeded(String message) { super(message, null, false, false); }
    }
}
