package dev.oreslang.runtime;

/**
 * Conservative admission limit for synchronous guest invocation nesting on a
 * JVM carrier. A tail-call trampoline consumes only one entry regardless of
 * the number of tail hops.
 *
 * <p>This is a defence-in-depth guard, NOT a native stack reservation and NOT
 * proof that Java StackOverflowError is recoverable. All backend paths,
 * including host interop and source-expression nesting, still need an
 * independently bounded compiler/runtime frame model.</p>
 *
 * <p>State is carrier-thread-local, not actor-owned: only synchronous Java
 * frames are counted. Every enter must leave in a finally block before the
 * carrier runs another actor. Suspension releases the carrier stack.</p>
 */
public final class GuestCallDepthGuard {
    public static final int MAX_SYNCHRONOUS_GUEST_CALLS = 64;
    private static final ThreadLocal<Integer> DEPTH = new ThreadLocal<>();

    private GuestCallDepthGuard() { }

    /**
     * Reserve one nested synchronous guest invocation before evaluating it.
     * @return an exact nesting token that must be passed to leave()
     */
    public static int enter() {
        Integer current = DEPTH.get();
        int depth = current == null ? 0 : current;
        if (depth >= MAX_SYNCHRONOUS_GUEST_CALLS) {
            throw new GuestCallDepthExceededException(MAX_SYNCHRONOUS_GUEST_CALLS);
        }
        int enteredDepth = depth + 1;
        DEPTH.set(enteredDepth);
        return enteredDepth;
    }

    /**
     * Release only the caller's own entry. Never leave a poisoned depth on a
     * reused carrier, even when an internal scope mismatch is detected.
     */
    public static void leave(int enteredDepth) {
        Integer observed = DEPTH.get();
        if (enteredDepth <= 0 || observed == null || observed != enteredDepth) {
            DEPTH.remove();
            throw new IllegalStateException(
                    "unbalanced synchronous guest-call guard on carrier");
        }
        if (enteredDepth == 1) DEPTH.remove();
        else DEPTH.set(enteredDepth - 1);
    }
}
