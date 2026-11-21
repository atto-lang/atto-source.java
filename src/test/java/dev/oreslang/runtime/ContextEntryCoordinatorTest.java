package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Deterministic lock/ownership tests, complemented by real Polyglot tests. */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
final class ContextEntryCoordinatorTest {
    @Test
    void alreadyRunningGuestPhysicallyExitsBeforeExclusiveHostEntry() throws Exception {
        var gate = new ContextEntryCoordinator();
        var guestInside = new CountDownLatch(1);
        var releaseGuest = new CountDownLatch(1);
        var hostEntered = new CountDownLatch(1);
        var releaseHost = new CountDownLatch(1);
        var guestDone = new CountDownLatch(1);
        var guestBodyExited = new AtomicBoolean();
        var pool = Executors.newFixedThreadPool(2);
        try {
            var guest = pool.submit(() -> {
                gate.executeGuest(() -> {
                    guestInside.countDown();
                    try {
                        assertTrue(releaseGuest.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException interruption) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interruption);
                    }
                    guestBodyExited.set(true);
                });
                guestDone.countDown();
            });
            assertTrue(guestInside.await(5, TimeUnit.SECONDS));
            var host = pool.submit(() -> {
                try (var lease = gate.enterHost()) {
                    assertTrue(guestBodyExited.get(),
                            "host must not acquire entry before the guest turn exits");
                    hostEntered.countDown();
                    assertTrue(releaseHost.await(5, TimeUnit.SECONDS));
                }
                return null;
            });
            assertFalse(hostEntered.await(100, TimeUnit.MILLISECONDS));
            releaseGuest.countDown();
            assertTrue(guestDone.await(5, TimeUnit.SECONDS));
            assertTrue(hostEntered.await(5, TimeUnit.SECONDS));
            var admitted = pool.submit(() -> gate.executeGuest(() ->
                    assertEquals(0, releaseHost.getCount(),
                            "second guest must wait until host physical release")));
            assertFalse(admitted.isDone());
            releaseHost.countDown();
            guest.get(5, TimeUnit.SECONDS);
            host.get(5, TimeUnit.SECONDS);
            admitted.get(5, TimeUnit.SECONDS);
        } finally {
            releaseGuest.countDown();
            releaseHost.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void guestCannotUpgradeReadOwnershipIntoExclusiveHostLease() {
        var gate = new ContextEntryCoordinator();
        gate.executeGuest(() ->
                assertThrows(SecurityException.class, gate::enterHost));
    }

    @Test
    void hostLeaseIsBoundToExactPhysicalThreadAndOneClose() throws Exception {
        var gate = new ContextEntryCoordinator();
        var host = gate.enterHost();
        var pool = Executors.newSingleThreadExecutor();
        try {
            pool.submit(() -> assertThrows(SecurityException.class, host::close))
                    .get(5, TimeUnit.SECONDS);
            host.close();
            assertThrows(IllegalStateException.class, host::close);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void retirementSealsNewHostBeforePhysicalGuestCleanupIsRetired() {
        var gate = new ContextEntryCoordinator();
        gate.beginRetirement();
        assertTrue(gate.hostSealed());
        assertThrows(RejectedExecutionException.class, gate::enterHost);
        var cleaned = new AtomicBoolean();
        gate.executeGuest(() -> cleaned.set(true));
        assertTrue(cleaned.get(), "previously admitted cleanup still needs guest entry");
        gate.finishRetirement();
        assertTrue(gate.guestsRetired());
        assertThrows(RejectedExecutionException.class,
                () -> gate.executeGuest(() -> fail("retired guest ran")));
    }

    @Test
    void hostScopeCannotRetireItsOwnExclusiveContext() {
        var gate = new ContextEntryCoordinator();
        try (var host = gate.enterHost()) {
            assertThrows(IllegalStateException.class, gate::beginRetirement);
            assertFalse(gate.hostSealed());
        }
    }
}
