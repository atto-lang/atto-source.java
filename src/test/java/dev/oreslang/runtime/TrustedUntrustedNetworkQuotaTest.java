package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** HOST-ONLY prototype tests, not a real UNTRUSTED guest network bridge. */
final class TrustedUntrustedNetworkQuotaTest {
    @Test
    void tenHttpInFlightAndEleventhDeniedWithoutRateCharge() {
        AtomicLong now = new AtomicLong();
        var quota = new TrustedUntrustedNetworkQuota(
                TrustedUntrustedNetworkQuota.Policy.initialDefault(), now::get);
        List<TrustedUntrustedNetworkQuota.Lease> leases = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            // Allow the configured 10 starts/sec bucket to refill.
            now.set(i * 100_000_000L);
            var admission = quota.tryStartHttp();
            assertEquals(TrustedUntrustedNetworkQuota.Status.ADMITTED, admission.status());
            leases.add(admission.lease());
        }
        assertEquals(10, quota.httpInFlight());
        assertEquals(TrustedUntrustedNetworkQuota.Status.TOO_MANY_IN_FLIGHT,
                quota.tryStartHttp().status());
        leases.get(0).close();
        assertEquals(9, quota.httpInFlight());
        var resumed = quota.tryStartHttp();
        assertEquals(TrustedUntrustedNetworkQuota.Status.ADMITTED, resumed.status());
        for (var lease : leases) lease.close();
        resumed.lease().close();
        assertEquals(0, quota.httpInFlight());
        quota.close();
        assertEquals(TrustedUntrustedNetworkQuota.Status.CLOSED, quota.tryStartHttp().status());
    }

    @Test
    void httpStartsAreSeparatelyRateLimited() {
        AtomicLong now = new AtomicLong();
        var quota = new TrustedUntrustedNetworkQuota(
                TrustedUntrustedNetworkQuota.Policy.initialDefault(), now::get);
        var first = quota.tryStartHttp();
        var second = quota.tryStartHttp();
        assertEquals(TrustedUntrustedNetworkQuota.Status.ADMITTED, first.status());
        assertEquals(TrustedUntrustedNetworkQuota.Status.ADMITTED, second.status());
        assertEquals(TrustedUntrustedNetworkQuota.Status.RATE_LIMITED,
                quota.tryStartHttp().status());
        // HTTP completion releases concurrency, NOT the consumed rate budget.
        first.lease().close();
        second.lease().close();
        assertEquals(TrustedUntrustedNetworkQuota.Status.RATE_LIMITED,
                quota.tryStartHttp().status());
        now.set(100_000_000L);
        var third = quota.tryStartHttp();
        assertEquals(TrustedUntrustedNetworkQuota.Status.ADMITTED, third.status());
        third.lease().close();
        assertEquals(0, quota.httpInFlight());
    }

    @Test
    void tcpConnectionSlotsAreReleasedExactlyOnceAfterCloseAndFailure() {
        AtomicLong now = new AtomicLong();
        var quota = new TrustedUntrustedNetworkQuota(
                TrustedUntrustedNetworkQuota.Policy.initialDefault(), now::get);
        List<TrustedUntrustedNetworkQuota.Lease> sockets = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            now.set(i * 100_000_000L);
            var admitted = quota.tryOpenTcp();
            assertEquals(TrustedUntrustedNetworkQuota.Status.ADMITTED, admitted.status());
            sockets.add(admitted.lease());
        }
        assertEquals(TrustedUntrustedNetworkQuota.Status.TOO_MANY_CONNECTIONS,
                quota.tryOpenTcp().status());
        sockets.get(0).close();
        sockets.get(0).close(); // cancellation / close race
        assertEquals(9, quota.tcpConnections());
        var failedConnect = quota.tryOpenTcp();
        assertEquals(TrustedUntrustedNetworkQuota.Status.ADMITTED, failedConnect.status());
        failedConnect.lease().close(); // connection establishment failed
        for (var lease : sockets) lease.close();
        assertEquals(0, quota.tcpConnections());
    }

    @Test
    void closeAndConcurrentAdmissionAreRaceSafe() throws Exception {
        AtomicLong now = new AtomicLong();
        var quota = new TrustedUntrustedNetworkQuota(
                new TrustedUntrustedNetworkQuota.Policy(10, 2, 10, 10, 10, 10), now::get);
        AtomicInteger admitted = new AtomicInteger();
        var leases = new ConcurrentLinkedQueue<TrustedUntrustedNetworkQuota.Lease>();
        CountDownLatch start = new CountDownLatch(1);
        Thread[] threads = new Thread[24];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> {
                try {
                    if (!start.await(2, TimeUnit.SECONDS)) return;
                    var item = quota.tryOpenTcp();
                    if (item.lease() != null) {
                        leases.add(item.lease());
                        admitted.incrementAndGet();
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            threads[i].start();
        }
        start.countDown();
        for (var thread : threads) thread.join(2000);
        assertEquals(10, admitted.get());
        assertEquals(10, quota.tcpConnections());
        quota.close();
        assertEquals(TrustedUntrustedNetworkQuota.Status.CLOSED,
                quota.tryOpenTcp().status());
        for (var lease : leases) lease.close();
        assertEquals(0, quota.tcpConnections());
    }

    @Test
    void connectionChurnDoesNotMintTcpStartTokens() {
        AtomicLong clock = new AtomicLong();
        var quota = new TrustedUntrustedNetworkQuota(
                TrustedUntrustedNetworkQuota.Policy.initialDefault(), clock::get);
        for (int i = 0; i < 2; i++) {
            var ticket = quota.tryOpenTcp();
            assertEquals(TrustedUntrustedNetworkQuota.Status.ADMITTED, ticket.status());
            ticket.lease().close();
        }
        assertEquals(0, quota.tcpConnections());
        assertEquals(TrustedUntrustedNetworkQuota.Status.RATE_LIMITED,
                quota.tryOpenTcp().status());
        clock.set(100_000_000);
        var ticket = quota.tryOpenTcp();
        assertEquals(TrustedUntrustedNetworkQuota.Status.ADMITTED, ticket.status());
        ticket.lease().close();
    }

    @Test
    void malformedPolicyRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new TrustedUntrustedNetworkQuota.Policy(0, 2, 10, 10));
        assertThrows(IllegalArgumentException.class,
                () -> new TrustedUntrustedNetworkQuota.Policy(10, 2, 0, 10));
        assertThrows(IllegalArgumentException.class,
                () -> new TrustedUntrustedNetworkQuota.Policy(10, 2, 10, 0));
    }
}
