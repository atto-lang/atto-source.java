package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
final class MutexResourceAccountingTest {
    @Test void tracksLocalSharedPendingCancelledAndFailedResources() {
        int baseline = OresMutex.guardResourceCount();
        var local = OresMutex.local(1);
        var guard = local.lock();
        assertEquals(baseline + 1, OresMutex.guardResourceCount());
        guard.release(); guard.release();
        assertEquals(baseline, OresMutex.guardResourceCount());
        var shared = OresMutex.shared(1);
        var first = shared.lock();
        assertEquals(baseline + 1, OresMutex.guardResourceCount());
        first.fail();
        assertEquals(baseline, OresMutex.guardResourceCount());
        assertTrue(shared.lockAsync().isCompletedExceptionally());
        assertEquals(baseline, OresMutex.guardResourceCount());
    }

    @Test void cancellationRacingWithHandoffNeverLosesGuardAccounting() throws Exception {
        int baseline = OresMutex.guardResourceCount();
        var shared = OresMutex.shared(1);
        for (int i = 0; i < 300; i++) {
            var held = shared.lock();
            var result = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CompletableFuture<OresMutex.Guard<Integer>>>();
            Thread register = Thread.ofPlatform().start(() -> result.set(shared.lockAsync()));
            register.join();
            var future = result.get();
            assertFalse(future.isDone());
            assertEquals(baseline + 2, OresMutex.guardResourceCount());
            Thread cancel = Thread.ofPlatform().start(() -> future.cancel(false));
            held.release(); cancel.join();
            if (!future.isCancelled()) future.join().release();
            assertEquals(baseline, OresMutex.guardResourceCount(), "iteration " + i);
        }
    }
    @Test void localAdmissionAndRejectedReleasePreserveExactLiveCount() throws Exception {
        int baseline = OresMutex.guardResourceCount();
        var mutex = OresMutex.local(1);
        var guard = mutex.lock();
        try {
            assertEquals(baseline + 1, OresMutex.guardResourceCount());
            assertTrue(mutex.tryLock().isEmpty());
            assertThrows(OresMutex.RecursiveLockException.class, mutex::lock);
            var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            Thread wrongDomain = Thread.ofPlatform().start(() -> {
                try { guard.release(); } catch (Throwable error) { failure.set(error); }
            });
            wrongDomain.join();
            assertInstanceOf(OresMutex.WrongMutexDomainException.class, failure.get());
            assertFalse(guard.released());
            assertEquals(baseline + 1, OresMutex.guardResourceCount());
        } finally {
            guard.release();
        }
        guard.release();
        assertEquals(baseline, OresMutex.guardResourceCount());
    }

}
