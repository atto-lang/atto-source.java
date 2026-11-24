package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Host+ActorRuntime route proof; source guest and socket integration remain separate gates. */
@Timeout(20)
final class ActorRollingGenerationRouteTest {
    @Test void alreadyAdmittedOldPhysicalActorCompletesWhileNewOneServes() throws Exception {
        CountDownLatch oldEntered = new CountDownLatch(1);
        CountDownLatch finishOld = new CountDownLatch(1);
        CountDownLatch oldCompleted = new CountDownLatch(1);
        CountDownLatch newCompleted = new CountDownLatch(1);
        try (var runtime = new ActorRuntime()) {
            var oldActor = runtime.<byte[]>spawnShared(() -> (payload, context) -> {
                oldEntered.countDown();
                try {
                    if (!finishOld.await(5, TimeUnit.SECONDS))
                        throw new AssertionError("old handler timed out");
                    oldCompleted.countDown();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw interrupted;
                }
            });
            var newActor = runtime.<byte[]>spawnShared(() -> (payload, context) -> {
                assertArrayEquals(new byte[] {2}, payload);
                newCompleted.countDown();
            });
            oldActor.getReady().get(5, TimeUnit.SECONDS);
            newActor.getReady().get(5, TimeUnit.SECONDS);
            try (var route = new ActorRollingGenerationRoute<>(11L, oldActor, 2)) {
                var acceptedOld = route.acquire();
                acceptedOld.target().send(new byte[] {1});
                assertTrue(oldEntered.await(5, TimeUnit.SECONDS));
                assertTrue(route.publishReady(1L, newActor));
                assertFalse(route.admissionsDrained(1L), "old must remain physically pinned");
                try (var acceptedNew = route.acquire()) {
                    assertEquals(2, acceptedNew.epoch());
                    assertSame(newActor, acceptedNew.target());
                    acceptedNew.target().send(new byte[] {2});
                    assertTrue(newCompleted.await(5, TimeUnit.SECONDS),
                            "new actor cannot depend on old completion");
                }
                assertEquals(2, route.snapshot().liveGenerations());
                finishOld.countDown();
                assertTrue(oldCompleted.await(5, TimeUnit.SECONDS));
                oldActor.stop();
                oldActor.getDone().get(5, TimeUnit.SECONDS);
                // Transport also must join any I/O callbacks before this CONTROL receipt.
                acceptedOld.close();
                assertTrue(route.admissionsDrained(1L));
                assertThrows(IllegalStateException.class,
                        () -> route.acknowledgePhysicallyRetired(1L),
                        "traffic and actor terminal state do not prove physical quiescence");
                assertEquals(2, route.snapshot().liveGenerations(),
                        "old generation remains quarantined");
                newActor.stop();
                newActor.getDone().get(5, TimeUnit.SECONDS);
            }
        } finally {
            finishOld.countDown();
        }
    }

    @Test void staleEpochAndFailedCandidateNeverChangeServingRoute() {
        Object old = new Object(); Object next = new Object();
        try (var route = new ActorRollingGenerationRoute<>(1L, old, 2)) {
            assertThrows(NullPointerException.class, () -> route.publishReady(1L, null));
            assertThrows(IllegalArgumentException.class, () -> route.publishReady(1L, old));
            assertFalse(route.publishReady(0L, next));
            assertEquals(1L, route.snapshot().activeEpoch());
            try (var ref = route.acquire()) { assertSame(old, ref.target()); }
            assertTrue(route.publishReady(1L, next));
            assertFalse(route.publishReady(1L, new Object()));
            assertThrows(IllegalArgumentException.class,
                    () -> route.publishReady(2L, old),
                    "cannot resurrect a retained draining physical actor");
            assertEquals(2L, route.snapshot().activeEpoch());
        }
    }

    @Test void boundedRetainedGenerationsFailClosedWithoutPrematureReclamation() {
        Object old = new Object(); Object second = new Object();
        try (var route = new ActorRollingGenerationRoute<>(8L, old, 2)) {
            var held = route.acquire();
            assertTrue(route.publishReady(1L, second));
            assertThrows(IllegalStateException.class,
                    () -> route.acknowledgePhysicallyRetired(1L));
            assertThrows(IllegalStateException.class,
                    () -> route.publishReady(2L, new Object()));
            assertEquals(2L, route.snapshot().activeEpoch());
            held.close();
            assertThrows(IllegalStateException.class, held::target);
            assertTrue(route.admissionsDrained(1L));
            assertThrows(IllegalStateException.class,
                    () -> route.acknowledgePhysicallyRetired(1L));
            assertEquals(2, route.snapshot().liveGenerations());
            assertThrows(IllegalStateException.class,
                    () -> route.publishReady(2L, new Object()),
                    "safe boundedness beats unverified physical release");
            assertEquals(2L, route.snapshot().activeEpoch());
        }
    }

    @Test void concurrentPromotionsAreLinearizedWithoutABA() throws Exception {
        try (var route = new ActorRollingGenerationRoute<>(9L, new Object(), 4)) {
            int threads = 12;
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            AtomicInteger won = new AtomicInteger();
            AtomicReference<Throwable> unexpected = new AtomicReference<>();
            for (int i = 0; i < threads; i++) {
                Thread worker = Thread.ofPlatform().start(() -> {
                    ready.countDown();
                    try {
                        if (!start.await(5, TimeUnit.SECONDS)) return;
                        if (route.publishReady(1L, new Object())) won.incrementAndGet();
                    } catch (Throwable failure) {
                        unexpected.compareAndSet(null, failure);
                    } finally { done.countDown(); }
                });
                worker.setName("config-promotion-race");
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertNull(unexpected.get(), "concurrent publication must not throw");
            assertEquals(1, won.get());
            assertEquals(2L, route.snapshot().activeEpoch());
        }
    }

    @Test void shutdownClosesAdmissionButRetainsExistingLeases() {
        var route = new ActorRollingGenerationRoute<>(10L, new Object(), 2);
        var ref = route.acquire();
        route.close();
        route.close();
        assertFalse(route.admissionsDrained(1L));
        assertThrows(IllegalStateException.class, route::acquire);
        assertThrows(IllegalStateException.class,
                () -> route.publishReady(1L, new Object()));
        assertThrows(IllegalStateException.class,
                () -> route.acknowledgePhysicallyRetired(1L));
        ref.close(); ref.close();
        assertTrue(route.admissionsDrained(1L));
        assertThrows(IllegalStateException.class,
                () -> route.acknowledgePhysicallyRetired(1L),
                "shutdown never manufactures physical retirement proof");
    }
}
