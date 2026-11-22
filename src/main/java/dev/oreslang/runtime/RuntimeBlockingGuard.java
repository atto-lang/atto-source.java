package dev.oreslang.runtime;

/**
 * Tiny host-runtime guard for operations that may park a carrier.
 *
 * ActorRuntime marks the current physical actor carrier on entry and clears
 * it in a balanced finally block. Host-only blocking APIs fail closed while
 * the mark is present instead of relying on documentation or thread names.
 */
final class RuntimeBlockingGuard {
    private static final ThreadLocal<Integer> ACTOR_CARRIER_DEPTH =
            ThreadLocal.withInitial(() -> 0);

    private RuntimeBlockingGuard() { }

    static void enterActorCarrier() {
        int depth = ACTOR_CARRIER_DEPTH.get();
        if (depth == Integer.MAX_VALUE) {
            throw new IllegalStateException("actor carrier guard depth overflow");
        }
        ACTOR_CARRIER_DEPTH.set(depth + 1);
    }

    static void exitActorCarrier() {
        int depth = ACTOR_CARRIER_DEPTH.get();
        if (depth <= 0) {
            throw new IllegalStateException("actor carrier guard underflow");
        }
        if (depth == 1) ACTOR_CARRIER_DEPTH.remove();
        else ACTOR_CARRIER_DEPTH.set(depth - 1);
    }

    static void requireBlockingHostThread(String operation) {
        if (ACTOR_CARRIER_DEPTH.get() != 0) {
            throw new IllegalStateException(
                    operation + " cannot block an actor carrier");
        }
    }

    static boolean actorCarrierMarked() {
        return ACTOR_CARRIER_DEPTH.get() != 0;
    }
}
