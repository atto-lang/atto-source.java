package dev.oreslang.runtime;

import java.util.Objects;

/**
 * Actor-incarnation-owned logical stack root for one admitted source receive.
 *
 * <p>Unlike a physical carrier stack, the ExecutionContext remains stable while
 * an asynchronous source receive is suspended. Synchronous guest CALL/RET frames
 * live above this root in the same ActorFrameArena execution-ID stack. Task
 * continuations are minted separate execution IDs in the same actor arena.
 *
 * <p>Package-private trusted runtime seam. This is not native stack isolation,
 * a guest pointer, or compiler-lowered await inside a callee.
 */
final class ActorReceiveInvocation {
    static final int ROOT_BYTES = 64;

    private enum Phase { RUNNING, SUSPENDED, RETIRED }

    private final ActorFrameArena arena;
    private final ExecutionContext execution;
    private final ActorFrameArena.FrameRef root;
    private Phase phase = Phase.RUNNING;

    private ActorReceiveInvocation(
            ActorFrameArena arena,
            ExecutionContext execution,
            ActorFrameArena.FrameRef root) {
        this.arena = arena;
        this.execution = execution;
        this.root = root;
    }

    static ActorReceiveInvocation begin(ActorFrameArena arena) {
        Objects.requireNonNull(arena, "arena");
        ExecutionContext execution = arena.newSynchronousExecution();
        ActorFrameArena.FrameRef root = arena.push(execution, ROOT_BYTES);
        return new ActorReceiveInvocation(arena, execution, root);
    }

    synchronized ExecutionContext executionContext() {
        if (phase != Phase.RUNNING)
            throw new IllegalStateException("source receive invocation is not running");
        return execution;
    }

    synchronized void suspendAfterHandlerExit() {
        if (phase != Phase.RUNNING)
            throw new IllegalStateException("source receive invocation cannot suspend twice");
        arena.suspend(execution, root);
        phase = Phase.SUSPENDED;
    }

    /** Only the serialized actor continuation lane may call this method. */
    synchronized void completeSuspended() {
        if (phase != Phase.SUSPENDED)
            throw new IllegalStateException("source receive has no suspended invocation");
        try {
            arena.resume(execution, root);
            phase = Phase.RUNNING;
            finish();
        } catch (RuntimeException | Error failure) {
            cancelAfterFailure();
            throw failure;
        }
    }

    synchronized void finish() {
        if (phase != Phase.RUNNING)
            throw new IllegalStateException("source receive cannot retire before resume");
        try {
            // Fails closed if a compiler callee left a live child activation.
            arena.pop(execution, root);
        } catch (RuntimeException | Error failure) {
            cancelAfterFailure();
            throw failure;
        }
        phase = Phase.RETIRED;
    }

    synchronized void cancelAfterFailure() {
        if (phase == Phase.RETIRED) return;
        arena.cancel(execution);
        phase = Phase.RETIRED;
    }

    synchronized boolean suspended() { return phase == Phase.SUSPENDED; }
    synchronized boolean retired() { return phase == Phase.RETIRED; }
}
