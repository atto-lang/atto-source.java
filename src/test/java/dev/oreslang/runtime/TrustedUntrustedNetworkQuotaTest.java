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
    void publishedCancellationNeverMintsRateTokens() {
        AtomicLong clock = new AtomicLong();
        var quota = new TrustedUntrustedNetworkQuota(
                TrustedUntrustedNetworkQuota.Policy.initialDefault(), clock::get);
        var first = quota.tryStartHttp();
        assertEquals(TrustedUntrustedNetworkQuota.Status.ADMITTED, first.status());
        assertTrue(first.lease().abortBeforeDispatch());
        assertFalse(first.lease().abortBeforeDispatch());
        assertFalse(first.lease().commitDispatch());
        first.lease().close();
        assertEquals(0, quota.httpInFlight());

        // One cancelled reservation still spends a start-rate token.
        var second = quota.tryStartHttp();
        assertEquals(TrustedUntrustedNetworkQuota.Status.ADMITTED, second.status());
        assertEquals(TrustedUntrustedNetworkQuota.Status.RATE_LIMITED,
                quota.tryStartHttp().status());
        assertTrue(second.lease().commitDispatch());
        assertFalse(second.lease().abortBeforeDispatch());
        second.lease().close();

        var tcp = quota.tryOpenTcp();
        assertTrue(tcp.lease().abortBeforeDispatch());
        assertFalse(tcp.lease().abortBeforeDispatch());
        assertEquals(0, quota.tcpConnections());
    }

    @Test
    void delayedPublishedCancellationsCannotCreateAnOversizedBurst() {
        AtomicLong clock = new AtomicLong();
        var quota = new TrustedUntrustedNetworkQuota(
                TrustedUntrustedNetworkQuota.Policy.initialDefault(), clock::get);
        var a = quota.tryStartHttp();
        var b = quota.tryStartHttp();
        assertEquals(TrustedUntrustedNetworkQuota.Status.ADMITTED, a.status());
        assertEquals(TrustedUntrustedNetworkQuota.Status.ADMITTED, b.status());
        clock.set(1_000_000_000L);
        var c = quota.tryStartHttp();
        var d = quota.tryStartHttp();
        assertEquals(TrustedUntrustedNetworkQuota.Status.ADMITTED, c.status());
        assertEquals(TrustedUntrustedNetworkQuota.Status.ADMITTED, d.status());
        assertTrue(a.lease().abortBeforeDispatch());
        assertTrue(b.lease().abortBeforeDispatch());
        assertEquals(TrustedUntrustedNetworkQuota.Status.RATE_LIMITED,
                quota.tryStartHttp().status()); // old cancellations created no t=1s tokens
        c.lease().close();
        d.lease().close();
        assertEquals(0, quota.httpInFlight());

        var tcp = new TrustedUntrustedNetworkQuota(
                TrustedUntrustedNetworkQuota.Policy.initialDefault(), clock::get);
        var t1 = tcp.tryOpenTcp();
        var t2 = tcp.tryOpenTcp();
        assertEquals(TrustedUntrustedNetworkQuota.Status.ADMITTED, t1.status());
        assertEquals(TrustedUntrustedNetworkQuota.Status.ADMITTED, t2.status());
        clock.set(2_000_000_000L);
        var t3 = tcp.tryOpenTcp();
        var t4 = tcp.tryOpenTcp();
        assertEquals(TrustedUntrustedNetworkQuota.Status.ADMITTED, t3.status());
        assertEquals(TrustedUntrustedNetworkQuota.Status.ADMITTED, t4.status());
        t1.lease().abortBeforeDispatch();
        t2.lease().abortBeforeDispatch();
        assertEquals(TrustedUntrustedNetworkQuota.Status.RATE_LIMITED,
                tcp.tryOpenTcp().status());
        t3.lease().close();
        t4.lease().close();
        assertEquals(0, tcp.tcpConnections());
    }

    @Test
    void supervisorRevocationBlocksReservedButNotCommittedNetworkStarts() {
        AtomicLong clock = new AtomicLong();
        var http = new TrustedUntrustedNetworkQuota(
                TrustedUntrustedNetworkQuota.Policy.initialDefault(), clock::get);
        var reservedHttp = http.tryStartHttp();
        assertEquals(TrustedUntrustedNetworkQuota.Status.ADMITTED, reservedHttp.status());
        http.close();
        assertFalse(reservedHttp.lease().commitDispatch());
        assertTrue(reservedHttp.lease().abortBeforeDispatch());
        assertFalse(reservedHttp.lease().abortBeforeDispatch());
        assertEquals(0, http.httpInFlight());
        assertEquals(TrustedUntrustedNetworkQuota.Status.CLOSED, http.tryStartHttp().status());

        var tcp = new TrustedUntrustedNetworkQuota(
                TrustedUntrustedNetworkQuota.Policy.initialDefault(), clock::get);
        var reservedTcp = tcp.tryOpenTcp();
        assertEquals(TrustedUntrustedNetworkQuota.Status.ADMITTED, reservedTcp.status());
        tcp.close();
        assertFalse(reservedTcp.lease().commitDispatch());
        assertTrue(reservedTcp.lease().abortBeforeDispatch());
        assertEquals(0, tcp.tcpConnections());
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
