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
                                    assertTrue(slab.retire(a));
                                    assertTrue(slab.retire(a));
                                    assertEquals(2, slab.pending(a));
                                    assertThrows(IllegalStateException.class, () -> slab.pin(a));
                                    assertEquals(0L, slab.admit(9, 6, 13));
                                    assertTrue(slab.settle(second));
                                    assertFalse(slab.settle(second));
                                    assertEquals(3, slab.live());
                                    assertTrue(slab.settle(first));
                                    assertEquals(2, slab.live());
                                    long reused = slab.admit(9, 6, 13);
                                    assertNotEquals(a, reused);
                                    assertEquals(13, slab.route(reused));
                                    assertFalse(slab.retire(a));
                                    assertFalse(slab.settle(first));
                                    assertThrows(IllegalStateException.class, () -> slab.route(a));
                                    assertFalse(slab.settle((first & 0xffffffffL) | (99L << 32)));
                                    assertTrue(slab.retire(b));
                                    assertEquals(3, slab.live());
                                    assertTrue(slab.settle(other));
                                    assertTrue(slab.retire(c));
                                    assertTrue(slab.retire(reused));
                                    assertEquals(0, slab.live());
                                    slab.close();
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
                                assertTrue(slab.settle(receipts[i]));
                                assertFalse(slab.settle(receipts[i]));
                                assertEquals(1, slab.live());
                            }
                            assertTrue(slab.settle(receipts[0]));
                            assertEquals(0, slab.live());
                            assertFalse(slab.settle(receipts[0]));
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
                    if (!slab.retire(req) || slab.live() != 1 || !slab.settle(pin)
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
                                            assertTrue(slab.settle(pins[slot]));
                                            assertFalse(slab.settle(pins[slot]));
                                            pins[slot] = 0L;
                                        }
                                        if (pins[slot] == 0L && random.nextInt(4) == 0) {
                                            assertTrue(slab.retire(handles[slot]));
                                            handles[slot] = 0L;
                                        }
                                    }
                                    for (int slot = 0; slot < capacity; slot++) {
                                        if (pins[slot] != 0L) assertTrue(slab.settle(pins[slot]));
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

}
