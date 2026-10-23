package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
final class ActorProxyRequestSlabTest {
    @Test
    void allLayoutsRejectStaleHandlesAndWaitForOutstandingReceipts() throws Exception {
        for (ActorProxyRequestSlab.Layout layout : ActorProxyRequestSlab.Layout.values()) {
            for (int lanes : layout == ActorProxyRequestSlab.Layout.SOA
                    ? new int[] {1} : new int[] {16, 32, 64}) {
                try (ActorRuntime runtime = new ActorRuntime()) {
                    CountDownLatch finished = new CountDownLatch(1);
                    AtomicReference<Throwable> failure = new AtomicReference<>();
                    AtomicReference<ActorProxyRequestSlab> retained = new AtomicReference<>();
                    ActorRuntime.ActorRef<Integer> actor = runtime.spawnSharedTrusted(
                            factory -> (message, turn) -> {
                                try {
                                    ActorProxyRequestSlab slab =
                                            new ActorProxyRequestSlab(layout, 3, lanes);
                                    retained.set(slab);
                                    assertEquals(0, slab.live());
                                    assertThrows(IllegalArgumentException.class,
                                            () -> slab.admit(-1, 3, 10));
                                    assertThrows(IllegalArgumentException.class,
                                            () -> slab.admit(7, -1, 10));
                                    assertEquals(0, slab.live());
                                    long a = slab.admit(7, 3, 10);
                                    long b = slab.admit(7, 4, 11);
                                    long c = slab.admit(8, 5, 12);
                                    assertNotEquals(0L, a);
                                    assertNotEquals(0L, b);
                                    assertNotEquals(0L, c);
                                    assertEquals(3, slab.live());
                                    assertTrue(slab.full());
                                    assertEquals(0L, slab.admit(9, 6, 13));
                                    assertEquals(10, slab.route(a));
                                    assertEquals(4, slab.connection(b));
                                    assertEquals(8, slab.epoch(c));
                                    long first = slab.pin(a);
                                    long second = slab.pin(a);
                                    long other = slab.pin(b);
                                    assertNotEquals(first, second);
                                    assertFalse(slab.settle(slab.identity(), b, first),
                                            "a completion for request a cannot settle request b");
                                    assertFalse(slab.settle(slab.identity(), a, other),
                                            "a completion for request b cannot settle request a");
                                    assertEquals(2, slab.pending(a));
                                    assertEquals(1, slab.pending(b));
                                    assertTrue(slab.retire(a));
                                    assertTrue(slab.retire(a));
                                    assertEquals(2, slab.pending(a));
                                    assertThrows(IllegalStateException.class, () -> slab.pin(a));
                                    assertEquals(0L, slab.admit(9, 6, 13));
                                    assertTrue(slab.settle(slab.identity(), a, second));
                                    assertFalse(slab.settle(slab.identity(), a, second));
                                    assertEquals(3, slab.live());
                                    assertTrue(slab.settle(slab.identity(), a, first));
                                    assertEquals(2, slab.live());
                                    long reused = slab.admit(9, 6, 13);
                                    assertNotEquals(a, reused);
                                    assertEquals(13, slab.route(reused));
                                    assertFalse(slab.retire(a));
                                    assertFalse(slab.settle(slab.identity(), a, first));
                                    assertThrows(IllegalStateException.class, () -> slab.route(a));
                                    assertFalse(slab.settle(slab.identity(), a, (first & 0xffffffffL) | (99L << 32)));
                                    assertTrue(slab.retire(b));
                                    assertEquals(3, slab.live());
                                    assertTrue(slab.settle(slab.identity(), b, other));
                                    assertTrue(slab.retire(c));
                                    assertTrue(slab.retire(reused));
                                    assertEquals(0, slab.live());
                                    slab.close();
                                    slab.close(); // repeated cleanup is harmless
                                } catch (Throwable problem) {
                                    failure.set(problem);
                                } finally {
                                    finished.countDown();
                                }
                            });
                    actor.send(1);
                    assertTrue(finished.await(5, TimeUnit.SECONDS));
                    assertNull(failure.get(), "all layouts must preserve ownership");
                    assertThrows(SecurityException.class, () -> retained.get().live());
                    actor.stop();
                    assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
                    Field storage = ActorProxyRequestSlab.class.getDeclaredField("storage");
                    storage.setAccessible(true);
                    assertNull(storage.get(retained.get()), "actor lease must detach backing");
                }
            }
        }
    }

    @Test
    void pinCapacityBoundsAdmissionAndReleasesAfterLastCompletion() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch finished = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            ActorRuntime.ActorRef<Integer> actor = runtime.spawnSharedTrusted(
                    factory -> (message, turn) -> {
                        try {
                            ActorProxyRequestSlab slab =
                                    new ActorProxyRequestSlab(ActorProxyRequestSlab.Layout.AOSOA, 1, 32);
                            long request = slab.admit(7, 0, 0);
                            long[] receipts = new long[ActorProxyRequestSlab.PINS_PER_SLOT];
                            for (int i = 0; i < receipts.length; i++) {
                                receipts[i] = slab.pin(request);
                                assertNotEquals(0L, receipts[i]);
                            }
                            assertEquals(0L, slab.pin(request));
                            assertTrue(slab.retire(request));
                            assertThrows(IllegalStateException.class, slab::close);
                            for (int i = receipts.length - 1; i >= 1; i--) {
                                assertTrue(slab.settle(slab.identity(), request, receipts[i]));
                                assertFalse(slab.settle(slab.identity(), request, receipts[i]));
                                assertEquals(1, slab.live());
                            }
                            assertTrue(slab.settle(slab.identity(), request, receipts[0]));
                            assertEquals(0, slab.live());
                            assertFalse(slab.settle(slab.identity(), request, receipts[0]));
                            slab.close();
                        } catch (Throwable problem) {
                            failure.set(problem);
                        } finally {
                            finished.countDown();
                        }
                    });
            actor.send(1);
            assertTrue(finished.await(5, TimeUnit.SECONDS));
            assertNull(failure.get());
            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void privateActorSlabUsesChargedArenaAndReleasesOnPhysicalStop() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<Integer> actor = runtime.spawnPrivate(factory -> {
                final ActorProxyRequestSlab slab =
                        new ActorProxyRequestSlab(ActorProxyRequestSlab.Layout.AOSOA, 65, 32);
                if (!slab.isPrivateBacked()) {
                    throw new AssertionError("private slab must use direct actor arena");
                }
                return (message, turn) -> {
                    long req = slab.admit(11, 5, 6);
                    long pin = slab.pin(req);
                    if (!slab.retire(req) || slab.live() != 1 || !slab.settle(slab.identity(), req, pin)
                            || slab.live() != 0) {
                        throw new AssertionError("private request retirement failed");
                    }
                };
            });
            actor.send(1);
            actor.drain().get(5, TimeUnit.SECONDS);
            assertTrue(runtime.privateMemoryBytes() > 0);
            actor.gracefulStop().get(5, TimeUnit.SECONDS);
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(0, runtime.privateMemoryBytes(),
                    "actor-owned arena must be released on physical termination");
        }
    }

    @Test
    void slabIdentitiesFailClosedBeforeLongOverflow() {
        java.util.concurrent.atomic.AtomicLong next =
                new java.util.concurrent.atomic.AtomicLong(Long.MAX_VALUE - 1L);
        assertEquals(Long.MAX_VALUE - 1L, ActorProxyRequestSlab.allocateSlabIdentity(next));
        assertEquals(Long.MAX_VALUE, next.get());
        assertThrows(IllegalStateException.class,
                () -> ActorProxyRequestSlab.allocateSlabIdentity(next));
        assertEquals(Long.MAX_VALUE, next.get());
        java.util.concurrent.atomic.AtomicLong invalid =
                new java.util.concurrent.atomic.AtomicLong(0L);
        assertThrows(IllegalStateException.class,
                () -> ActorProxyRequestSlab.allocateSlabIdentity(invalid));
    }

    @Test
    void foreignActorCannotSettleOwnersReceipt() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch ready = new CountDownLatch(1);
            CountDownLatch checked = new CountDownLatch(1);
            CountDownLatch afterClose = new CountDownLatch(1);
            AtomicReference<ActorProxyRequestSlab> shared = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            java.util.concurrent.atomic.AtomicLong receipt =
                    new java.util.concurrent.atomic.AtomicLong();
            java.util.concurrent.atomic.AtomicLong request =
                    new java.util.concurrent.atomic.AtomicLong();
            ActorRuntime.ActorRef<Integer> a = runtime.spawnSharedTrusted(context ->
                    (message, turn) -> {
                        try {
                            if (message == 1) {
                                ActorProxyRequestSlab slab = new ActorProxyRequestSlab(
                                        ActorProxyRequestSlab.Layout.SOA, 1, 1);
                                request.set(slab.admit(1, 0, 0));
                                receipt.set(slab.pin(request.get()));
                                shared.set(slab);
                                ready.countDown();
                            } else {
                                ActorProxyRequestSlab slab = shared.get();
                                assertTrue(slab.retire(request.get()));
                                assertTrue(slab.settle(slab.identity(), request.get(), receipt.get()));
                                slab.close();
                                checked.countDown();
                            }
                        } catch (Throwable t) { failure.set(t); checked.countDown(); }
                    });
            a.send(1);
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            ActorRuntime.ActorRef<Integer> b = runtime.spawnSharedTrusted(context ->
                    (message, turn) -> {
                        try {
                            ActorProxyRequestSlab slab = shared.get();
                            if (message == 2) {
                                assertThrows(SecurityException.class, slab::close);
                            } else {
                                assertThrows(SecurityException.class, slab::capacity);
                                assertThrows(SecurityException.class, slab::layout);
                                assertThrows(SecurityException.class, slab::lanes);
                                assertThrows(SecurityException.class,
                                        () -> slab.pin(request.get()));
                                assertThrows(SecurityException.class,
                                        () -> slab.settle(1L, request.get(), receipt.get()));
                            }
                        } catch (Throwable t) { failure.set(t); }
                        finally {
                            if (message == 2) afterClose.countDown();
                            else checked.countDown();
                        }
                    });
            b.send(1);
            assertTrue(checked.await(5, TimeUnit.SECONDS));
            assertNull(failure.get());
            a.send(2);
            a.drain().get(5, TimeUnit.SECONDS);
            assertNull(failure.get());
            b.send(2);
            assertTrue(afterClose.await(5, TimeUnit.SECONDS));
            assertNull(failure.get());
            a.stop();
            b.stop();
            assertTrue(a.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(b.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void externalHostCannotAllocateActorOwnedSlab() {
        assertThrows(SecurityException.class, () ->
                new ActorProxyRequestSlab(ActorProxyRequestSlab.Layout.SOA, 4, 1));
    }
    @Test
    void seededRecycleStressKeepsReceiptsUniqueAcrossReuse() throws Exception {
        for (ActorProxyRequestSlab.Layout layout : ActorProxyRequestSlab.Layout.values()) {
            try (ActorRuntime runtime = new ActorRuntime()) {
                CountDownLatch finished = new CountDownLatch(1);
                AtomicReference<Throwable> failure = new AtomicReference<>();
                ActorRuntime.ActorRef<Integer> actor = runtime.spawnSharedTrusted(
                        factory -> (message, turn) -> {
                            try {
                                for (int capacity : new int[] {1, 3, 31, 32, 33, 65}) {
                                    ActorProxyRequestSlab slab = new ActorProxyRequestSlab(
                                            layout, capacity,
                                            layout == ActorProxyRequestSlab.Layout.SOA ? 1 : 32);
                                    java.util.Random random = new java.util.Random(112358L + capacity);
                                    long[] handles = new long[capacity];
                                    long[] pins = new long[capacity];
                                    for (int operation = 0; operation < 10_000; operation++) {
                                        int slot = random.nextInt(capacity);
                                        if (handles[slot] == 0L) {
                                            handles[slot] = slab.admit(3, slot, slot);
                                            assertNotEquals(0L, handles[slot]);
                                        }
                                        if (pins[slot] == 0L && random.nextInt(3) == 0) {
                                            pins[slot] = slab.pin(handles[slot]);
                                        }
                                        if (pins[slot] != 0L && random.nextInt(3) == 0) {
                                            assertTrue(slab.settle(slab.identity(), handles[slot], pins[slot]));
                                            assertFalse(slab.settle(slab.identity(), handles[slot], pins[slot]));
                                            pins[slot] = 0L;
                                        }
                                        if (pins[slot] == 0L && random.nextInt(4) == 0) {
                                            assertTrue(slab.retire(handles[slot]));
                                            handles[slot] = 0L;
                                        }
                                    }
                                    for (int slot = 0; slot < capacity; slot++) {
                                        if (pins[slot] != 0L) assertTrue(slab.settle(slab.identity(), handles[slot], pins[slot]));
                                        if (handles[slot] != 0L) assertTrue(slab.retire(handles[slot]));
                                    }
                                    assertEquals(0, slab.live(), "no slot leaks after stress");
                                    slab.close();
                                }
                            } catch (Throwable problem) {
                                failure.set(problem);
                            } finally {
                                finished.countDown();
                            }
                        });
                actor.send(1);
                assertTrue(finished.await(10, TimeUnit.SECONDS));
                assertNull(failure.get());
                actor.stop();
                assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void claimSerialExhaustionTombstonesThePhysicalSlot() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch finished = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            ActorRuntime.ActorRef<Integer> actor = runtime.spawnSharedTrusted(
                    factory -> (message, turn) -> {
                        try {
                            for (ActorProxyRequestSlab.Layout layout
                                    : ActorProxyRequestSlab.Layout.values()) {
                                int lanes = layout == ActorProxyRequestSlab.Layout.SOA ? 1 : 32;
                                ActorProxyRequestSlab slab =
                                        new ActorProxyRequestSlab(layout, 1, lanes);
                                long request = slab.admit(3, 7, 8);
                                assertNotEquals(0, request);
                                Field storage = ActorProxyRequestSlab.class
                                        .getDeclaredField("storage");
                                storage.setAccessible(true);
                                int[] words = (int[]) storage.get(slab);
                                int serialOffset = layout == ActorProxyRequestSlab.Layout.SOA
                                        ? 7 : 7 * lanes;
                                words[serialOffset] = Integer.MAX_VALUE;
                                assertThrows(IllegalStateException.class,
                                        () -> slab.pin(request));
                                assertEquals(1, slab.live());
                                assertTrue(slab.retire(request));
                                assertEquals(0, slab.live());
                                assertTrue(slab.full(),
                                        "a permanently exhausted slot is not reusable");
                                assertEquals(0, slab.admit(4, 7, 8));
                                slab.close();
                            }
                        } catch (Throwable error) {
                            failure.set(error);
                        } finally {
                            finished.countDown();
                        }
                    });
            actor.send(1);
            assertTrue(finished.await(5, TimeUnit.SECONDS));
            assertNull(failure.get(), "serial exhaustion must preserve quota/slot safety");
            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void sameRawClaimCannotSettleDifferentSlabOrRequest() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch finished = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            ActorRuntime.ActorRef<Integer> actor = runtime.spawnSharedTrusted(
                    factory -> (message, turn) -> {
                        try {
                            ActorProxyRequestSlab a =
                                    new ActorProxyRequestSlab(ActorProxyRequestSlab.Layout.SOA, 1, 1);
                            ActorProxyRequestSlab b =
                                    new ActorProxyRequestSlab(ActorProxyRequestSlab.Layout.AOSOA, 1, 32);
                            assertNotEquals(a.identity(), b.identity());
                            long aTicket = a.admit(5, 10, 1);
                            long bTicket = b.admit(6, 20, 2);
                            long aClaim = a.pin(aTicket);
                            long bClaim = b.pin(bTicket);
                            assertEquals(aClaim, bClaim, "same raw receipt in different slabs");
                            assertTrue(a.retire(aTicket));
                            assertTrue(b.retire(bTicket));
                            assertFalse(b.settle(a.identity(), aTicket, aClaim));
                            assertFalse(a.settle(b.identity(), bTicket, bClaim));
                            assertEquals(1, a.live());
                            assertEquals(1, b.live());
                            assertTrue(a.settle(a.identity(), aTicket, aClaim));
                            assertFalse(a.settle(a.identity(), aTicket, aClaim));
                            assertTrue(b.settle(b.identity(), bTicket, bClaim));
                            assertFalse(b.settle(b.identity(), bTicket, bClaim));
                            assertEquals(0, a.live());
                            assertEquals(0, b.live());
                            a.close();
                            b.close();
                        } catch (Throwable problem) {
                            failure.set(problem);
                        } finally {
                            finished.countDown();
                        }
                    });
            actor.send(1);
            assertTrue(finished.await(5, TimeUnit.SECONDS));
            assertNull(failure.get(), "cross-slab receipt collision must fail closed");
            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

}
