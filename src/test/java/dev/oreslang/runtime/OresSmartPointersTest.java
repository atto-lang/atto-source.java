package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class OresSmartPointersTest {
    @Test
    void boxConsumesExactlyOnce() {
        var box = OresSmartPointers.box("payload");
        assertEquals("payload", box.value());
        assertEquals("payload", box.take());
        assertFalse(box.live());
        assertThrows(IllegalStateException.class, box::value);
        assertThrows(IllegalStateException.class, box::take);
        assertDoesNotThrow(box::close);
    }

    @Test
    void arcStrongWeakLifecycleIsDeterministic() {
        var first = OresSmartPointers.arc("shared");
        var second = first.retain();
        var weak = first.downgrade();

        assertEquals(2, first.strongCount());
        assertEquals(1, first.weakCount());
        var upgraded = weak.upgrade().orElseThrow();
        assertEquals("shared", upgraded.value());
        assertEquals(3, first.strongCount());
        upgraded.close();
        assertEquals(2, first.strongCount());

        first.close();
        assertEquals(1, second.strongCount());
        second.close();
        assertTrue(weak.upgrade().isEmpty());
        weak.close();

        var owner = OresSmartPointers.arc(7);
        var expiry = owner.downgrade();
        owner.close();
        assertTrue(expiry.upgrade().isEmpty());
        assertEquals(0, expiry.strongCount());
        expiry.close();
    }

    @Test
    void arcClosedHandlesCannotBeReused() {
        var arc = OresSmartPointers.arc(1);
        arc.close();
        assertTrue(arc.closed());
        assertThrows(IllegalStateException.class, arc::value);
        assertThrows(IllegalStateException.class, arc::retain);
        assertThrows(IllegalStateException.class, arc::downgrade);
        assertDoesNotThrow(arc::close);
    }

    @Test
    void arcRetainAndCloseOnSameHandleDoNotResurrectDroppedPayload() throws Exception {
        var workers = Executors.newFixedThreadPool(2);
        try {
            for (int iteration = 0; iteration < 1_024; ++iteration) {
                var owner = OresSmartPointers.arc(42);
                var observer = owner.downgrade();
                var start = new CountDownLatch(1);
                var retainedFuture = workers.submit(() -> {
                    start.await();
                    try {
                        return owner.retain();
                    } catch (IllegalStateException closedBeforeRetain) {
                        return null;
                    }
                });
                var closeFuture = workers.submit(() -> {
                    start.await();
                    owner.close();
                    return null;
                });
                start.countDown();
                var retained = retainedFuture.get(10, TimeUnit.SECONDS);
                closeFuture.get(10, TimeUnit.SECONDS);
                if (retained != null) {
                    assertEquals(42, retained.value().intValue());
                    assertEquals(1, observer.strongCount());
                    retained.close();
                }
                assertEquals(0, observer.strongCount());
                assertTrue(observer.upgrade().isEmpty());
                observer.close();
            }
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void arcDowngradeAndCloseOnSameHandleAreSerialized() throws Exception {
        var workers = Executors.newFixedThreadPool(2);
        try {
            for (int iteration = 0; iteration < 256; ++iteration) {
                var owner = OresSmartPointers.arc(42);
                var start = new CountDownLatch(1);
                var downgradeFuture = workers.submit(() -> {
                    start.await();
                    try {
                        return owner.downgrade();
                    } catch (IllegalStateException closedBeforeDowngrade) {
                        return null;
                    }
                });
                var closeFuture = workers.submit(() -> {
                    start.await();
                    owner.close();
                    return null;
                });
                start.countDown();
                var weak = downgradeFuture.get(10, TimeUnit.SECONDS);
                closeFuture.get(10, TimeUnit.SECONDS);
                if (weak != null) {
                    assertEquals(0, weak.strongCount());
                    assertTrue(weak.upgrade().isEmpty());
                    weak.close();
                }
            }
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void weakUpgradeAndCloseOnSameHandleAreSerialized() throws Exception {
        var workers = Executors.newFixedThreadPool(2);
        try {
            for (int iteration = 0; iteration < 512; ++iteration) {
                var owner = OresSmartPointers.arc(42);
                var weak = owner.downgrade();
                var start = new CountDownLatch(1);
                var upgradeFuture = workers.submit(() -> {
                    start.await();
                    try {
                        return weak.upgrade().orElse(null);
                    } catch (IllegalStateException closedBeforeUpgrade) {
                        return null;
                    }
                });
                var closeFuture = workers.submit(() -> {
                    start.await();
                    weak.close();
                    return null;
                });
                start.countDown();
                var upgraded = upgradeFuture.get(10, TimeUnit.SECONDS);
                closeFuture.get(10, TimeUnit.SECONDS);
                if (upgraded != null) {
                    assertEquals(42, upgraded.value().intValue());
                    upgraded.close();
                }
                assertTrue(weak.closed());
                assertThrows(IllegalStateException.class, weak::upgrade);
                owner.close();
            }
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void weakUpgradeRacingFinalStrongDropNeverResurrectsPayload() throws Exception {
        var workers = Executors.newFixedThreadPool(2);
        try {
            for (int iteration = 0; iteration < 1_024; ++iteration) {
                var owner = OresSmartPointers.arc(42);
                var weak = owner.downgrade();
                var start = new CountDownLatch(1);
                var upgradedFuture = workers.submit(() -> {
                    start.await();
                    return weak.upgrade().orElse(null);
                });
                var dropFuture = workers.submit(() -> {
                    start.await();
                    owner.close();
                    return null;
                });
                start.countDown();
                var upgraded = upgradedFuture.get(10, TimeUnit.SECONDS);
                dropFuture.get(10, TimeUnit.SECONDS);
                if (upgraded != null) {
                    assertEquals(42, upgraded.value().intValue());
                    assertEquals(1, weak.strongCount(),
                            "a successful upgrade must own the last strong handle");
                    upgraded.close();
                }
                assertEquals(0, weak.strongCount());
                assertTrue(weak.upgrade().isEmpty(),
                        "the last drop must permanently reject resurrection");
                weak.close();
            }
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void rcIsCarrierLocalAndWeakDoesNotKeepPayloadAlive() throws Exception {
        var rc = OresSmartPointers.rc("local");
        var clone = rc.retain();
        var weak = rc.downgrade();
        assertEquals(2, rc.strongCount());
        assertEquals(1, rc.weakCount());

        AtomicReference<Throwable> crossThread = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                rc.value();
            } catch (Throwable failure) {
                crossThread.set(failure);
            }
        });
        thread.start();
        thread.join();
        assertInstanceOf(IllegalStateException.class, crossThread.get());

        rc.close();
        assertEquals(1, clone.strongCount());
        clone.close();
        assertTrue(weak.upgrade().isEmpty());
        weak.close();
    }

    @Test
    void rcWeakUpgradeKeepsPayloadAliveUntilItsOwnDrop() {
        var owner = OresSmartPointers.rc(42);
        var first = owner.downgrade();
        var second = owner.downgrade();
        assertEquals(2, owner.weakCount());

        var upgraded = first.upgrade().orElseThrow();
        assertEquals(2, owner.strongCount());
        owner.close();
        assertEquals(42, upgraded.value().intValue());
        assertEquals(1, second.strongCount());

        upgraded.close();
        assertEquals(0, second.strongCount());
        assertTrue(first.upgrade().isEmpty());
        assertTrue(second.upgrade().isEmpty());
        assertEquals(2, second.weakCount());
        first.close();
        assertEquals(1, second.weakCount());
        second.close();
        assertThrows(IllegalStateException.class, first::upgrade);
    }

    @Test
    void rcWeakCannotUpgradeOnForeignCarrier() throws Exception {
        var owner = OresSmartPointers.rc(42);
        var weak = owner.downgrade();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread foreign = new Thread(() -> {
            try {
                weak.upgrade();
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        });
        foreign.start();
        foreign.join();
        assertInstanceOf(IllegalStateException.class, failure.get());
        assertEquals(1, owner.strongCount(),
                "foreign carrier must not increment local Rc strong count");
        weak.close();
        owner.close();
    }

    @Test
    void refCellGuardsReleaseOneBorrowAndCannotBeReused() {
        var cell = OresSmartPointers.refCell(5);
        var read = cell.borrow();
        assertEquals(5, read.value().intValue());
        assertThrows(IllegalStateException.class, cell::borrowMut);
        read.close();
        read.close();
        assertThrows(IllegalStateException.class, read::value);

        var write = cell.borrowMut();
        assertEquals(5, write.value().intValue());
        write.set(6);
        assertThrows(IllegalStateException.class, cell::borrow);
        write.close();
        write.close();
        assertThrows(IllegalStateException.class, write::value);
        assertThrows(IllegalStateException.class, () -> write.set(7));
        try (var finalRead = cell.borrow()) {
            assertEquals(6, finalRead.value().intValue());
        }
    }

    @Test
    void refCellEnforcesDynamicBorrowExclusivity() {
        var cell = OresSmartPointers.refCell(1);

        try (var first = cell.borrow();
             var second = cell.borrow()) {
            assertEquals(1, first.value());
            assertEquals(1, second.value());
            assertThrows(IllegalStateException.class, cell::borrowMut);
        }

        try (var mutable = cell.borrowMut()) {
            assertEquals(1, mutable.value());
            mutable.set(2);
            assertEquals(2, mutable.value());
            assertThrows(IllegalStateException.class, cell::borrow);
            assertThrows(IllegalStateException.class, cell::borrowMut);
        }

        try (var read = cell.borrow()) {
            assertEquals(2, read.value());
        }
    }

    @Test
    void foreignCarrierCannotReleaseSharedOrMutableGuards() throws Exception {
        var cell = OresSmartPointers.refCell(5);
        var first = cell.borrow();
        var second = cell.borrow();
        AtomicReference<Throwable> foreignSharedClose = new AtomicReference<>();
        Thread readerClose = new Thread(() -> {
            try {
                first.close();
            } catch (Throwable failure) {
                foreignSharedClose.set(failure);
            }
        });
        readerClose.start();
        readerClose.join();
        assertInstanceOf(IllegalStateException.class, foreignSharedClose.get());
        assertEquals(5, first.value().intValue());
        first.close();
        // The other shared loan must still block an exclusive borrow.
        assertThrows(IllegalStateException.class, cell::borrowMut);
        second.close();

        var write = cell.borrowMut();
        AtomicReference<Throwable> foreignMutableClose = new AtomicReference<>();
        Thread writerClose = new Thread(() -> {
            try {
                write.close();
            } catch (Throwable failure) {
                foreignMutableClose.set(failure);
            }
        });
        writerClose.start();
        writerClose.join();
        assertInstanceOf(IllegalStateException.class, foreignMutableClose.get());
        assertThrows(IllegalStateException.class, cell::borrow);
        assertThrows(IllegalStateException.class, cell::borrowMut);
        write.set(9);
        write.close();
        try (var again = cell.borrow()) {
            assertEquals(9, again.value().intValue());
        }
    }

    @Test
    void sharedBorrowRemainsLiveUntilItsLastGuardCloses() {
        var cell = OresSmartPointers.refCell(3);
        var first = cell.borrow();
        var second = cell.borrow();
        var third = cell.borrow();
        first.close();
        second.close();
        assertEquals(3, third.value().intValue());
        assertThrows(IllegalStateException.class, cell::borrowMut);
        third.close();
        try (var writer = cell.borrowMut()) {
            writer.set(4);
        }
        try (var reader = cell.borrow()) {
            assertEquals(4, reader.value().intValue());
        }
    }

    @Test
    void refCellGuardCannotCrossCarrierThread() throws Exception {
        var cell = OresSmartPointers.refCell(1);
        var read = cell.borrow();

        AtomicReference<Throwable> crossThread = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                read.value();
            } catch (Throwable failure) {
                crossThread.set(failure);
            }
        });
        thread.start();
        thread.join();

        assertInstanceOf(IllegalStateException.class, crossThread.get());
        read.close();
    }
}
