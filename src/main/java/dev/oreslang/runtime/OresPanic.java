package dev.oreslang.runtime;

/**
 * Non-trappable Oreslang invariant failure.
 *
 * <p>Unlike an ordinary Java RuntimeException, this semantic signal must
 * bypass source try/catch and trap. ActorRuntime intercepts it at the
 * actor boundary, terminates only that actor subtree and exposes a typed
 * high-priority supervision notice.</p>
 */
public final class OresPanic extends RuntimeException {
    public OresPanic(String message) {
        super(message, null, true, false);
    }
}
