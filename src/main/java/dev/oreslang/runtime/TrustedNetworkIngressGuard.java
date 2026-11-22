package dev.oreslang.runtime;

/**
 * Defense-in-depth at the ordinary host network entry point.
 *
 * Untrusted actor networking is denied until an explicitly authenticated,
 * quota-metered protected guest -> supervisor broker exists. This helper
 * does NOT grant NETWORK, create an untrusted bridge, or depend on heap/OS.
 * The trusted runtime's execution identity is authoritative; a guest-supplied
 * actor ID, envelope or endpoint argument is not.
 */
final class TrustedNetworkIngressGuard {
    private TrustedNetworkIngressGuard() { }

    static void rejectUntrustedDirectNetwork(String operation) {
        if (ActorRuntime.currentActorKind() == ActorRuntime.ActorKind.UNTRUSTED) {
            throw new SecurityException(
                    "UNTRUSTED actor direct " + operation
                    + " is denied; use a future authenticated quota-mediated broker");
        }
    }
}
