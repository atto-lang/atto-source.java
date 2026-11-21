package dev.oreslang.runtime;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/**
 * M3.2 pointer-free call/return record ABI for a FUTURE stackless guest
 * instruction dispatcher. It does not itself replace recursive Java calls.
 *
 * <p>Every record is charged to the exact actor incarnation. It contains a
 * compiler-provided call-site PC and return PC, a callee index, operand depth,
 * actor generation, owner and a unique logical execution number. No guest
 * object references, Java return addresses or host pointers are serialized.
 * This class is a runtime-only capability, never a guest-visible value.
 */
final class ActorGuestReturnRecord implements AutoCloseable {
    static final int RECORD_BYTES = 48;
    private static final int MAGIC = 0x4154544f; // ATTO
    private static final int VERSION = 1;

    record Cursor(int calleeIndex, int callSitePc, int returnPc, int operandDepth) {
        Cursor {
            if (calleeIndex < 0 || callSitePc < 0 || returnPc < 0 || operandDepth < 0) {
                throw new IllegalArgumentException("negative guest call/return cursor");
            }
        }
    }

    private final ActorFrameArena arena;
    private final ExecutionContext execution;
    private final ActorFrameArena.FrameRef handle;
    private final Thread carrier;
    private boolean closed;

    private ActorGuestReturnRecord(ActorFrameArena arena, ExecutionContext execution,
                                   ActorFrameArena.FrameRef handle) {
        this.arena = arena;
        this.execution = execution;
        this.handle = handle;
        this.carrier = Thread.currentThread();
    }

    static ActorGuestReturnRecord enter(Cursor cursor) {
        Objects.requireNonNull(cursor, "cursor");
        ActorFrameArena arena = ActorRuntime.currentActorFrameArena();
        if (arena == null) throw new SecurityException(
                "guest call/return record requires an actual actor-owned turn");
        ExecutionContext execution = arena.newSynchronousExecution();
        ActorFrameArena.FrameRef handle = arena.push(execution, RECORD_BYTES);
        try {
            arena.write(execution, handle, 0, encode(cursor, execution));
            return new ActorGuestReturnRecord(arena, execution, handle);
        } catch (RuntimeException | Error failure) {
            try {
                arena.pop(execution, handle);
            } catch (RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    /** No guest control transfer occurs here. The future dispatcher owns it. */
    Cursor readCursor() {
        requireOwnerTurn();
        byte[] data = arena.read(execution, handle, 0, RECORD_BYTES);
        try {
            ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
            int magic = b.getInt(), version = b.getInt();
            Cursor cursor = new Cursor(b.getInt(), b.getInt(), b.getInt(), b.getInt());
            long generation = b.getLong(), owner = b.getLong(), executionNumber = b.getLong();
            if (magic != MAGIC || version != VERSION
                    || generation != execution.allocationDomain().generation()
                    || owner != execution.allocationDomain().owner()
                    || executionNumber != execution.id().execution()) {
                throw new IllegalStateException(
                        "guest call/return descriptor generation or execution mismatch");
            }
            return cursor;
        } catch (RuntimeException failure) {
            arena.poisonFromGuestGuard();
            throw failure;
        }
    }

    private static byte[] encode(Cursor cursor, ExecutionContext execution) {
        return ByteBuffer.allocate(RECORD_BYTES).order(ByteOrder.BIG_ENDIAN)
                .putInt(MAGIC).putInt(VERSION)
                .putInt(cursor.calleeIndex()).putInt(cursor.callSitePc())
                .putInt(cursor.returnPc()).putInt(cursor.operandDepth())
                .putLong(execution.allocationDomain().generation())
                .putLong(execution.allocationDomain().owner())
                .putLong(execution.id().execution()).array();
    }

    private void requireOwnerTurn() {
        if (closed) throw new IllegalStateException("guest call/return record retired");
        if (Thread.currentThread() != carrier
                || ActorRuntime.currentActorFrameArena() != arena) {
            throw new SecurityException("foreign actor call/return record access");
        }
    }

    @Override
    public void close() {
        requireOwnerTurn();
        closed = true;
        // pop deterministically zeroizes every byte before return. Even an
        // already poisoned actor can release its own charged record.
        arena.pop(execution, handle);
    }
}
