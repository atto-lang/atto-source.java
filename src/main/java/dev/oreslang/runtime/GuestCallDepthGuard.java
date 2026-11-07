package dev.oreslang.runtime;

import java.util.ArrayDeque;

/**
 * Dual-boundary admission for synchronous interpreter guest invocations.
 *
 * <p>The carrier has a native recursion cap and each actor incarnation owns
 * both a logical invocation count and a bounded managed byte-frame store.
 * Every guarded non-tail guest call reserves one actor frame and unwinds it in
 * exact LIFO order. The reserved bytes DO NOT yet hold compiler-lowered guest
 * locals, return continuations, or suspended SourceTask state. The JVM carrier
 * still executes the interpreter and requires a separate native stack guard.
 *
 * <p>Call-chain identities are new for each outermost guarded synchronous
 * call and never travel through await. Actor migration and continuation
 * ownership remain separate compiler/scheduler obligations.
 */
public final class GuestCallDepthGuard {
    public static final int MAX_SYNCHRONOUS_GUEST_CALLS = 64;
    /** Bootstrap accounting reservation, NOT a compiled guest-frame layout. */
    static final int MANAGED_FRAME_CHARGE_BYTES = 32;

    private static final class Scope {
        final ActorGuestStack actorStack;
        final ActorFrameArena arena;
        final ExecutionContext execution;
        final ArrayDeque<ActorFrameArena.FrameRef> frames = new ArrayDeque<>();
        int depth;

        Scope(ActorGuestStack actorStack, ActorFrameArena arena) {
            this.actorStack = actorStack;
            this.arena = arena;
            this.execution = arena == null ? null : arena.newSynchronousExecution();
        }
    }

    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    private GuestCallDepthGuard() { }

    /**
     * Admit one non-tail synchronous guest invocation before any additional
     * carrier recursion. The actor's managed-byte frame lease is released by
     * the paired leave() even if guest execution throws.
     *
     * @return exact nested-depth token required by leave()
     */
    public static int enter() {
        ActorGuestStack actorStack = ActorRuntime.currentActorGuestStack();
        ActorFrameArena arena = ActorRuntime.currentActorFrameArena();
        Scope scope = CURRENT.get();
        if ((actorStack == null) != (arena == null)
                || (scope != null
                && (scope.actorStack != actorStack || scope.arena != arena))) {
            if (scope != null && scope.actorStack != null) scope.actorStack.poison();
            if (scope != null && scope.arena != null) scope.arena.poisonFromGuestGuard();
            if (actorStack != null) actorStack.poison();
            if (arena != null) arena.poisonFromGuestGuard();
            CURRENT.remove();
            throw new IllegalStateException(
                    "actor execution context changed inside a live guest invocation");
        }

        int prior = scope == null ? 0 : scope.depth;
        if (prior >= MAX_SYNCHRONOUS_GUEST_CALLS) {
            if (actorStack != null) actorStack.poison();
            if (arena != null) arena.poisonFromGuestGuard();
            throw new GuestCallDepthExceededException(MAX_SYNCHRONOUS_GUEST_CALLS);
        }
        if (scope == null) scope = new Scope(actorStack, arena);

        // Reserve the logical invocation first. If byte admission fails, undo
        // only this reservation, poison the actor and leave outer frames for
        // their own finally/unwind paths.
        int chargedDepth;
        try {
            chargedDepth = actorStack == null ? 0 : actorStack.reserveFrame();
        } catch (RuntimeException failure) {
            if (arena != null) arena.poisonFromGuestGuard();
            throw failure;
        }
        try {
            if (arena != null) {
                scope.frames.addLast(
                        arena.push(scope.execution, MANAGED_FRAME_CHARGE_BYTES));
            }
        } catch (RuntimeException failure) {
            if (actorStack != null) {
                try {
                    actorStack.releaseFrame(chargedDepth);
                } catch (RuntimeException unwindFailure) {
                    failure.addSuppressed(unwindFailure);
                }
                actorStack.poison();
            }
            if (arena != null) arena.poisonFromGuestGuard();
            throw failure;
        }

        scope.depth = prior + 1;
        CURRENT.set(scope);
        return scope.depth;
    }

    /** Pop the actor-owned frame before releasing its logical guest charge. */
    public static void leave(int enteredDepth) {
        Scope scope = CURRENT.get();
        if (enteredDepth <= 0 || scope == null || scope.depth != enteredDepth
                || scope.actorStack != ActorRuntime.currentActorGuestStack()
                || scope.arena != ActorRuntime.currentActorFrameArena()) {
            if (scope != null && scope.actorStack != null) scope.actorStack.poison();
            if (scope != null && scope.arena != null) scope.arena.poisonFromGuestGuard();
            CURRENT.remove();
            throw new IllegalStateException(
                    "unbalanced synchronous guest-call guard on carrier");
        }
        try {
            if (scope.arena != null) {
                ActorFrameArena.FrameRef ref = scope.frames.peekLast();
                if (ref == null) {
                    throw new IllegalStateException("missing managed guest-frame lease");
                }
                scope.arena.pop(scope.execution, ref);
                scope.frames.removeLast();
            }
            if (scope.actorStack != null) scope.actorStack.releaseFrame(enteredDepth);
            scope.depth--;
        } catch (RuntimeException failure) {
            if (scope.actorStack != null) scope.actorStack.poison();
            if (scope.arena != null) scope.arena.poisonFromGuestGuard();
            CURRENT.remove();
            throw failure;
        }
        if (scope.depth == 0) CURRENT.remove();
    }
}
