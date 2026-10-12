package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ActorIngressConcurrencyTest {
    @Test void regularActorHasOneIndependentMessageLimit() {
        try (var budget = ActorIngressConcurrency.regular(11, 2)) {
            var a = budget.tryEnterRegular(1, 11);
            var b = budget.tryEnterRegular(2, 11);
            assertNotNull(a);
            assertNotNull(b);
            assertNull(budget.tryEnterRegular(3, 11));
            assertEquals(2, budget.activeOrdinaryMessages());
            a.close();
            a.close();
            var c = budget.tryEnterRegular(3, 11);
            assertNotNull(c);
            assertEquals(2, budget.activeOrdinaryMessages());
            assertThrows(IllegalArgumentException.class, () -> budget.tryEnterRegular(2, 11));
            assertThrows(SecurityException.class, () -> budget.tryEnterRegular(4, 10));
            b.close();
            c.close();
            assertEquals(0, budget.activeOrdinaryMessages());
        }
    }

    @Test void networkActorsLimitConnectionsAndMessagesIndependently() {
        for (var protocol : List.of(ActorIngressConcurrency.Protocol.HTTP,
                ActorIngressConcurrency.Protocol.TCP, ActorIngressConcurrency.Protocol.WS)) {
            try (var budget = ActorIngressConcurrency.network(protocol, 17, 2, 2)) {
                var a = budget.tryOpenConnection(17);
                var b = budget.tryOpenConnection(17);
                assertNotNull(a);
                assertNotNull(b);
                assertNull(budget.tryOpenConnection(17));
                assertNotEquals(a.id(), b.id(), "the broker must mint unique session identities");
                assertTrue(b.id() > a.id());
                assertEquals(2, budget.activeConnections());

                var a1 = a.tryEnter(1, 17);
                var a2 = a.tryEnter(2, 17);
                var b1 = b.tryEnter(1, 17);
                var b2 = b.tryEnter(2, 17);
                assertNotNull(a1);
                assertNotNull(a2);
                assertNotNull(b1);
                assertNotNull(b2);
                assertNull(a.tryEnter(3, 17));
                assertEquals(2, a.activeMessages());
                assertEquals(2, b.activeMessages());

                a1.close();
                assertNotNull(a.tryEnter(3, 17));
                assertEquals(2, a.activeMessages());
                assertEquals(2, b.activeMessages());
                assertThrows(IllegalArgumentException.class, () -> a.tryEnter(2, 17));
                assertThrows(SecurityException.class, () -> b.tryEnter(3, 99));

                a.close();
                assertTrue(a2.isClosed());
                assertEquals(1, budget.activeConnections());
                var c = budget.tryOpenConnection(17);
                assertNotNull(c);
                assertTrue(c.id() > b.id(),
                        "connection IDs cannot be recycled after closing a session");
                assertThrows(IllegalStateException.class, () -> a.tryEnter(4, 17));
                b.close();
                b1.close();
                b2.close();
            }
        }
    }

    @Test void closeInvalidatesLeasesAndActorIncarnation() {
        var budget = ActorIngressConcurrency.network(ActorIngressConcurrency.Protocol.HTTP, 7, 1, 2);
        var a = budget.tryOpenConnection(7);
        var m = a.tryEnter(1, 7);
        assertThrows(SecurityException.class, () -> budget.tryOpenConnection(8));
        budget.close();
        budget.close();
        assertTrue(a.isClosed());
        assertTrue(m.isClosed());
        assertEquals(0, budget.activeConnections());
        assertThrows(IllegalStateException.class, () -> a.tryEnter(2, 7));
        assertThrows(IllegalStateException.class, () -> budget.tryOpenConnection(7));
        m.close();
        a.close();
    }

    @Test void fullBudgetNeverConsumesRejectedSequence() {
        try (var budget = ActorIngressConcurrency.network(ActorIngressConcurrency.Protocol.WS, 4, 1, 1)) {
            var a = budget.tryOpenConnection(4);
            var first = a.tryEnter(1, 4);
            assertNull(a.tryEnter(2, 4));
            first.close();
            assertNotNull(a.tryEnter(2, 4));
            assertThrows(IllegalArgumentException.class, () -> a.tryEnter(2, 4));
        }
    }

    @Test void contestedNetworkAdmissionCannotExceedConnectionOrMessageLimit() throws Exception {
        try (var budget = ActorIngressConcurrency.network(ActorIngressConcurrency.Protocol.TCP, 66, 3, 2);
             ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger admitted = new AtomicInteger();
            CountDownLatch admissionsAttempted = new CountDownLatch(50);
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                futures.add(workers.submit(() -> {
                    start.await();
                    var conn = budget.tryOpenConnection(66);
                    if (conn != null) admitted.incrementAndGet();
                    // Publish the observation before marking this contender
                    // finished, otherwise the parent can read a stale count.
                    admissionsAttempted.countDown();
                    if (conn == null) return null;
                    try (conn) {
                        // Distinct connection handlers can each have 2 live turns.
                        var first = conn.tryEnter(1, 66);
                        var second = conn.tryEnter(2, 66);
                        assertNotNull(first);
                        assertNotNull(second);
                        assertNull(conn.tryEnter(3, 66));
                        release.await();
                        first.close();
                        second.close();
                    }
                    return null;
                }));
            }
            start.countDown();
            // All competing attempts must have reached the admission gate.
            // Don't depend on an arbitrary sleep interval or scheduler speed.
            try {
                assertTrue(admissionsAttempted.await(5, TimeUnit.SECONDS),
                        "all contenders must attempt admission before checking quotas");
                assertEquals(3, admitted.get());
                assertEquals(3, budget.activeConnections());
            } finally {
                // Avoid stranding the winning workers if an assertion fails.
                release.countDown();
            }
            for (var future : futures) future.get(5, TimeUnit.SECONDS);
            assertEquals(0, budget.activeConnections());
        }
    }

    @Test void shutdownRacingContendedAdmissionNeverReopensAClosedBudget() throws Exception {
        for (int iteration = 0; iteration < 64; iteration++) {
            var budget = ActorIngressConcurrency.network(
                    ActorIngressConcurrency.Protocol.WS, iteration + 1L, 2, 2);
            try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
                CountDownLatch start = new CountDownLatch(1);
                List<Future<?>> attempts = new ArrayList<>();
                final long incarnation = iteration + 1L;
                for (int k = 0; k < 16; k++) {
                    attempts.add(workers.submit(() -> {
                        start.await();
                        try {
                            var connection = budget.tryOpenConnection(incarnation);
                            if (connection != null) {
                                try (connection) {
                                    var message = connection.tryEnter(1, incarnation);
                                    if (message != null) message.close();
                                }
                            }
                        } catch (IllegalStateException shutdownWon) {
                            assertTrue(budget.isClosed(),
                                    "only a terminated budget may reject admission here");
                        }
                        return null;
                    }));
                }
                attempts.add(workers.submit(() -> {
                    start.await();
                    budget.close();
                    return null;
                }));
                start.countDown();
                for (var attempt : attempts) attempt.get(5, TimeUnit.SECONDS);
                assertTrue(budget.isClosed());
                assertEquals(0, budget.activeConnections());
                assertThrows(IllegalStateException.class,
                        () -> budget.tryOpenConnection(incarnation));
            } finally {
                budget.close();
            }
        }
    }

    @Test void releasingALeaseNeverMakesItsSequenceReplayable() {
        try (var budget = ActorIngressConcurrency.regular(101, 1)) {
            var first = budget.tryEnterRegular(1, 101);
            first.close();
            assertThrows(IllegalArgumentException.class,
                    () -> budget.tryEnterRegular(1, 101));
            var next = budget.tryEnterRegular(2, 101);
            assertNotNull(next);
            next.close();
        }
        try (var budget = ActorIngressConcurrency.network(
                ActorIngressConcurrency.Protocol.TCP, 102, 1, 1)) {
            var conn = budget.tryOpenConnection(102);
            var first = conn.tryEnter(1, 102);
            first.close();
            assertThrows(IllegalArgumentException.class, () -> conn.tryEnter(1, 102));
            var next = conn.tryEnter(2, 102);
            assertNotNull(next);
            conn.close();
            assertTrue(next.isClosed());
            assertThrows(IllegalStateException.class, () -> conn.tryEnter(3, 102));
        }
    }

    @Test void incorrectModesAndNonPositiveLimitsFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> ActorIngressConcurrency.regular(1, 0));
        assertThrows(IllegalArgumentException.class, () -> ActorIngressConcurrency.network(
                ActorIngressConcurrency.Protocol.REGULAR, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> ActorIngressConcurrency.network(
                ActorIngressConcurrency.Protocol.HTTP, 1, 0, 1));
        try (var generic = ActorIngressConcurrency.regular(1, 1);
             var http = ActorIngressConcurrency.network(ActorIngressConcurrency.Protocol.HTTP, 2, 1, 1)) {
            assertThrows(IllegalStateException.class, () -> generic.tryOpenConnection(1));
            assertThrows(IllegalStateException.class, () -> http.tryEnterRegular(1, 2));
        }
    }
}
