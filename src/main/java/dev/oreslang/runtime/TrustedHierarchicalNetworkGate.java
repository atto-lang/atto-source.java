package dev.oreslang.runtime;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * Trusted-only atomic child + supervisor + tenant network quota admission.
 *
 * <p>HOST-ONLY FOUNDATION, not a guest networking implementation. The broker
 * must bind these host-issued scopes to verified actor/supervisor/tenant
 * identity rather than take scope IDs from guest messages. Scope identity
 * survives actor reincarnation; policy creation is a privileged operation.
 * No ambient UNTRUSTED NETWORK capability is granted by this class.</p>
 *
 * <p>All scopes must be accessed through this gate for multi-scope atomicity.
 * Do not share their underlying quota with an independent admission path.
 * Actual protected guest egress, DNS authorization, socket lifetimes and
 * source-level integration are separate remaining requirements.</p>
 */
final class TrustedHierarchicalNetworkGate {
    static final class Scope implements AutoCloseable {
        private final TrustedHierarchicalNetworkGate gate;
        private final TrustedUntrustedNetworkQuota quota;
        private Scope(TrustedHierarchicalNetworkGate gate,
                      TrustedUntrustedNetworkQuota quota) {
            this.gate = gate;
            this.quota = quota;
        }
        @Override public void close() {
            requireHost();
            synchronized (gate.lock) { quota.close(); }
        }
    }

    static final class Lease implements AutoCloseable {
        private final TrustedUntrustedNetworkQuota.Lease child;
        private final TrustedUntrustedNetworkQuota.Lease supervisor;
        private final TrustedUntrustedNetworkQuota.Lease tenant;
        // Reserved=0; dispatched=1; settled=2.
        private final AtomicInteger state = new AtomicInteger();

        private Lease(TrustedUntrustedNetworkQuota.Lease child,
                      TrustedUntrustedNetworkQuota.Lease supervisor,
                      TrustedUntrustedNetworkQuota.Lease tenant) {
            this.child = child;
            this.supervisor = supervisor;
            this.tenant = tenant;
        }

        /** Must be called by the trusted broker just before I/O submission. */
        boolean commitDispatch() {
            if (!state.compareAndSet(0, 1)) return false;
            if (!child.commitDispatch() || !supervisor.commitDispatch()
                    || !tenant.commitDispatch()) {
                throw new IllegalStateException("hierarchical lease corrupted");
            }
            return true;
        }

        /** Cancel before dispatch and refund all three scopes exactly once. */
        boolean abortBeforeDispatch() {
            if (!state.compareAndSet(0, 2)) return false;
            if (!child.abortBeforeDispatch()
                    || !supervisor.abortBeforeDispatch()
                    || !tenant.abortBeforeDispatch()) {
                throw new IllegalStateException("hierarchical rollback corrupted");
            }
            return true;
        }

        @Override public void close() {
            if (state.getAndSet(2) == 2) return;
            child.close();
            supervisor.close();
            tenant.close();
        }
    }

    record Admission(TrustedUntrustedNetworkQuota.Status status, Lease lease) {
        Admission {
            Objects.requireNonNull(status);
            if ((status == TrustedUntrustedNetworkQuota.Status.ADMITTED) != (lease != null)) {
                throw new IllegalArgumentException("invalid hierarchical admission");
            }
        }
        private static Admission denied(TrustedUntrustedNetworkQuota.Status status) {
            return new Admission(status, null);
        }
    }

    private final Object lock = new Object();

    Scope createHostScope(TrustedUntrustedNetworkQuota.Policy policy,
                          LongSupplier trustedClock) {
        requireHost();
        synchronized (lock) {
            return new Scope(this, new TrustedUntrustedNetworkQuota(policy, trustedClock));
        }
    }

    Admission tryStartHttp(Scope child, Scope supervisor, Scope tenant) {
        return tryAdmit(child, supervisor, tenant, true);
    }

    Admission tryOpenTcp(Scope child, Scope supervisor, Scope tenant) {
        return tryAdmit(child, supervisor, tenant, false);
    }

    private Admission tryAdmit(Scope child, Scope supervisor,
                               Scope tenant, boolean http) {
        requireHost();
        Objects.requireNonNull(child);
        Objects.requireNonNull(supervisor);
        Objects.requireNonNull(tenant);
        if (child == supervisor || child == tenant || supervisor == tenant
                || child.gate != this || supervisor.gate != this || tenant.gate != this) {
            throw new IllegalArgumentException("three distinct scopes from this gate required");
        }
        synchronized (lock) {
            TrustedUntrustedNetworkQuota.Admission first = attempt(child, http);
            if (first.lease() == null) return Admission.denied(first.status());
            TrustedUntrustedNetworkQuota.Admission second = null;
            TrustedUntrustedNetworkQuota.Admission third = null;
            boolean completed = false;
            try {
                second = attempt(supervisor, http);
                if (second.lease() == null) return Admission.denied(second.status());
                third = attempt(tenant, http);
                if (third.lease() == null) return Admission.denied(third.status());
                Lease lease = new Lease(first.lease(), second.lease(), third.lease());
                completed = true;
                return new Admission(TrustedUntrustedNetworkQuota.Status.ADMITTED, lease);
            } finally {
                // Also covers exceptions (including allocation failures).
                if (!completed) {
                    if (third != null && third.lease() != null) {
                        third.lease().abortBeforeDispatch();
                    }
                    if (second != null && second.lease() != null) {
                        second.lease().abortBeforeDispatch();
                    }
                    first.lease().abortBeforeDispatch();
                }
            }
        }
    }

    private static TrustedUntrustedNetworkQuota.Admission attempt(
            Scope scope, boolean http) {
        return http ? scope.quota.tryStartHttp() : scope.quota.tryOpenTcp();
    }

    /** The guest may never mint/submit its own Scope or lease. */
    private static void requireHost() {
        if (ActorRuntime.inActorExecution()
                || ActorRuntime.inActorGroupMailmanExecution()) {
            throw new SecurityException("network egress quota authority belongs to trusted host");
        }
    }
}
