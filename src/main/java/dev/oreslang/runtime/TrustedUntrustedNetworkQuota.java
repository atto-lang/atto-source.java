package dev.oreslang.runtime;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
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
 * A TCP lease reserves an open/connecting socket slot. Callers must hold
 * the lease through the real I/O operation and release on every terminal
 * path. Cancellation must actually terminate or close host I/O before
 * releasing the corresponding slot.</p>
 */
final class TrustedUntrustedNetworkQuota implements AutoCloseable {
    record Policy(long httpStartsPerSecond, long httpBurst, int maxHttpInFlight,
                  int maxTcpConnections) {
        Policy {
            if (httpStartsPerSecond <= 0 || httpStartsPerSecond > 1_000_000_000L
                    || httpBurst <= 0 || httpBurst > 1_000_000_000L
                    || maxHttpInFlight < 1 || maxHttpInFlight > 1_000_000
                    || maxTcpConnections < 1 || maxTcpConnections > 1_000_000) {
                throw new IllegalArgumentException("invalid UNTRUSTED network quota policy");
            }
        }
        static Policy initialDefault() {
            return new Policy(10, 2, 10, 10);
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
        private final AtomicBoolean released = new AtomicBoolean();

        private Lease(TrustedUntrustedNetworkQuota owner, boolean http) {
            this.owner = owner;
            this.http = http;
        }

        /** Idempotent under cancellation / timeout / response-close races. */
        @Override public void close() {
            if (released.compareAndSet(false, true)) owner.release(http);
        }
    }

    private final TrustedOutboundRateBucket httpStarts;
    private final int maxHttpInFlight;
    private final int maxTcpConnections;
    private int httpInFlight;
    private int tcpConnections;
    private boolean closed;

    TrustedUntrustedNetworkQuota(Policy policy, LongSupplier trustedMonotonicNanos) {
        Objects.requireNonNull(policy, "trusted network policy");
        this.httpStarts = new TrustedOutboundRateBucket(
                policy.httpStartsPerSecond(), policy.httpBurst(),
                Objects.requireNonNull(trustedMonotonicNanos, "trusted clock"));
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
        if (!httpStarts.tryConsume(1)) return Admission.denied(Status.RATE_LIMITED);
        httpInFlight++;
        return new Admission(Status.ADMITTED, new Lease(this, true));
    }

    /** Reserve before DNS/socket creation; release after the host socket closes. */
    synchronized Admission tryOpenTcp() {
        if (closed) return Admission.denied(Status.CLOSED);
        if (tcpConnections >= maxTcpConnections) {
            return Admission.denied(Status.TOO_MANY_CONNECTIONS);
        }
        tcpConnections++;
        return new Admission(Status.ADMITTED, new Lease(this, false));
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
