package dev.oreslang.runtime;

/**
 * Dual-boundary admission for synchronous interpreter guest invocations.
 *
 * <p>The native JVM call stack belongs to a carrier, so every invocation
 * remains capped by a carrier-local depth. In an actor turn the same
 * invocation ALSO charges the actor cell's own logical frame budget. The actor
 * budget follows actor identity, not worker thread identity; a supervisor
 * restart gets a fresh actor stack. Proper tail hops remain inside one guarded
 * invocation. None of this makes arbitrary JVM/native stack faults recoverable.
 *
 * <p>The per-carrier scope is intentionally discarded after its final exit.
 * Binding checks reject accidental actor-to-actor context switches under live
 * Java frames, including stale scopes left by a failing turn.
 */
public final class GuestCallDepthGuard {
    public static final int MAX_SYNCHRONOUS_GUEST_CALLS = 64;

    private static final class Scope {
        final ActorGuestStack actorStack;
        int depth;

        Scope(ActorGuestStack actorStack) {
            this.actorStack = actorStack;
        }
    }

    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    private GuestCallDepthGuard() { }

    /**
     * Admit one non-tail guest invocation on both the carrier and, if present,
     * its actor-owned logical frame stack. Tail calls do not re-enter this API.
     * @return the exact nested-depth token required by leave()
     */
    public static int enter() {
        ActorGuestStack actorStack = ActorRuntime.currentActorGuestStack();
        Scope scope = CURRENT.get();
        if (scope != null && scope.actorStack != actorStack) {
            if (scope.actorStack != null) scope.actorStack.poison();
            if (actorStack != null) actorStack.poison();
            CURRENT.remove();
            throw new IllegalStateException(
                    "actor execution context changed inside a live guest invocation");
        }
        int prior = scope == null ? 0 : scope.depth;
        if (prior >= MAX_SYNCHRONOUS_GUEST_CALLS) {
            if (actorStack != null) actorStack.poison();
            throw new GuestCallDepthExceededException(MAX_SYNCHRONOUS_GUEST_CALLS);
        }
        // Charge the actor before advancing the carrier depth. The actor stack
        // refuses any further guest calls after its quota is exhausted.
        if (actorStack != null) actorStack.reserveFrame();
        if (scope == null) {
            scope = new Scope(actorStack);
            CURRENT.set(scope);
        }
        scope.depth = prior + 1;
        return scope.depth;
    }

    /**
     * Unwind exactly the caller's entry. A mismatch poisons the actor and
     * clears carrier state rather than silently transferring it to the next
     * scheduled actor or actor generation.
     */
    public static void leave(int enteredDepth) {
        Scope scope = CURRENT.get();
        if (enteredDepth <= 0 || scope == null || scope.depth != enteredDepth
                || scope.actorStack != ActorRuntime.currentActorGuestStack()) {
            // A malformed unwind with no carrier scope still belongs to the
            // current actor. Poison that incarnation even if trusted host
            // code catches this exception; never let the next mail run.
            ActorGuestStack current = ActorRuntime.currentActorGuestStack();
            if (scope != null && scope.actorStack != null) scope.actorStack.poison();
            if (current != null) current.poison();
            CURRENT.remove();
            throw new IllegalStateException(
                    "unbalanced synchronous guest-call guard on carrier");
        }
        try {
            if (scope.actorStack != null) scope.actorStack.releaseFrame(enteredDepth);
            scope.depth--;
        } catch (RuntimeException failure) {
            if (scope.actorStack != null) scope.actorStack.poison();
            CURRENT.remove();
            throw failure;
        }
        if (scope.depth == 0) CURRENT.remove();
    }
}
