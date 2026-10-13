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
    void pendingAsyncMutexFutureRejectsBlockingJoinGetAndTimedGetOnScheduler()
            throws Exception {
        var shared = OresMutex.shared(5);
        try (var owner = shared.lock()) {
            try (var scheduler = new OresScheduler(1)) {
                assertTrue(scheduler.startSync(() -> {
                    var pending = shared.lockAsync();
                    assertFalse(pending.isDone());
                    assertThrows(OresMutex.WrongMutexDomainException.class, pending::join);
                    assertThrows(OresMutex.WrongMutexDomainException.class, pending::get);
                    assertThrows(OresMutex.WrongMutexDomainException.class,
                            () -> pending.get(1, TimeUnit.SECONDS));
                    assertThrows(java.util.concurrent.TimeoutException.class,
                            () -> pending.get(0, TimeUnit.SECONDS));
                    var derived = pending.thenApply(mutexGuard -> 21);
                    assertTrue(derived instanceof OresMutex.CarrierSafeFuture<?>,
                            "dependent stages must retain the nonblocking policy");
                    assertThrows(OresMutex.WrongMutexDomainException.class, derived::join);
                    assertThrows(OresMutex.WrongMutexDomainException.class, derived::get);
                    assertThrows(OresMutex.WrongMutexDomainException.class,
                            () -> derived.get(1, TimeUnit.SECONDS));
                    assertThrows(java.util.concurrent.TimeoutException.class,
                            () -> derived.get(0, TimeUnit.SECONDS));
                    assertTrue(pending.cancel(false));
                    return true;
                }).join());
                assertEquals(13, scheduler.startSync(() -> 13).join());
            }
        }
    }

    @Test
    void asyncSharedMutexGuardCannotEscapeItsOwningSchedulerTask() throws Exception {
        var shared = OresMutex.shared(31);
        var resume = new OresFuture<Void>();
        var acquired = new CountDownLatch(1);
        var guard = new AtomicReference<OresMutex.Guard<Integer>>();
        try (var scheduler = new OresScheduler(1)) {
            var owner = scheduler.start(turn -> {
                if (turn.initial()) {
                    var ready = shared.lockAsync();
                    assertTrue(ready.isDone());
                    guard.set(ready.join());
                    acquired.countDown();
                    return OresScheduler.await(resume);
                }
                assertEquals(31, guard.get().value());
                guard.get().release();
                return OresScheduler.done(57);
            });
            assertTrue(acquired.await(5, TimeUnit.SECONDS));
            assertTrue(scheduler.startSync(() -> {
                assertThrows(OresMutex.WrongMutexDomainException.class,
                        () -> guard.get().value(),
                        "an async guard must not be exposed to another task on one carrier");
                return true;
            }).join());
            assertTrue(resume.completeFromRuntime(null));
            assertEquals(57, owner.join());
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
                assertThrows(OresMutex.WrongMutexDomainException.class,
                        () -> shared.recover(value -> value),
                        "contended SharedMutex recovery cannot park the scheduler");
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
