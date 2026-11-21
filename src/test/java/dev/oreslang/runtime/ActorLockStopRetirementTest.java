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
    void actorStopWaitsForSourceResultSubscribersAfterTaskDone() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch admitted = new CountDownLatch(1);
            CountDownLatch releaseGuest = new CountDownLatch(1);
            CountDownLatch resultCallbackEntered = new CountDownLatch(1);
            CountDownLatch releaseResultCallback = new CountDownLatch(1);
            AtomicReference<OresFuture<Integer>> source = new AtomicReference<>();
            var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                OresFuture<Integer> task = context.runtime().startActorTask(resume -> {
                    try {
                        if (!releaseGuest.await(5, TimeUnit.SECONDS))
                            throw new IllegalStateException("guest gate timed out");
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(error);
                    }
                    return OresScheduler.done(31);
                });
                source.set(task);
                admitted.countDown();
            });
            actor.send("start");
            assertTrue(admitted.await(5, TimeUnit.SECONDS));
            assertNotNull(source.get());
            source.get().whenCompleteRuntime((value, failure) -> {
                resultCallbackEntered.countDown();
                try {
                    if (!releaseResultCallback.await(5, TimeUnit.SECONDS))
                        throw new IllegalStateException("result subscriber timeout");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(error);
                }
            });
            try {
                releaseGuest.countDown();
                assertTrue(resultCallbackEntered.await(5, TimeUnit.SECONDS));
                assertTrue(source.get().isDone());
                actor.stop();
                assertFalse(actor.getDone().isDone(),
                        "actor DONE raced ahead of the source-result callback");
                assertFalse(actor.awaitTermination(20, TimeUnit.MILLISECONDS),
                        "actor registry/arena retired while result callback was live");
            } finally {
                releaseGuest.countDown();
                releaseResultCallback.countDown();
            }
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void actorStopJoinsTrackedSourceTaskDoneSubscriberDrain() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            OresFuture<Integer> suspended = new OresFuture<>();
            CountDownLatch taskEntered = new CountDownLatch(1);
            CountDownLatch doneCallbackEntered = new CountDownLatch(1);
            CountDownLatch releaseDoneCallback = new CountDownLatch(1);
            var ref = runtime.<String>spawnShared(() -> (message, context) -> {
                context.runtime().startActorTask(resume -> {
                    taskEntered.countDown();
                    return OresScheduler.await(suspended);
                });
            });
            ref.send("start");
            assertTrue(taskEntered.await(5, TimeUnit.SECONDS));
            Field actorsField = ActorRuntime.class.getDeclaredField("actors");
            actorsField.setAccessible(true);
            Object cell = ((Map<?, ?>) actorsField.get(runtime)).get(ref.id());
            assertNotNull(cell);
            Field trackedField = cell.getClass().getDeclaredField("sourceTaskHandles");
            trackedField.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.Set<TaskHandle<?>> tracked =
                    (java.util.Set<TaskHandle<?>>) trackedField.get(cell);
            assertEquals(1, tracked.size(), "source Task not retained by actor");
            TaskHandle<?> sourceTask = tracked.iterator().next();
            sourceTask.getDone().whenCompleteRuntime((outcome, failure) -> {
                doneCallbackEntered.countDown();
                try {
                    if (!releaseDoneCallback.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Task DONE callback timeout");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            });
            try {
                ref.stop();
                assertTrue(doneCallbackEntered.await(5, TimeUnit.SECONDS));
                assertTrue(sourceTask.getDone().isDone(),
                        "Task never reached its terminal CAS");
                assertFalse(sourceTask.doneCallbacksDrained()
                                .toCompletableFuture().isDone(),
                        "physical Task callback drain claimed before subscriber returned");
                assertTrue(tracked.contains(sourceTask),
                        "actor forgot its Task before terminal subscribers drained");
                assertFalse(ref.getDone().isDone(),
                        "actor DONE overtook Task's blocked terminal callback");
                assertFalse(ref.awaitTermination(20, TimeUnit.MILLISECONDS),
                        "actor terminal latch overtook Task callback");
            } finally {
                releaseDoneCallback.countDown();
            }
            assertTrue(ref.awaitTermination(5, TimeUnit.SECONDS));
            assertFalse(tracked.contains(sourceTask));
        }
    }

    @Test
    void actorStopWaitsForSuspendedSourceTaskGuardRetirement() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var mutex = OresMutex.shared(new int[] {11});
            OresFuture<Integer> neverCompletes = new OresFuture<>();
            CountDownLatch taskSuspended = new CountDownLatch(1);
            AtomicReference<OresMutex.Guard<int[]>> held = new AtomicReference<>();
            var ref = runtime.<String>spawnShared(() -> (message, context) -> {
                context.runtime().startActorTask(resume -> {
                    if (!resume.initial()) {
                        throw new IllegalStateException(
                                "suspended task resumed after STOP");
                    }
                    var guard = mutex.tryLock().orElseThrow();
                    guard.value()[0] = 31;
                    held.set(guard);
                    taskSuspended.countDown();
                    return OresScheduler.await(neverCompletes);
                });
            });
            ref.send("start");
            assertTrue(taskSuspended.await(5, TimeUnit.SECONDS));
            assertNotNull(held.get());
            assertFalse(held.get().released());
            assertTrue(mutex.tryLock().isEmpty(), "suspended Task lost its permit");
            ref.stop();
            assertTrue(ref.awaitTermination(5, TimeUnit.SECONDS),
                    "actor STOP missed suspended Task lock receipt");
            assertTrue(held.get().released(), "retirement left Task guard active");
            assertTrue(mutex.isPoisoned(),
                    "suspended writer must be poisoned on forced unwind");
            assertTrue(ref.getDone().isDone());
        }
    }

    @Test
    void sourceTaskLeakedGuardRetiresBeforeActorLifetimeEnds() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var mutex = OresMutex.shared(new int[] {3});
            AtomicReference<OresMutex.Guard<int[]>> owned = new AtomicReference<>();
            CountDownLatch submitted = new CountDownLatch(1);
            var ref = runtime.<String>spawnShared(() -> (message, context) -> {
                if (message.equals("start")) {
                    context.runtime().startActorTask(resume -> {
                        var guard = mutex.tryLock().orElseThrow();
                        guard.value()[0] = 15;
                        owned.set(guard); // missing explicit defer/release
                        return OresScheduler.done(null);
                    });
                    submitted.countDown();
                } else if (message.equals("stop")) {
                    context.self().stop();
                }
            });
            ref.send("start");
            assertTrue(submitted.await(5, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while ((owned.get() == null || !owned.get().released())
                    && System.nanoTime() < deadline) {
                Thread.sleep(2);
            }
            assertNotNull(owned.get(), "compiler-started Task never acquired its guard");
            assertTrue(owned.get().released(),
                    "Task leaked mutex until actor STOP rather than Task DONE");
            assertTrue(mutex.isPoisoned(),
                    "Task abandoned a mutable shared critical section");
            assertTrue(ref.isAlive(),
                    "Task lifetime must not implicitly terminate its actor");
            ref.send("stop");
            assertTrue(ref.awaitTermination(5, TimeUnit.SECONDS));
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
    void channelCloseCallbacksRunOutsideActorLifecycleMonitor() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<String>spawnShared(() -> (message, context) -> {});
            Field actorsField = ActorRuntime.class.getDeclaredField("actors");
            actorsField.setAccessible(true);
            Object cell = ((Map<?, ?>) actorsField.get(runtime)).get(ref.id());
            assertNotNull(cell);
            Field lifecycleField = cell.getClass().getDeclaredField("lifecycleLock");
            lifecycleField.setAccessible(true);
            Object lifecycle = lifecycleField.get(cell);
            Field mailboxField = cell.getClass().getDeclaredField("mailbox");
            mailboxField.setAccessible(true);
            @SuppressWarnings("unchecked")
            ChannelRuntime.Channel<Object> queue =
                    (ChannelRuntime.Channel<Object>) mailboxField.get(cell);
            var pending = queue.readAsync();
            CountDownLatch callbackEntered = new CountDownLatch(1);
            CountDownLatch releaseCallback = new CountDownLatch(1);
            AtomicBoolean ranInsideMonitor = new AtomicBoolean(true);
            pending.whenCompleteRuntime((ignored, error) -> {
                ranInsideMonitor.set(Thread.holdsLock(lifecycle));
                callbackEntered.countDown();
                try {
                    if (!releaseCallback.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("close callback timeout");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            });
            Thread stopper = Thread.ofPlatform().start(ref::stop);
            try {
                assertTrue(callbackEntered.await(5, TimeUnit.SECONDS));
                assertFalse(ranInsideMonitor.get(),
                        "ChannelRuntime.close notified Futures while lifecycleLock held");
                assertFalse(ref.getDone().isDone(),
                        "actor DONE while STOP channel close callbacks were live");
            } finally {
                releaseCallback.countDown();
                stopper.join(5000);
            }
            assertFalse(stopper.isAlive());
            assertTrue(ref.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void failedMailboxEnvelopeReleaseQuarantinesActorAndGlobalRuntime()
            throws Exception {
        ActorRuntime runtime = new ActorRuntime();
        try {
            var ref = runtime.<String>spawnShared(() -> (message, context) -> {});
            Field actorsField = ActorRuntime.class.getDeclaredField("actors");
            actorsField.setAccessible(true);
            Object cell = ((Map<?, ?>) actorsField.get(runtime)).get(ref.id());
            assertNotNull(cell);
            var reserve = cell.getClass().getDeclaredMethod("reserveMailboxSlot");
            reserve.setAccessible(true);
            assertEquals(Boolean.TRUE, reserve.invoke(cell));
            Class<?> envelopeClass = Class.forName(
                    "dev.oreslang.runtime.ActorRuntime$MessageEnvelope");
            var constructor = envelopeClass.getDeclaredMethod(
                    "message", Object.class, Runnable.class,
                    ActorRuntime.ActorId.class, long.class);
            constructor.setAccessible(true);
            CountDownLatch callbackRan = new CountDownLatch(1);
            Object envelope = constructor.invoke(null, "queued",
                    (Runnable) () -> {
                        callbackRan.countDown();
                        throw new IllegalStateException(
                                "injected native mailbox loan release failure");
                    }, null, 0L);
            Field mailboxField = cell.getClass().getDeclaredField("mailbox");
            mailboxField.setAccessible(true);
            @SuppressWarnings("unchecked")
            ChannelRuntime.Channel<Object> queue =
                    (ChannelRuntime.Channel<Object>) mailboxField.get(cell);
            assertTrue(queue.tryWrite(envelope));
            ref.stop();
            assertTrue(callbackRan.await(5, TimeUnit.SECONDS));
            assertTrue(ref.failure().isPresent());
            assertFalse(ref.getDone().isDone(),
                    "failed user envelope revocation forged actor DONE");
            assertFalse(ref.awaitTermination(20, TimeUnit.MILLISECONDS),
                    "failed mailbox cleanup released quarantined actor");
        } finally {
            assertThrows(IllegalStateException.class, runtime::close);
        }
    }

    @Test
    void messageReleaseCallbacksRunOutsideMonitorAndGateActorDone()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<String>spawnShared(() -> (message, context) -> {});
            Field actorsField = ActorRuntime.class.getDeclaredField("actors");
            actorsField.setAccessible(true);
            Object cell = ((Map<?, ?>) actorsField.get(runtime)).get(ref.id());
            assertNotNull(cell);
            Field lifecycleField = cell.getClass().getDeclaredField("lifecycleLock");
            lifecycleField.setAccessible(true);
            Object lifecycle = lifecycleField.get(cell);
            var reserve = cell.getClass().getDeclaredMethod("reserveMailboxSlot");
            reserve.setAccessible(true);
            assertEquals(Boolean.TRUE, reserve.invoke(cell));
            Class<?> envelopeClass = Class.forName(
                    "dev.oreslang.runtime.ActorRuntime$MessageEnvelope");
            var constructor = envelopeClass.getDeclaredMethod(
                    "message", Object.class, Runnable.class,
                    ActorRuntime.ActorId.class, long.class);
            constructor.setAccessible(true);
            CountDownLatch callbackEntered = new CountDownLatch(1);
            CountDownLatch releaseCallback = new CountDownLatch(1);
            AtomicBoolean ranInsideMonitor = new AtomicBoolean(true);
            Object envelope = constructor.invoke(null, "pending", (Runnable) () -> {
                ranInsideMonitor.set(Thread.holdsLock(lifecycle));
                callbackEntered.countDown();
                try {
                    if (!releaseCallback.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("mailbox release timeout");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            }, null, 0L);
            Field mailboxField = cell.getClass().getDeclaredField("mailbox");
            mailboxField.setAccessible(true);
            @SuppressWarnings("unchecked")
            ChannelRuntime.Channel<Object> queue =
                    (ChannelRuntime.Channel<Object>) mailboxField.get(cell);
            assertTrue(queue.tryWrite(envelope));
            Thread stopper = Thread.ofPlatform().start(ref::stop);
            try {
                assertTrue(callbackEntered.await(5, TimeUnit.SECONDS));
                assertFalse(ranInsideMonitor.get(),
                        "message release callback ran under actor lifecycleLock");
                assertFalse(ref.getDone().isDone(),
                        "actor DONE published while envelope release was incomplete");
            } finally {
                releaseCallback.countDown();
                stopper.join(5000);
            }
            assertFalse(stopper.isAlive());
            assertTrue(ref.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void parentStopWaitsUntilChildDoneSubscribersDrain() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch childDoneObserverEntered = new CountDownLatch(1);
            CountDownLatch releaseChildObserver = new CountDownLatch(1);
            AtomicReference<ActorRuntime.ActorRef<String>> child = new AtomicReference<>();
            var parent = runtime.<String>spawnShared(() -> (message, context) -> {
                var handle = runtime.<String>spawnChildShared(
                        factoryContext -> (childMessage, childContext) ->
                                childContext.self().stop());
                child.set(handle.ref());
                handle.ref().getDone().whenCompleteRuntime((ignored, failure) -> {
                    childDoneObserverEntered.countDown();
                    try {
                        if (!releaseChildObserver.await(5, TimeUnit.SECONDS))
                            throw new IllegalStateException("child done observer timeout");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    }
                });
                handle.send("exit");
                context.self().stop();
            });
            try {
                parent.send("spawn");
                assertTrue(childDoneObserverEntered.await(5, TimeUnit.SECONDS));
                assertNotNull(child.get());
                assertFalse(parent.getDone().isDone(),
                        "parent reclaimed while child DONE callback was active");
                assertFalse(parent.awaitTermination(20, TimeUnit.MILLISECONDS),
                        "parent child-retirement barrier bypassed observer drain");
            } finally {
                releaseChildObserver.countDown();
            }
            assertTrue(child.get().awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(parent.awaitTermination(5, TimeUnit.SECONDS));
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
                // The public Future terminal CAS is visible, but its
                // subscribers have not physically returned. Keep the actor
                // registry and quota reservation until they do; a successor
                // must not reuse a slot while a terminal callback is live.
                Field actorsField = ActorRuntime.class.getDeclaredField("actors");
                actorsField.setAccessible(true);
                assertTrue(((Map<?, ?>) actorsField.get(runtime)).containsKey(ref.id()),
                        "actor registry removed while DONE subscribers still running");
                Field countField = ActorRuntime.class.getDeclaredField("actorCount");
                countField.setAccessible(true);
                assertTrue(((java.util.concurrent.atomic.AtomicInteger)
                        countField.get(runtime)).get() >= 1,
                        "old incarnation actor quota was recycled too early");
                assertFalse(ref.awaitTermination(20, TimeUnit.MILLISECONDS),
                        "callback still live but physical termination reported");
            } finally {
                releaseSubscriber.countDown();
            }
            assertTrue(ref.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(ref.failure().isEmpty());
        }
    }

    @Test
    void failedHostRetirementStageQuarantinesStoppedActor()
            throws Exception {
        ActorRuntime runtime = new ActorRuntime();
        CountDownLatch stopRequested = new CountDownLatch(1);
        CountDownLatch releaseCarrier = new CountDownLatch(1);
        try {
            var ref = runtime.<String>spawnShared(() -> (message, context) -> {
                context.self().stop();
                stopRequested.countDown();
                try {
                    if (!releaseCarrier.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("host fault injection timed out");
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(error);
                }
            });
            ref.send("stop");
            assertTrue(stopRequested.await(5, TimeUnit.SECONDS));

            // Simulate an exception in a host finalization continuation while
            // STOP is fenced but its physical carrier is still unwinding.
            // The wrapper must retain a permanent quarantine root, not let
            // its virtual-thread exception silently disappear.
            Field actors = ActorRuntime.class.getDeclaredField("actors");
            actors.setAccessible(true);
            Object cell = ((Map<?, ?>) actors.get(runtime)).get(ref.id());
            assertNotNull(cell);
            var stage = cell.getClass().getDeclaredMethod(
                    "scheduleHostRetirement", String.class, Runnable.class);
            stage.setAccessible(true);
            stage.invoke(cell, "ores-test-retirement-fault-", (Runnable) () -> {
                throw new IllegalStateException("injected host stage failed");
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (ref.failure().isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(2);
            }
            assertTrue(ref.failure().isPresent(),
                    "uncaught host stage failure disappeared");
        } finally {
            releaseCarrier.countDown();
            assertThrows(IllegalStateException.class, runtime::close);
        }
    }

    @Test
    void asynchronousPhysicalLockReceiptFailureQuarantinesSupervisorRestart()
            throws Exception {
        ActorRuntime runtime = new ActorRuntime();
        try {
            java.util.concurrent.CompletableFuture<Void> physicalReceipt =
                    new java.util.concurrent.CompletableFuture<>();
            CountDownLatch retirementStarted = new CountDownLatch(1);
            var ref = runtime.<String>spawnShared(() -> (message, context) -> {
                TaskOwnedLockLedger ledger = ActorRuntime.currentActorLockLedger();
                assertNotNull(ledger);
                ledger.register(new TaskOwnedLockLedger.Lease() {
                    @Override public void revokeAfterQuiescence() {
                        throw new AssertionError("only asynchronous receipt may revoke");
                    }
                    @Override public java.util.concurrent.CompletionStage<Void>
                            settleAfterQuiescence() {
                        retirementStarted.countDown();
                        return physicalReceipt;
                    }
                });
                context.self().stop();
            });
            ref.send("stop");
            assertTrue(retirementStarted.await(5, TimeUnit.SECONDS));
            assertFalse(ref.getDone().isDone(),
                    "STOP certified cleanup before the physical receipt");
            assertFalse(ref.awaitTermination(20, TimeUnit.MILLISECONDS));
            physicalReceipt.completeExceptionally(
                    new IllegalStateException("injected native handoff failure"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (ref.failure().isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(2);
            }
            assertTrue(ref.failure().isPresent(),
                    "failed asynchronous retirement receipt was swallowed");
            assertFalse(ref.getDone().isDone(),
                    "failed physical receipt forged successful actor DONE");
            assertFalse(ref.awaitTermination(20, TimeUnit.MILLISECONDS),
                    "unsafe retirement made actor eligible for reuse");
        } finally {
            assertThrows(IllegalStateException.class, runtime::close,
                    "quarantined actor must retain its arena and capability roots");
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
