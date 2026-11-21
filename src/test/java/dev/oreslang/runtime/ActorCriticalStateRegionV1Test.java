package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

final class ActorCriticalStateRegionV1Test {
    @Test void writerRemainsAheadOf128NewerReaders() {
        var region = new ActorCriticalStateRegionV1(11, new long[]{0}, 256);
        Object heldOwner = new Object();
        region.request(heldOwner, 11, ActorCriticalStateRegionV1.Mode.READ);
        var held = region.admitReady().getFirst();
        Object writerOwner = new Object();
        region.request(writerOwner, 11, ActorCriticalStateRegionV1.Mode.WRITE);
        for (int i = 0; i < 128; ++i) {
            region.request(new Object(), 11, ActorCriticalStateRegionV1.Mode.READ);
        }
        assertEquals(129, region.pendingCount());
        assertTrue(region.admitReady().isEmpty());
        held.close();
        var writer = region.admitReady();
        assertEquals(1, writer.size());
        assertEquals(ActorCriticalStateRegionV1.Mode.WRITE, writer.getFirst().mode());
        assertTrue(region.admitReady().isEmpty());
        try (var scope = writer.getFirst()) {
            assertEquals(42L, scope.checkedAddInt(0, 42));
        }
        var readers = region.admitReady();
        assertEquals(128, readers.size());
        for (var scope : readers) {
            try (scope) {
                assertEquals(42L, scope.readInt(0));
                assertThrows(IllegalStateException.class, () -> scope.checkedAddInt(0, 1));
            }
        }
        assertEquals(0, region.activeCount());
        assertEquals(0, region.pendingCount());
    }

    @Test void stopFencesLiveWriterWithoutForceReclaimOrParking() throws Exception {
        var region = new ActorCriticalStateRegionV1(9, new long[]{3}, 3);
        Object handler = new Object();
        region.request(handler, 9, ActorCriticalStateRegionV1.Mode.WRITE);
        var active = region.admitReady().getFirst();
        region.request(new Object(), 9, ActorCriticalStateRegionV1.Mode.READ);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var forbidden = new AtomicBoolean();
        Thread worker = Thread.ofPlatform().start(() -> {
            try (active) {
                assertEquals(5L, active.checkedAddInt(0, 2));
                entered.countDown();
                try {
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
                forbidden.set(assertThrows(IllegalStateException.class,
                        () -> active.checkedAddInt(0, 1)).getMessage().contains("RETIRED"));
            }
        });
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            region.retire(10);
            assertEquals(0, region.pendingCount());
            assertEquals(1, region.activeCount());
            assertFalse(region.mayReclaim());
            assertThrows(IllegalStateException.class,
                    () -> region.request(new Object(), 9, ActorCriticalStateRegionV1.Mode.READ));
            assertThrows(IllegalStateException.class,
                    () -> region.request(new Object(), 10, ActorCriticalStateRegionV1.Mode.READ));
        } finally {
            release.countDown();
            worker.join(10_000);
        }
        assertFalse(worker.isAlive());
        assertTrue(forbidden.get());
        assertTrue(region.mayReclaim());
        assertEquals(0, region.activeCount());
        active.close();
        region.retire(10);
    }

    @Test void checkedOverflowNeverPartiallyCommitsAndScopesCloseOnFailure() {
        var region = new ActorCriticalStateRegionV1(3, new long[]{Long.MAX_VALUE - 1}, 2);
        region.request(new Object(), 3, ActorCriticalStateRegionV1.Mode.WRITE);
        try (var scope = region.admitReady().getFirst()) {
            assertThrows(ArithmeticException.class, () -> scope.checkedAddInt(0, 3));
            assertEquals(Long.MAX_VALUE - 1, scope.readInt(0));
            assertEquals(Long.MAX_VALUE, scope.checkedAddInt(0, 1));
        }
        region.request(new Object(), 3, ActorCriticalStateRegionV1.Mode.READ);
        var loan = region.admitReady().getFirst();
        try (loan) { assertEquals(Long.MAX_VALUE, loan.readInt(0)); }
        assertThrows(IllegalStateException.class, () -> loan.readInt(0));
        assertEquals(0, region.activeCount());
    }

    @Test void twoReadersOverlapAndWriterWaitsUntilBothRelease() {
        var region = new ActorCriticalStateRegionV1(7, new long[]{17}, 4);
        region.request(new Object(), 7, ActorCriticalStateRegionV1.Mode.READ);
        region.request(new Object(), 7, ActorCriticalStateRegionV1.Mode.READ);
        var readers = region.admitReady();
        assertEquals(2, readers.size());
        assertEquals(2, region.activeCount());
        region.request(new Object(), 7, ActorCriticalStateRegionV1.Mode.WRITE);
        assertTrue(region.admitReady().isEmpty());
        readers.get(0).close();
        assertTrue(region.admitReady().isEmpty());
        readers.get(1).close();
        try (var writer = region.admitReady().getFirst()) {
            assertEquals(19L, writer.checkedAddInt(0, 2));
        }
        assertEquals(0, region.activeCount());
    }

    @Test void staleGenerationReentrancyAndCancelledTicketsAreDenied() {
        var region = new ActorCriticalStateRegionV1(17, new long[]{1}, 1);
        Object actor = new Object();
        Object cancelled = new Object();
        assertThrows(IllegalStateException.class,
                () -> region.request(actor, 16, ActorCriticalStateRegionV1.Mode.READ));
        region.request(actor, 17, ActorCriticalStateRegionV1.Mode.READ);
        assertThrows(IllegalStateException.class,
                () -> region.request(actor, 17, ActorCriticalStateRegionV1.Mode.WRITE));
        assertThrows(IllegalStateException.class,
                () -> region.request(cancelled, 17, ActorCriticalStateRegionV1.Mode.READ));
        assertEquals(1, region.pendingCount());
        assertTrue(region.cancelPending(actor));
        assertFalse(region.cancelPending(actor));
        region.request(cancelled, 17, ActorCriticalStateRegionV1.Mode.WRITE);
        try (var writer = region.admitReady().getFirst()) {
            assertEquals(3L, writer.checkedAddInt(0, 2));
            assertThrows(IllegalStateException.class,
                    () -> region.request(cancelled, 17, ActorCriticalStateRegionV1.Mode.READ));
        }
        region.request(cancelled, 17, ActorCriticalStateRegionV1.Mode.READ);
        var reused = region.admitReady().getFirst();
        try (reused) { assertEquals(3L, reused.readInt(0)); }
    }

    @Test void malformedRegionAndScopeParamsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new ActorCriticalStateRegionV1(0, new long[]{1}, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new ActorCriticalStateRegionV1(Long.MAX_VALUE, new long[]{1}, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new ActorCriticalStateRegionV1(1, new long[0], 1));
        assertThrows(IllegalArgumentException.class,
                () -> new ActorCriticalStateRegionV1(1, new long[33], 1));
        assertThrows(IllegalArgumentException.class,
                () -> new ActorCriticalStateRegionV1(1, new long[]{1}, 0));
        var region = new ActorCriticalStateRegionV1(1, new long[]{0}, 1);
        assertThrows(NullPointerException.class,
                () -> region.request(null, 1, ActorCriticalStateRegionV1.Mode.READ));
        assertThrows(NullPointerException.class, () -> region.request(new Object(), 1, null));
        region.request(new Object(), 1, ActorCriticalStateRegionV1.Mode.READ);
        try (var scope = region.admitReady().getFirst()) {
            assertThrows(IndexOutOfBoundsException.class, () -> scope.readInt(-1));
            assertThrows(IndexOutOfBoundsException.class, () -> scope.readInt(1));
        }
        assertFalse(region.mayReclaim());
        assertThrows(IllegalArgumentException.class, () -> region.retire(1));
        region.retire(2);
        assertTrue(region.mayReclaim());
    }
}
