package dev.oreslang.runtime;

import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Runtime-only safety gate; does not assert production multi-client networking. */
final class ActorReplyRouterTest {
    @Test void saturatesPerClientGlobalAndClientQuotasWithoutCreatingPendingReplies() throws Exception {
        try (ActorReplyRouter<String> router = new ActorReplyRouter<>(2, 2, 3)) {
            var a = router.tryOpenClient().orElseThrow();
            var b = router.tryOpenClient().orElseThrow();
            assertTrue(router.tryOpenClient().isEmpty());
            var a1 = router.tryAdmit(a).orElseThrow();
            var a2 = router.tryAdmit(a).orElseThrow();
            assertTrue(router.tryAdmit(a).isEmpty());
            var b1 = router.tryAdmit(b).orElseThrow();
            assertTrue(router.tryAdmit(b).isEmpty());
            assertEquals(3, router.inFlight());

            assertTrue(router.deliver(a, a1.requestId(), "A1"));
            assertEquals("A1", a1.reply().get(1, TimeUnit.SECONDS));
            assertTrue(router.tryAdmit(b).isPresent());
            assertEquals(3, router.inFlight());
            a2.reply().cancel(false);
            assertEquals(2, router.inFlight());
            assertFalse(router.deliver(a, a2.requestId(), "late"));
            b1.reply().cancel(false);
        }
    }

    @Test void outOfOrderRepliesAreExactlyCorrelatedAndWrongClientIsRejected() throws Exception {
        try (ActorReplyRouter<String> router = new ActorReplyRouter<>(2, 5, 10)) {
            var clientA = router.tryOpenClient().orElseThrow();
            var clientB = router.tryOpenClient().orElseThrow();
            var a1 = router.tryAdmit(clientA).orElseThrow();
            var b1 = router.tryAdmit(clientB).orElseThrow();
            var a2 = router.tryAdmit(clientA).orElseThrow();
            assertNotEquals(a1.requestId(), a2.requestId());
            assertFalse(router.deliver(clientB, a1.requestId(), "stolen"));
            assertTrue(router.deliver(clientA, a2.requestId(), "second"));
            assertTrue(router.deliver(clientB, b1.requestId(), "other client"));
            assertTrue(router.deliver(clientA, a1.requestId(), "first"));
            assertEquals("first", a1.reply().get(1, TimeUnit.SECONDS));
            assertEquals("second", a2.reply().get(1, TimeUnit.SECONDS));
            assertEquals("other client", b1.reply().get(1, TimeUnit.SECONDS));
            assertFalse(router.deliver(clientA, a1.requestId(), "duplicate"));
            assertEquals(0, router.inFlight());
        }
    }

    @Test void closeCancelsOnlyItsOwnRequestsAndNeverReusesClientIdentity() throws Exception {
        try (ActorReplyRouter<String> router = new ActorReplyRouter<>(2, 4, 8)) {
            var old = router.tryOpenClient().orElseThrow();
            var other = router.tryOpenClient().orElseThrow();
            var orphan = router.tryAdmit(old).orElseThrow();
            var live = router.tryAdmit(other).orElseThrow();
            old.close();
            assertTrue(orphan.reply().isCancelled());
            assertFalse(router.deliver(old, orphan.requestId(), "stale"));
            assertTrue(router.tryAdmit(old).isEmpty());
            var replacement = router.tryOpenClient().orElseThrow();
            assertNotEquals(old.id(), replacement.id());
            assertTrue(router.deliver(other, live.requestId(), "unaffected"));
            assertEquals("unaffected", live.reply().get(1, TimeUnit.SECONDS));
            router.close();
            assertTrue(router.tryOpenClient().isEmpty());
            assertTrue(router.tryAdmit(replacement).isEmpty());
        }
    }

    @Test void competingCancellationAndDeliveryReleaseQuotasExactlyOnce() throws Exception {
        try (ActorReplyRouter<Integer> router = new ActorReplyRouter<>(1, 1, 1);
             ExecutorService executor = Executors.newFixedThreadPool(2)) {
            var client = router.tryOpenClient().orElseThrow();
            for (int iteration = 0; iteration < 200; iteration++) {
                var ticket = router.tryAdmit(client).orElseThrow();
                CyclicBarrier barrier = new CyclicBarrier(3);
                Future<Boolean> delivery = executor.submit(() -> {
                    barrier.await();
                    return router.deliver(client, ticket.requestId(), 42);
                });
                Future<Boolean> cancel = executor.submit(() -> {
                    barrier.await();
                    return ticket.reply().cancel(false);
                });
                barrier.await();
                delivery.get(5, TimeUnit.SECONDS);
                cancel.get(5, TimeUnit.SECONDS);
                assertTrue(ticket.reply().isDone());
                assertEquals(0, router.inFlight(),
                        "no leaked quota or double release on racing terminal operations");
            }
        }
    }

    @Test void closeRetiresAllAndRejectsZeroQuotas() {
        assertThrows(IllegalArgumentException.class,
                () -> new ActorReplyRouter<>(0, 1, 1));
        try (ActorReplyRouter<Integer> router = new ActorReplyRouter<>(3, 3, 9)) {
            var a = router.tryOpenClient().orElseThrow();
            var b = router.tryOpenClient().orElseThrow();
            var x = router.tryAdmit(a).orElseThrow();
            var y = router.tryAdmit(b).orElseThrow();
            router.close();
            assertTrue(x.reply().isCancelled());
            assertTrue(y.reply().isCancelled());
            assertEquals(0, router.inFlight());
            assertEquals(0, router.openClients());
            router.close();
        }
    }
}
