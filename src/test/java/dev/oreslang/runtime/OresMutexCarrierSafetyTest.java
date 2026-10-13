package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Scheduler turns must not park carriers or use thread-affine mutex identities. */
final class OresMutexCarrierSafetyTest {
    @Test
    void blockingSharedMutexAcquisitionIsForbiddenOnOrdinarySchedulerCarrier()
            throws Exception {
        var shared = OresMutex.shared(7);
        try (var scheduler = new OresScheduler(1)) {
            assertTrue(scheduler.startSync(() -> {
                assertTrue(OresScheduler.isSchedulerCarrierThread());
                assertThrows(OresMutex.WrongMutexDomainException.class, shared::lock);
                assertThrows(OresMutex.WrongMutexDomainException.class,
                        () -> shared.lockFor(Duration.ofMillis(20)));
                assertThrows(OresMutex.WrongMutexDomainException.class,
                        () -> shared.withLock(value -> value + 1));
                // This is a nonblocking polling API even when the deadline is zero.
                try (var guard = shared.lockFor(Duration.ZERO).orElseThrow()) {
                    assertEquals(7, guard.value());
                }
                try (var guard = shared.tryLock().orElseThrow()) {
                    assertEquals(7, guard.value());
                }
                return true;
            }).join());
            assertEquals(37, scheduler.startSync(() -> 37).join(),
                    "denied blocking mutex calls must not stall subsequent tasks");
        }
    }

    @Test
    void twoSchedulerTasksSharingOnePhysicalCarrierDoNotShareLocalMutexAuthority() {
        try (var scheduler = new OresScheduler(1)) {
            var local = scheduler.startSync(() -> OresMutex.local(19)).join();
            assertTrue(scheduler.startSync(() -> {
                assertThrows(OresMutex.WrongMutexDomainException.class, local::lock);
                assertThrows(OresMutex.WrongMutexDomainException.class, local::tryLock);
                return true;
            }).join());
        }
    }

    @Test
    void sharedMutexGuardRemainsBoundToLogicalTaskAcrossAwaitAndNotCarrier()
            throws Exception {
        var shared = OresMutex.shared(17);
        var resume = new OresFuture<Void>();
        var acquired = new CountDownLatch(1);
        var guard = new AtomicReference<OresMutex.Guard<Integer>>();
        try (var scheduler = new OresScheduler(1)) {
            var taskA = scheduler.start(turn -> {
                if (turn.initial()) {
                    guard.set(shared.tryLock().orElseThrow());
                    acquired.countDown();
                    return OresScheduler.await(resume);
                }
                assertEquals(17, guard.get().value(),
                        "logical owner remains stable when its continuation resumes");
                guard.get().close();
                return OresScheduler.done(41);
            });
            assertTrue(acquired.await(5, TimeUnit.SECONDS));
            assertTrue(scheduler.startSync(() -> {
                assertThrows(OresMutex.WrongMutexDomainException.class,
                        () -> guard.get().value(),
                        "a different logical task cannot steal the guard on the same carrier");
                assertTrue(shared.tryLock().isEmpty());
                assertTrue(shared.lockFor(Duration.ZERO).isEmpty());
                return true;
            }).join());
            assertTrue(resume.completeFromRuntime(null));
            assertEquals(41, taskA.join());
            assertTrue(scheduler.startSync(() -> {
                try (var replacement = shared.tryLock().orElseThrow()) {
                    assertEquals(17, replacement.value());
                }
                return true;
            }).join());
        }
    }
}
