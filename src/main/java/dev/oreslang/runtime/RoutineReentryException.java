package dev.oreslang.runtime;

/**
 * A direct-call-only routine was entered twice along one synchronous logical
 * invocation chain, including through fnc tail calls or callback dispatch.
 * This is a language-contract violation, not a trappable guest exception.
 */
public final class RoutineReentryException extends SecurityException {
    public RoutineReentryException(String routineName) {
        super("routine '" + routineName
                + "' cannot be recursive (directly or indirectly); dynamic reentry rejected");
    }
}
