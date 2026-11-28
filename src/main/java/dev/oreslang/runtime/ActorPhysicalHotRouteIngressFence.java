package dev.oreslang.runtime;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Physical actor ingress fencing for a CONTROL-owned logical route.
 *
 * <p>Claim is a one-way transition: an existing physical ActorRef permanently
 * loses raw DATA send authority. The runtime holds the claim under its physical
 * ActorCell lifecycle monitor and checks admission twice, including at physical
 * mailbox publication. A route-specific Permit is the only accepted sender.
 *
 * <p>The Permit is never a guest/source capability. It does not confer state,
 * supervisor, STOP, FFI, or physical code-image reclamation authority.
 */
final class ActorPhysicalHotRouteIngressFence {
    static final class Permit {
        private final ActorPhysicalHotRouteIngressFence issuer;
        private final AtomicBoolean retired = new AtomicBoolean();

        private Permit(ActorPhysicalHotRouteIngressFence issuer) {
            this.issuer = issuer;
        }

        /** Monotonic: retiring an old route never restores raw sends. */
        void retire() {
            retired.set(true);
        }

        boolean retired() { return retired.get(); }
    }

    private final AtomicReference<Permit> claimed = new AtomicReference<>();

    Permit claim() {
        Permit next = new Permit(this);
        if (!claimed.compareAndSet(null, next)) {
            throw new IllegalStateException("physical actor ingress already claimed by a hot route");
        }
        return next;
    }

    void require(Permit candidate) {
        Permit current = claimed.get();
        if (current == null) {
            if (candidate != null) {
                throw new SecurityException("foreign physical hot-route send permit");
            }
            return;
        }
        if (candidate != current || candidate.issuer != this || candidate.retired()) {
            throw new SecurityException(
                    "physical actor ingress is sealed; send through the logical ActorRef route");
        }
    }

    boolean claimed() { return claimed.get() != null; }
}
