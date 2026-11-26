package dev.oreslang.runtime;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Single owner for the ordered actor-owned call-frame chain of one SourceTask.
 *
 * <p>The chain shares the SourceTask's ActorFrameArena stack. It therefore
 * suspends/resumes exactly one top-frame capability for the whole chain; it
 * never independently suspends a parent or child frame. The scheduler-bound
 * ActorSourceTaskFrame invokes the lifecycle hooks after physical admission.
 *
 * <p>This is a host/compiler bridge and deliberately is not guest-call lowering.
 * Frame images contain only scalar ABI metadata and tagged scalar cells; no
 * pointer, closure, Java stack address, or producer callback is stored here.
 */
final class ActorCallContinuation {
    private static final int MAGIC = 0x41434331; // ACC1
    private static final int HEADER_BYTES = 28;
    private static final int MAX_SLOTS = 4096;

    private enum State { RUNNING, SUSPENDED, RETIRED }

    private record Activation(
            int functionId,
            int localCount,
            int operandCapacity,
            ActorFrameArena.FrameRef ref) { }

    /** Opaque, identity-compared, one-shot scheduler resume witness. */
    private static final class ResumeTicket {
        final ActorCallContinuation owner;
        final long actorGeneration;
        final long sourceDispatch;
        final long sequence;

        ResumeTicket(ActorCallContinuation owner, long actorGeneration,
                     long sourceDispatch, long sequence) {
            this.owner = owner;
            this.actorGeneration = actorGeneration;
            this.sourceDispatch = sourceDispatch;
            this.sequence = sequence;
        }
    }

    private final ActorSourceTaskFrame sourceTask;
    private final ActorFrameArena arena;
    private final ExecutionContext execution;
    private final ActorFrameArena.FrameRef taskRoot;
    private final ArrayList<Activation> activations = new ArrayList<>();
    private State state = State.RUNNING;
    private ResumeTicket pendingTicket;
    private long nextTicket = 1;

    ActorCallContinuation(ActorSourceTaskFrame sourceTask,
                          ActorFrameArena arena,
                          ExecutionContext execution,
                          ActorFrameArena.FrameRef taskRoot) {
        this.sourceTask = Objects.requireNonNull(sourceTask, "sourceTask");
        this.arena = Objects.requireNonNull(arena, "arena");
        this.execution = Objects.requireNonNull(execution, "execution");
        this.taskRoot = Objects.requireNonNull(taskRoot, "taskRoot");
        if (sourceTask.arena() != arena
                || sourceTask.executionContext() != execution
                || !arena.allocationDomain().equals(execution.allocationDomain())) {
            throw new SecurityException("call continuation is not bound to its exact actor task");
        }
    }

    private void requireTurn() {
        sourceTask.requireContinuationTurn();
        if (state == State.RETIRED) {
            throw new IllegalStateException("retired actor call continuation");
        }
        if (state != State.RUNNING) {
            throw new IllegalStateException("suspended actor call continuation is not accessible");
        }
    }

    private Activation top() {
        return activations.isEmpty() ? null : activations.get(activations.size() - 1);
    }

    private ActorFrameArena.FrameRef topRef() {
        Activation top = top();
        return top == null ? taskRoot : top.ref();
    }

    /**
     * Push one bounded typed scalar activation. Local and operand cells use
     * GuestScalarFrameCodec's canonical 9-byte representation. The parent
     * remains below the child in the same arena stack and is not separately
     * suspendable.
     */
    synchronized void pushFrame(int functionId, int returnPc,
                                int localCount, int operandCapacity) {
        requireTurn();
        if (functionId < 0 || returnPc < 0 || localCount < 0
                || operandCapacity < 0 || (long) localCount + operandCapacity > MAX_SLOTS) {
            throw new IllegalArgumentException("invalid actor call-frame layout");
        }
        long byteCount = HEADER_BYTES
                + ((long) localCount + operandCapacity) * GuestScalarFrameCodec.SLOT_BYTES;
        if (byteCount > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("actor call frame exceeds representable size");
        }
        ActorFrameArena.FrameRef ref = arena.push(execution, (int) byteCount);
        try {
            byte[] header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.BIG_ENDIAN)
                    .putInt(MAGIC)
                    .putInt(functionId)
                    .putInt(0) // program counter
                    .putInt(returnPc)
                    .putInt(localCount)
                    .putInt(operandCapacity)
                    .putInt(0) // operand stack pointer
                    .array();
            arena.write(execution, ref, 0, header);
            activations.add(new Activation(functionId, localCount, operandCapacity, ref));
        } catch (RuntimeException | Error failure) {
            arena.cancel(execution);
            activations.clear();
            throw failure;
        }
    }

    synchronized void setProgramCounter(int pc) {
        requireTurn();
        if (pc < 0) throw new IllegalArgumentException("negative actor call-frame PC");
        Activation frame = requireTopActivation();
        ByteBuffer header = readHeader(frame);
        header.putInt(8, pc);
        writeHeader(frame, header);
    }

    synchronized int programCounter() {
        requireTurn();
        return readHeader(requireTopActivation()).getInt(8);
    }

    synchronized int functionId() {
        requireTurn();
        return requireTopActivation().functionId();
    }

    synchronized int returnProgramCounter() {
        requireTurn();
        return readHeader(requireTopActivation()).getInt(12);
    }

    synchronized void writeLocal(int index, Object value) {
        requireTurn();
        Activation frame = requireTopActivation();
        if (index < 0 || index >= frame.localCount()) {
            throw new IndexOutOfBoundsException("actor call-frame local index");
        }
        writeScalar(frame, HEADER_BYTES + index * GuestScalarFrameCodec.SLOT_BYTES, value);
    }

    synchronized Object readLocal(int index) {
        requireTurn();
        Activation frame = requireTopActivation();
        if (index < 0 || index >= frame.localCount()) {
            throw new IndexOutOfBoundsException("actor call-frame local index");
        }
        return readScalar(frame, HEADER_BYTES + index * GuestScalarFrameCodec.SLOT_BYTES);
    }

    synchronized void pushOperand(Object value) {
        requireTurn();
        Activation frame = requireTopActivation();
        ByteBuffer header = readHeader(frame);
        int sp = header.getInt(24);
        if (sp < 0 || sp >= frame.operandCapacity()) {
            throw corrupt("actor call-frame operand stack overflow");
        }
        int index = frame.localCount() + sp;
        writeScalar(frame, HEADER_BYTES + index * GuestScalarFrameCodec.SLOT_BYTES, value);
        header.putInt(24, sp + 1);
        writeHeader(frame, header);
    }

    synchronized Object popOperand() {
        requireTurn();
        Activation frame = requireTopActivation();
        ByteBuffer header = readHeader(frame);
        int sp = header.getInt(24);
        if (sp <= 0 || sp > frame.operandCapacity()) {
            throw corrupt("actor call-frame operand stack underflow");
        }
        int index = frame.localCount() + sp - 1;
        int offset = HEADER_BYTES + index * GuestScalarFrameCodec.SLOT_BYTES;
        Object value = readScalar(frame, offset);
        arena.write(execution, frame.ref(), offset,
                new byte[GuestScalarFrameCodec.SLOT_BYTES]);
        header.putInt(24, sp - 1);
        writeHeader(frame, header);
        return value;
    }

    synchronized int depth() {
        requireTurn();
        return activations.size();
    }

    /** Internal collision guard for the separate SourceTask root scalar region. */
    synchronized boolean hasGuestFrames() {
        return !activations.isEmpty();
    }

    synchronized int chargedBytes() {
        requireTurn();
        int bytes = 0;
        for (Activation activation : activations) {
            bytes = Math.addExact(bytes, HEADER_BYTES
                    + (activation.localCount() + activation.operandCapacity())
                    * GuestScalarFrameCodec.SLOT_BYTES);
        }
        return bytes;
    }

    /** Pop only the active child; the SourceTask root is owned by its parent. */
    synchronized void popFrame() {
        requireTurn();
        Activation frame = requireTopActivation();
        arena.pop(execution, frame.ref());
        activations.remove(activations.size() - 1);
    }

    private Activation requireTopActivation() {
        Activation frame = top();
        if (frame == null) throw new IllegalStateException("actor call chain has no guest activation");
        return frame;
    }

    private ByteBuffer readHeader(Activation frame) {
        byte[] bytes = arena.read(execution, frame.ref(), 0, HEADER_BYTES);
        ByteBuffer header = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        if (header.getInt(0) != MAGIC
                || header.getInt(4) != frame.functionId()
                || header.getInt(16) != frame.localCount()
                || header.getInt(20) != frame.operandCapacity()
                || header.getInt(8) < 0 || header.getInt(12) < 0
                || header.getInt(24) < 0
                || header.getInt(24) > frame.operandCapacity()) {
            throw corrupt("actor call-frame header mismatch");
        }
        return header;
    }

    private void writeHeader(Activation frame, ByteBuffer header) {
        arena.write(execution, frame.ref(), 0, header.array());
    }

    private void writeScalar(Activation frame, int offset, Object value) {
        byte[] encoded = GuestScalarFrameCodec.encode(List.of(value));
        if (encoded == null || encoded.length != GuestScalarFrameCodec.SLOT_BYTES
                || !(value instanceof Long || value instanceof Boolean)) {
            throw new IllegalArgumentException("actor call frames accept tagged long/bool scalars only");
        }
        arena.write(execution, frame.ref(), offset, encoded);
    }

    private Object readScalar(Activation frame, int offset) {
        byte[] encoded = arena.read(execution, frame.ref(), offset,
                GuestScalarFrameCodec.SLOT_BYTES);
        try {
            return GuestScalarFrameCodec.decode(encoded).getFirst();
        } catch (RuntimeException malformed) {
            throw corrupt("invalid actor call-frame scalar slot");
        }
    }

    private IllegalStateException corrupt(String message) {
        arena.poisonFromGuestGuard();
        return new IllegalStateException(message);
    }

    /** Called only by ActorSourceTaskFrame after the guest turn has unwound. */
    synchronized void suspendFromScheduler() {
        sourceTask.requireContinuationTurn();
        if (state != State.RUNNING || pendingTicket != null) {
            throw new IllegalStateException("actor call continuation cannot suspend twice");
        }
        long dispatch = OresScheduler.currentDispatchId();
        if (dispatch <= 0) throw new SecurityException("call-chain suspension requires scheduler dispatch");
        if (nextTicket == Long.MAX_VALUE) throw corrupt("actor continuation ticket space exhausted");
        ResumeTicket ticket = new ResumeTicket(this,
                execution.allocationDomain().generation(), dispatch, nextTicket++);
        arena.suspend(execution, topRef());
        pendingTicket = ticket;
        state = State.SUSPENDED;
    }

    /** Called only by ActorSourceTaskFrame after fresh scheduler admission. */
    synchronized void resumeFromScheduler() {
        sourceTask.requireContinuationTurn();
        if (state != State.SUSPENDED || pendingTicket == null) {
            throw new IllegalStateException("actor call continuation has no suspended ticket");
        }
        ResumeTicket ticket = pendingTicket;
        if (ticket.owner != this
                || ticket.actorGeneration != execution.allocationDomain().generation()
                || ticket.sequence != nextTicket - 1
                || ticket.sourceDispatch == OresScheduler.currentDispatchId()) {
            throw new SecurityException("stale or forged actor call continuation ticket");
        }
        arena.resume(execution, topRef());
        pendingTicket = null;
        state = State.RUNNING;
    }

    /** Invalidates host handles; arena.cancel performs the one physical zeroization. */
    synchronized void retireAfterQuiescence() {
        if (state == State.RETIRED) return;
        state = State.RETIRED;
        pendingTicket = null;
        activations.clear();
    }

    synchronized boolean suspended() { return state == State.SUSPENDED; }
    synchronized boolean retired() { return state == State.RETIRED; }
}
