package dev.oreslang.runtime;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * Trusted-runtime-only *building block* for UNTRUSTED outbound network budgets.
 *
 * <p>NOT yet wired to any protected guest, HTTP client, socket dialer, or
 * egress broker. In particular, it does not grant the currently denied
 * UNTRUSTED NETWORK capability, enforce tenant/lineage aggregate limits,
 * or make an in-process native actor sandboxed. Only the trusted broker
 * may hold quota/lease instances; never expose them as guest values.</p>
 *
 * <p>HTTP request-start rate and HTTP in-flight count are independent.
 * A TCP lease reserves an open/connecting socket slot and a connection-start
 * rate token. Guest-visible cancellation and failed dial attempts do not
 * replenish that token, even if I/O was not dispatched.
 * Callers must hold
 * the lease through the real I/O operation and release on every terminal
 * path. Cancellation must actually terminate or close host I/O before
 * releasing the corresponding slot.</p>
 */
final class TrustedUntrustedNetworkQuota implements AutoCloseable {
    record Policy(long httpStartsPerSecond, long httpBurst, int maxHttpInFlight,
                  int maxTcpConnections, long tcpStartsPerSecond, long tcpBurst) {
        /** Compatibility constructor; connection attempts remain rate-limited. */
        Policy(long httpStartsPerSecond, long httpBurst, int maxHttpInFlight,
               int maxTcpConnections) {
            this(httpStartsPerSecond, httpBurst, maxHttpInFlight,
                    maxTcpConnections, 10, 2);
        }
        Policy {
            if (httpStartsPerSecond <= 0 || httpStartsPerSecond > 1_000_000_000L
                    || httpBurst <= 0 || httpBurst > 1_000_000_000L
                    || maxHttpInFlight < 1 || maxHttpInFlight > 1_000_000
                    || maxTcpConnections < 1 || maxTcpConnections > 1_000_000
                    || tcpStartsPerSecond <= 0 || tcpStartsPerSecond > 1_000_000_000L
                    || tcpBurst <= 0 || tcpBurst > 1_000_000_000L) {
                throw new IllegalArgumentException("invalid UNTRUSTED network quota policy");
            }
        }
        static Policy initialDefault() {
            return new Policy(10, 2, 10, 10, 10, 2);
        }
    }

    enum Status { ADMITTED, RATE_LIMITED, TOO_MANY_IN_FLIGHT, TOO_MANY_CONNECTIONS, CLOSED }

    record Admission(Status status, Lease lease) {
        Admission {
            Objects.requireNonNull(status);
            if ((status == Status.ADMITTED) != (lease != null)) {
                throw new IllegalArgumentException("lease must match admission");
            }
        }
        static Admission denied(Status code) { return new Admission(code, null); }
    }

    static final class Lease implements AutoCloseable {
        private final TrustedUntrustedNetworkQuota owner;
        private final boolean http;
        // 0=reserved but not dispatched, 1=dispatched, 2=settled.
        // This makes pre-dispatch token refunds one-shot even under racing
        // cancellation/close. Never publish this lease to untrusted code.
        private final AtomicInteger lifecycle = new AtomicInteger();

        private Lease(TrustedUntrustedNetworkQuota owner, boolean http) {
            this.owner = owner;
            this.http = http;
        }

        /**
         * Trusted gate preflight. Caller holds its admission lock, and all
         * host Scope.close operations take that same lock. Check every scope
         * before changing ANY component of a hierarchical reservation.
         */
        boolean canCommitDispatch() {
            synchronized (owner) {
                return !owner.closed && lifecycle.get() == 0;
            }
        }

        /** Call immediately before performing external I/O. */
        boolean commitDispatch() {
            // A supervisor may revoke a quota after admission but before
            // dispatch. The same quota monitor fences close vs commitment.
            // After commitment the trusted broker must cancel/close actual IO
            // on revocation; this method cannot itself own an OS socket.
            synchronized (owner) {
                return !owner.closed && lifecycle.compareAndSet(0, 1);
            }
        }

        /**
         * Guest-visible reservation cancellation releases the concurrency
         * slot but deliberately does NOT refund its start-rate token.
         * Otherwise holding a reservation across a refill epoch, aborting,
         * and starting new requests could exceed the configured burst.
         */
        boolean abortBeforeDispatch() {
            if (!lifecycle.compareAndSet(0, 2)) return false;
            owner.release(http);
            return true;
        }

        /**
         * Trusted gate INTERNAL ONLY: revert a provisional reservation
         * that was never published because another scope denied admission.
         * Never expose this operation through a broker/guest API.
         */
        boolean rollbackUnpublished() {
            if (!lifecycle.compareAndSet(0, 2)) return false;
            owner.refundUnsent(http);
            return true;
        }

        /** Idempotent under cancellation / timeout / response-close races. */
        @Override public void close() {
            int previous = lifecycle.getAndSet(2);
            if (previous != 2) owner.release(http);
            // Conservative: close without explicit abort consumes start rate.
            // Otherwise failing I/O could restore tokens and enable floods.
        }
    }

    private final TrustedOutboundRateBucket httpStarts;
    private final TrustedOutboundRateBucket tcpStarts;
    private final int maxHttpInFlight;
    private final int maxTcpConnections;
    private int httpInFlight;
    private int tcpConnections;
    private boolean closed;

    TrustedUntrustedNetworkQuota(Policy policy, LongSupplier trustedMonotonicNanos) {
        Objects.requireNonNull(policy, "trusted network policy");
        Objects.requireNonNull(trustedMonotonicNanos, "trusted clock");
        this.httpStarts = new TrustedOutboundRateBucket(
                policy.httpStartsPerSecond(), policy.httpBurst(), trustedMonotonicNanos);
        this.tcpStarts = new TrustedOutboundRateBucket(
                policy.tcpStartsPerSecond(), policy.tcpBurst(), trustedMonotonicNanos);
        this.maxHttpInFlight = policy.maxHttpInFlight();
        this.maxTcpConnections = policy.maxTcpConnections();
    }

    /** Reserve before dispatch, without parking an actor/carrier. */
    synchronized Admission tryStartHttp() {
        if (closed) return Admission.denied(Status.CLOSED);
        if (httpInFlight >= maxHttpInFlight) {
            // Failed capacity admission must not burn request-start tokens.
            return Admission.denied(Status.TOO_MANY_IN_FLIGHT);
        }
        // Materialize the lease and admission envelope before changing any
        // quota counters: an allocation failure must not strand a slot or
        // silently spend a rate token.
        Admission granted = new Admission(Status.ADMITTED, new Lease(this, true));
        if (!httpStarts.tryConsume(1)) return Admission.denied(Status.RATE_LIMITED);
        httpInFlight++;
        return granted;
    }

    /** Reserve before DNS/socket creation; release after the host socket closes. */
    synchronized Admission tryOpenTcp() {
        if (closed) return Admission.denied(Status.CLOSED);
        if (tcpConnections >= maxTcpConnections) {
            return Admission.denied(Status.TOO_MANY_CONNECTIONS);
        }
        // A live-socket cap alone can be evaded by connect/fail/close storms.
        // Connection attempts consume a separate rate token even on DNS or
        // connect failure; successful close refunds only the live slot.
        Admission granted = new Admission(Status.ADMITTED, new Lease(this, false));
        if (!tcpStarts.tryConsume(1)) return Admission.denied(Status.RATE_LIMITED);
        tcpConnections++;
        return granted;
    }

    private synchronized void refundUnsent(boolean http) {
        release(http);
        if (http) httpStarts.rollbackUnpublished(1);
        else tcpStarts.rollbackUnpublished(1);
    }

    private synchronized void release(boolean http) {
        if (http) {
            if (httpInFlight == 0) throw new IllegalStateException("HTTP quota underflow");
            httpInFlight--;
        } else {
            if (tcpConnections == 0) throw new IllegalStateException("TCP quota underflow");
            tcpConnections--;
        }
    }

    synchronized int httpInFlight() { return httpInFlight; }
    synchronized int tcpConnections() { return tcpConnections; }

    /** Stops new admissions; previously issued leases still settle normally. */
    @Override public synchronized void close() { closed = true; }
}
