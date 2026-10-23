package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Real actor TurnExecutor / STOP / physical-receipt lifecycle regressions. */
@Timeout(25)
final class ActorLockStopRetirementTest {
    @Test
    void directActorGuardIsRevokedAndDirtySharedPayloadPoisonedBeforeDone()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var mutex = OresMutex.shared(new int[] {1});
            AtomicReference<OresMutex.Guard<int[]>> held = new AtomicReference<>();
            var ref = runtime.<String>spawnShared(() -> (message, context) -> {
                var guard = mutex.tryLock().orElseThrow();
                guard.value()[0] = 5;
                held.set(guard); // deliberately skip lexical release
                context.self().stop();
            });
            ref.send("hold");
            assertTrue(ref.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(ref.getDone().isDone());
            assertNotNull(held.get());
            assertTrue(held.get().released(), "STOP published before actor permit retirement");
            assertTrue(mutex.isPoisoned(), "abandoned actor writer must fail closed");
            assertThrows(IllegalStateException.class, held.get()::value);
            assertThrows(OresMutex.PoisonedMutexException.class, mutex::lock);
            mutex.recover(value -> { value[0] = 0; return null; });
            var guard = mutex.tryLock().orElseThrow();
            assertEquals(0, guard.value()[0]);
            guard.release();
        }
    }

    @Test
    void actorStopCancelsQueuedAsyncAcquisitionBeforeTerminationReceipt()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var mutex = OresMutex.shared(new int[] {1});
            var hostHolder = mutex.tryLock().orElseThrow();
            AtomicReference<java.util.concurrent.CompletableFuture<OresMutex.Guard<int[]>>>
                    queued = new AtomicReference<>();
            var ref = runtime.<String>spawnShared(() -> (message, context) -> {
                queued.set(mutex.lockAsync());
                assertFalse(queued.get().isDone(), "holder still owns the permit");
                context.self().stop();
            });
            try {
                ref.send("queue");
                assertTrue(ref.awaitTermination(5, TimeUnit.SECONDS),
                        "STOP did not retire actor-owned async waiter");
                assertNotNull(queued.get());
                assertTrue(queued.get().isCancelled(),
                        "queued actor-owned waiter survived termination");
                assertTrue(ref.getDone().isDone());
                assertFalse(mutex.isPoisoned(),
                        "an ungranted lock request cannot be a dirty writer");
            } finally {
                hostHolder.release();
            }
            var later = mutex.tryLock().orElseThrow();
            later.release();
        }
    }

    @Test
    void blockedActorExitHookDoesNotHoldLifecycleMonitorOrPublishDone()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch unblock = new CountDownLatch(1);
            AtomicBoolean hookOwnsMonitor = new AtomicBoolean(true);
            AtomicBoolean hookRanAsActor = new AtomicBoolean(true);
            AtomicReference<Object> cellRef = new AtomicReference<>();
            runtime.setActorExitHook(domain -> {
                hookRanAsActor.set(ActorRuntime.inActorExecution());
                try {
                    Object cell = cellRef.get();
                    assertNotNull(cell);
                    Field lock = cell.getClass().getDeclaredField("lifecycleLock");
                    lock.setAccessible(true);
                    hookOwnsMonitor.set(Thread.holdsLock(lock.get(cell)));
                    entered.countDown();
                    if (!unblock.await(5, TimeUnit.SECONDS))
                        throw new IllegalStateException("hook blocker exceeded");
                } catch (ReflectiveOperationException error) {
                    throw new AssertionError(error);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(error);
                }
            });
            var ref = runtime.<String>spawnShared(() -> (message, context) ->
                    context.self().stop());
            Field actors = ActorRuntime.class.getDeclaredField("actors");
            actors.setAccessible(true);
            cellRef.set(((Map<?, ?>) actors.get(runtime)).get(ref.id()));
            try {
                ref.send("stop");
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertFalse(ref.getDone().isDone(),
                        "observer DONE preceded actor exit receipt");
                assertFalse(ref.awaitTermination(20, TimeUnit.MILLISECONDS),
                        "terminal registry removed before cleanup completed");
                assertFalse(hookOwnsMonitor.get(),
                        "trusted exit hook called while holding lifecycleLock");
                assertFalse(hookRanAsActor.get(),
                        "exit hook inherited guest actor authority");
            } finally {
                unblock.countDown();
            }
            assertTrue(ref.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(ref.getDone().isDone());
            assertTrue(ref.failure().isEmpty());
        }
    }

    @Test
    void failedActorOwnedPermitRevocationQuarantinesBeforeExitHook()
            throws Exception {
        ActorRuntime runtime = new ActorRuntime();
        try {
            CountDownLatch attempted = new CountDownLatch(1);
            AtomicBoolean exitHookRan = new AtomicBoolean();
            runtime.setActorExitHook(ignored -> exitHookRan.set(true));
            var ref = runtime.<String>spawnPrivate(() -> (message, context) -> {
                TaskOwnedLockLedger ledger = ActorRuntime.currentActorLockLedger();
                assertNotNull(ledger);
                ledger.register(() -> {
                    attempted.countDown();
                    throw new IllegalStateException("injected native lock revocation failure");
                });
                context.self().stop();
            });
            ref.send("stop");
            assertTrue(attempted.await(5, TimeUnit.SECONDS),
                    "actor lock ledger never attempted cleanup");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (ref.failure().isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(2);
            }
            assertTrue(ref.failure().isPresent(), "failed lock receipt lost");
            assertFalse(exitHookRan.get(),
                    "actor exited and released its arena after failed lock cleanup");
            assertFalse(ref.getDone().isDone(),
                    "DONE cannot be manufactured after failed permit retirement");
            assertFalse(ref.awaitTermination(20, TimeUnit.MILLISECONDS));
        } finally {
            assertThrows(IllegalStateException.class, runtime::close);
        }
    }

    @Test
    void registryRemovalCannotForgeTerminationWhileDoneSubscribersStillDrain()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch doneSubscriberEntered = new CountDownLatch(1);
            CountDownLatch releaseSubscriber = new CountDownLatch(1);
            var ref = runtime.<String>spawnShared(() -> (message, context) ->
                    context.self().stop());
            ref.getDone().whenCompleteRuntime((value, error) -> {
                doneSubscriberEntered.countDown();
                try {
                    if (!releaseSubscriber.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("terminal callback barrier timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            });
            try {
                ref.send("stop");
                assertTrue(doneSubscriberEntered.await(5, TimeUnit.SECONDS));
                assertTrue(ref.getDone().isDone(),
                        "test did not reach terminal CAS");
                // By this point the finalizer has already unregistered the
                // actor, but its terminal callback is still physically live.
                assertFalse(ref.awaitTermination(20, TimeUnit.MILLISECONDS),
                        "missing actor map entry forged termination receipt");
            } finally {
                releaseSubscriber.countDown();
            }
            assertTrue(ref.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(ref.failure().isEmpty());
        }
    }

    @Test
    void exitHookFailureQuarantinesActorAndWithholdsSuccessfulDone() throws Exception {
        ActorRuntime runtime = new ActorRuntime();
        try {
            CountDownLatch entered = new CountDownLatch(1);
            runtime.setActorExitHook(domain -> {
                entered.countDown();
                throw new IllegalStateException("injected lock/arena cleanup failure");
            });
            var ref = runtime.<String>spawnShared(() -> (message, context) ->
                    context.self().stop());
            ref.send("stop");
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (ref.failure().isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(2);
            }
            assertTrue(ref.failure().isPresent(), "cleanup failure lost");
            assertFalse(ref.getDone().isDone(),
                    "unsafe actor teardown forged a DONE receipt");
            assertFalse(ref.awaitTermination(20, TimeUnit.MILLISECONDS),
                    "failed exit hook released quarantined actor");
        } finally {
            // Quarantined actors intentionally prevent clean runtime shutdown.
            // A failed cleanup receipt is not a successful join.
            assertThrows(IllegalStateException.class, runtime::close);
        }
    }
}
