package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** The real OresScheduler TaskHandle + OresMutex synchronous guard path. */
public final class TaskOwnedLockLedgerTest {
    @Test
    void mutexCreatorMayRetireWhileAnotherTaskLaterAcquiresItsGuard() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(2)) {
            AtomicReference<OresMutex.Shared<int[]>> published = new AtomicReference<>();
            TaskHandle<Void> creator = scheduler.startTracked(resume -> {
                published.set(OresMutex.shared(new int[] {3}));
                return OresScheduler.done(null);
            });
            assertEquals(new TaskHandle.Success<Void>(null),
                    creator.getDone().get(3, TimeUnit.SECONDS));

            // A mutex object's creator does not own a later Task's permit.
            TaskHandle<Integer> holder = scheduler.startTracked(resume -> {
                var guard = published.get().tryLock().orElseThrow();
                try {
                    guard.value()[0] += 4;
                    return OresScheduler.done(guard.value()[0]);
                } finally {
                    guard.release();
                }
            });
            assertEquals(new TaskHandle.Success<>(7),
                    holder.getDone().get(3, TimeUnit.SECONDS));
            assertFalse(published.get().isPoisoned());
        }
    }

    @Test
    void unreleasedSharedGuardIsRevokedAndPoisonedBeforeTaskDone() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresMutex.Shared<int[]> mutex = OresMutex.shared(new int[] {0});
            AtomicReference<OresMutex.Guard<int[]>> retained = new AtomicReference<>();
            TaskHandle<Integer> task = scheduler.startTracked(resume -> {
                var guard = mutex.tryLock().orElseThrow();
                guard.value()[0] = 11;
                retained.set(guard); // simulates a leaked lexical guard
                return OresScheduler.done(11);
            });
            assertEquals(new TaskHandle.Success<>(11),
                    task.getDone().get(3, TimeUnit.SECONDS));
            assertTrue(retained.get().released(), "Task DONE preceded guard revocation");
            assertThrows(IllegalStateException.class, retained.get()::value);
            assertTrue(mutex.isPoisoned(), "abandoned mutable state must fail closed");
            assertThrows(OresMutex.PoisonedMutexException.class, mutex::lock);
            mutex.recover(value -> { value[0] = 0; return null; });
            var next = mutex.tryLock().orElseThrow();
            assertEquals(0, next.value()[0]);
            next.release();
        }
    }

    @Test
    void explicitlyReleasedSharedGuardIsNotPoisonedByTaskRetirement() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresMutex.Shared<int[]> mutex = OresMutex.shared(new int[] {0});
            TaskHandle<Integer> task = scheduler.startTracked(resume -> {
                var guard = mutex.tryLock().orElseThrow();
                guard.value()[0] = 7;
                guard.release();
                return OresScheduler.done(7);
            });
            assertEquals(new TaskHandle.Success<>(7),
                    task.getDone().get(3, TimeUnit.SECONDS));
            assertFalse(mutex.isPoisoned());
            var next = mutex.tryLock().orElseThrow();
            assertEquals(7, next.value()[0]);
            next.release();
        }
    }

    @Test
    void cancellationNeverUnlocksRunningTaskBeforeCarrierQuiescence() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresMutex.Shared<int[]> mutex = OresMutex.shared(new int[] {1});
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch leave = new CountDownLatch(1);
            TaskHandle<Void> task = scheduler.startTracked(resume -> {
                var guard = mutex.tryLock().orElseThrow();
                entered.countDown();
                try {
                    if (!leave.await(3, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test barrier timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new CancellationException();
                }
                guard.value()[0] = 4; // old owner is still executing
                return OresScheduler.done(null);
            });
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            try {
                assertTrue(task.cancel("stop"));
                assertTrue(mutex.tryLock().isEmpty(), "permit stolen from live carrier");
                assertFalse(task.getDone().isDone(), "DONE before physical exit");
            } finally {
                leave.countDown();
            }
            assertInstanceOf(TaskHandle.Cancelled.class,
                    task.getDone().get(3, TimeUnit.SECONDS));
            assertTrue(mutex.isPoisoned());
            mutex.recover(value -> { value[0] = 0; return null; });
            var guard = mutex.tryLock().orElseThrow();
            guard.release();
        }
    }

    @Test
    void cancelledTaskCannotAcquireAnotherPermitAfterAdmissionFence() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresMutex.Shared<int[]> mutex = OresMutex.shared(new int[]{2});
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch leave = new CountDownLatch(1);
            AtomicReference<Throwable> rejected = new AtomicReference<>();
            TaskHandle<Void> task = scheduler.startTracked(resume -> {
                entered.countDown();
                try {
                    if (!leave.await(3, TimeUnit.SECONDS))
                        throw new IllegalStateException("test barrier timed out");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new CancellationException();
                }
                try {
                    var guard = mutex.tryLock().orElseThrow();
                    guard.release();
                } catch (Throwable failure) {
                    rejected.set(failure);
                }
                return OresScheduler.done(null);
            });
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            try {
                assertTrue(task.cancel("stop"));
            } finally {
                leave.countDown();
            }
            assertInstanceOf(TaskHandle.Cancelled.class,
                    task.getDone().get(3, TimeUnit.SECONDS));
            assertInstanceOf(CancellationException.class, rejected.get());
            assertFalse(mutex.isPoisoned(), "failed acquisition has no dirty writer");
            var next = mutex.tryLock().orElseThrow();
            next.release();
        }
    }

    @Test
    void trackedPermitsRetireInLifoOrderWithoutRetainingReleasedGuards() {
        TaskOwnedLockLedger ledger = new TaskOwnedLockLedger();
        java.util.List<Integer> releaseOrder = new java.util.ArrayList<>();
        TaskOwnedLockLedger.Lease first = () -> releaseOrder.add(1);
        TaskOwnedLockLedger.Lease second = () -> releaseOrder.add(2);
        TaskOwnedLockLedger.Lease third = () -> releaseOrder.add(3);
        ledger.register(first);
        ledger.register(second);
        ledger.register(third);
        ledger.forget(second); // ordinary explicit guard close
        assertEquals(2, ledger.outstanding());
        ledger.revokeAfterQuiescence();
        assertEquals(java.util.List.of(3, 1), releaseOrder);
        assertEquals(0, ledger.outstanding());
        ledger.revokeAfterQuiescence(); // exactly once
        assertEquals(java.util.List.of(3, 1), releaseOrder);
    }

    @Test
    void hostileTaskCannotRegisterUnboundedPermits() {
        TaskOwnedLockLedger ledger = new TaskOwnedLockLedger();
        for (int i = 0; i < 4096; i++) {
            final int identity = i;
            ledger.register(() -> { if (identity < 0) throw new AssertionError(); });
        }
        assertThrows(IllegalStateException.class, () -> ledger.register(() -> {}));
        assertEquals(4096, ledger.outstanding());
    }

    @Test
    void registerVersusCancellationFenceNeverLosesOwnedPermit() throws Exception {
        for (int round = 0; round < 150; round++) {
            TaskOwnedLockLedger ledger = new TaskOwnedLockLedger();
            java.util.concurrent.atomic.AtomicInteger revoked =
                    new java.util.concurrent.atomic.AtomicInteger();
            AtomicBoolean registered = new AtomicBoolean();
            AtomicReference<Throwable> unexpected = new AtomicReference<>();
            CountDownLatch go = new CountDownLatch(1);
            Thread admission = Thread.ofPlatform().start(() -> {
                try {
                    go.await();
                    ledger.register(revoked::incrementAndGet);
                    registered.set(true);
                } catch (CancellationException expected) {
                    // STOP won before permit registration.
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    unexpected.compareAndSet(null, error);
                } catch (Throwable error) {
                    unexpected.compareAndSet(null, error);
                }
            });
            Thread canceller = Thread.ofPlatform().start(() -> {
                try {
                    go.await();
                    ledger.fence();
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    unexpected.compareAndSet(null, error);
                } catch (Throwable error) {
                    unexpected.compareAndSet(null, error);
                }
            });
            go.countDown();
            admission.join();
            canceller.join();
            assertNull(unexpected.get(), "unexpected racing worker failure");
            ledger.revokeAfterQuiescence();
            assertEquals(0, ledger.outstanding());
            assertEquals(registered.get() ? 1 : 0, revoked.get(),
                    "a granted permit must be reclaimed exactly once");
        }
    }

    @Test
    void ledgerRevocationRunsOutsideMonitorAndRetainsFailedPermit() {
        TaskOwnedLockLedger ledger = new TaskOwnedLockLedger();
        AtomicBoolean outsideMonitor = new AtomicBoolean();
        TaskOwnedLockLedger.Lease successful = () ->
                outsideMonitor.set(!Thread.holdsLock(ledger));
        TaskOwnedLockLedger.Lease failed = () -> {
            throw new IllegalStateException("failed to revoke");
        };
        ledger.register(successful);
        ledger.register(failed);
        ledger.fence();
        assertThrows(CancellationException.class, () -> ledger.register(() -> {}));
        assertThrows(IllegalStateException.class, ledger::revokeAfterQuiescence);
        assertTrue(outsideMonitor.get());
        assertEquals(1, ledger.outstanding(), "failed permit must stay quarantined");
    }
}
