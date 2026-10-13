package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Mailbox admission, processed barriers, and graceful close are distinct milestones. */
@Timeout(20)
final class ActorSendContractTest {
    @Test
    void actorRefIsOpaqueGenerationBoundAndSendAfterStopIsTyped() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            AtomicInteger count = new AtomicInteger();
            var ref = runtime.<Integer>spawnShared(() -> (message, context) ->
                    count.addAndGet(message));
            assertTrue(ref.generation() > 0);
            assertEquals(ref.id(), ref.address().actorId());
            assertEquals(ref.generation(), ref.address().generation());
            assertEquals("local-process", ref.address().transport());
            ref.send(1);
            ref.send(2);
            ref.drain().get(5, TimeUnit.SECONDS);
            assertEquals(3, count.get());
            ref.stop();
            ref.getDone().get(5, TimeUnit.SECONDS);
            ActorRuntime.ActorTerminatedException failure = assertThrows(
                    ActorRuntime.ActorTerminatedException.class, () -> ref.send(4));
            assertEquals(ActorRuntime.SendError.Reason.TERMINATED, failure.reason());
        }
    }

    @Test
    void gracefulStopSealsAdmissionsButWaitsForExistingMail() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ActorRuntime runtime = new ActorRuntime()) {
            AtomicInteger processed = new AtomicInteger();
            var ref = runtime.<Integer>spawnShared(() -> (message, context) -> {
                entered.countDown();
                if (!release.await(5, TimeUnit.SECONDS))
                    throw new AssertionError("test actor timed out");
                processed.incrementAndGet();
            });
            ref.send(42);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            OresFuture<Void> barrier = ref.drain();
            assertFalse(barrier.isDone());
            OresFuture<Void> done = ref.gracefulStop();
            ActorRuntime.ActorSealedException rejected = assertThrows(
                    ActorRuntime.ActorSealedException.class, () -> ref.send(43));
            assertEquals(ActorRuntime.SendError.Reason.SEALED, rejected.reason());
            assertFalse(done.isDone());
            release.countDown();
            barrier.get(5, TimeUnit.SECONDS);
            done.get(5, TimeUnit.SECONDS);
            assertEquals(1, processed.get());
            assertFalse(ref.isAlive());
        } finally {
            release.countDown();
        }
    }

    @Test
    void abruptStopFailsOutstandingDrainRatherThanFabricatingProcessing() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<Integer>spawnShared(() -> (message, context) -> {
                entered.countDown();
                release.await(5, TimeUnit.SECONDS);
            });
            ref.send(1);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            ref.send(2);
            OresFuture<Void> barrier = ref.drain();
            // stop cannot wait synchronously while the test deliberately holds
            // the guest turn. Unblock the carrier from another host thread.
            Thread releaser = new Thread(() -> {
                try {
                    Thread.sleep(30);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                release.countDown();
            });
            releaser.start();
            ref.stop();
            releaser.join();
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> barrier.get(5, TimeUnit.SECONDS));
            assertTrue(ref.awaitTermination(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
    }

    @Test
    void pendingDrainsHavePerActorQuotaAndGracefulStopKeepsReservedSlot() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<Integer>spawnShared(() -> (message, context) -> {
                entered.countDown();
                if (!release.await(5, TimeUnit.SECONDS))
                    throw new AssertionError("test actor timed out");
            });
            ref.send(1);
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            List<OresFuture<Void>> observers = new ArrayList<>();
            for (int i = 0; i < 1_024; i++) {
                OresFuture<Void> drain = ref.drain();
                assertFalse(drain.isDone(), "actor has not processed its held message");
                observers.add(drain);
            }
            ExecutionException exhausted = assertThrows(ExecutionException.class,
                    () -> ref.drain().get(5, TimeUnit.SECONDS));
            assertInstanceOf(ActorRuntime.ActorResourceExhaustedException.class,
                    exhausted.getCause());
            assertEquals(ActorRuntime.SendError.Reason.RESOURCE_EXHAUSTED,
                    ((ActorRuntime.ActorResourceExhaustedException) exhausted.getCause()).reason());

            // Exhausted public observers must not strand the actor in SEALED.
            OresFuture<Void> done = ref.gracefulStop();
            assertSame(done, ref.gracefulStop(), "repeated gracefulStop reuses termination");
            assertThrows(ActorRuntime.ActorSealedException.class, () -> ref.send(2));
            release.countDown();
            for (OresFuture<Void> observer : observers)
                observer.get(5, TimeUnit.SECONDS);
            done.get(5, TimeUnit.SECONDS);
            assertFalse(ref.isAlive());
        } finally {
            release.countDown();
        }
    }

    @Test
    void crossRuntimeRefDoesNotRouteAsSharedAddress() {
        try (ActorRuntime left = new ActorRuntime();
             ActorRuntime right = new ActorRuntime()) {
            var ref = left.<Integer>spawnPrivate(() -> (message, context) -> { });
            ActorRuntime.ActorInvalidReferenceException failure = assertThrows(
                    ActorRuntime.ActorInvalidReferenceException.class,
                    () -> right.send(ref, 7));
            assertEquals(ActorRuntime.SendError.Reason.INVALID_REFERENCE, failure.reason());
            assertNotEquals(ref.address().runtimeId(), 
                    right.<Integer>spawnPrivate(() -> (message, context) -> { })
                            .address().runtimeId());
        }
    }
    @Test
    void failedDrainObserverCallbacksRunOutsideActorLifecycleMonitor() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<Integer>spawnShared(() -> (message, context) -> {
                entered.countDown();
                release.await(5, TimeUnit.SECONDS);
            });
            ref.send(1);
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            // Test the runtime's lock-order contract directly. A failed
            // OresFuture invokes its registered runtime callbacks synchronously.
            var actorsField = ActorRuntime.class.getDeclaredField("actors");
            actorsField.setAccessible(true);
            var actors = (java.util.Map<?, ?>) actorsField.get(runtime);
            Object cell = actors.get(ref.id());
            assertNotNull(cell);
            var monitorField = cell.getClass().getDeclaredField("lifecycleLock");
            monitorField.setAccessible(true);
            Object lifecycleMonitor = monitorField.get(cell);

            AtomicInteger callbacksUnderLock = new AtomicInteger();
            CountDownLatch callbackObserved = new CountDownLatch(1);
            OresFuture<Void> pending = ref.drain();
            assertFalse(pending.isDone());
            pending.whenCompleteRuntime((ignored, failure) -> {
                if (Thread.holdsLock(lifecycleMonitor)) callbacksUnderLock.incrementAndGet();
                callbackObserved.countDown();
            });

            Thread stopper = new Thread(ref::stop, "actor-drain-monitor-regression");
            stopper.start();
            try {
                assertTrue(callbackObserved.await(5, TimeUnit.SECONDS),
                        "stopping an actor must fail pending drains");
                assertEquals(0, callbacksUnderLock.get(),
                        "runtime callbacks must never run inside lifecycleLock");
            } finally {
                release.countDown();
                stopper.join(5_000);
            }
            assertFalse(stopper.isAlive());
        } finally {
            release.countDown();
        }
    }

    @Test
    void exhaustedActorGenerationDoesNotLeakReservedCapacity() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var generationField = ActorRuntime.class.getDeclaredField("nextActorGeneration");
            var actorCountField = ActorRuntime.class.getDeclaredField("actorCount");
            generationField.setAccessible(true);
            actorCountField.setAccessible(true);
            var generations = (java.util.concurrent.atomic.AtomicLong)
                    generationField.get(runtime);
            var liveCount = (java.util.concurrent.atomic.AtomicInteger)
                    actorCountField.get(runtime);
            int baseline = liveCount.get();
            long originalGeneration = generations.get();
            try {
                // Force the fail-closed incarnation exhaustion branch without
                // waiting for a naturally impossible 2^63 actor allocations.
                generations.set(0L);
                IllegalStateException exhausted = assertThrows(
                        IllegalStateException.class,
                        () -> runtime.<Integer>spawnShared(
                                () -> (message, context) -> { }));
                assertTrue(exhausted.getMessage().contains("generation sequence exhausted"));
                assertEquals(baseline, liveCount.get(),
                        "failed actor construction must release its reserved slot");
            } finally {
                generations.set(originalGeneration);
            }
            var recovered = runtime.<Integer>spawnShared(
                    () -> (message, context) -> { });
            recovered.getReady().get(5, TimeUnit.SECONDS);
            recovered.stop();
            recovered.getDone().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void terminationFuturesPublishOutsideLifecycleMonitorExactlyOnce() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<Integer>spawnShared(() -> (message, context) -> {
                entered.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("held actor timed out");
                }
            });
            ref.send(1);
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            var actorsField = ActorRuntime.class.getDeclaredField("actors");
            actorsField.setAccessible(true);
            Object cell = ((java.util.Map<?, ?>) actorsField.get(runtime)).get(ref.id());
            assertNotNull(cell);
            var lockField = cell.getClass().getDeclaredField("lifecycleLock");
            lockField.setAccessible(true);
            Object actorMonitor = lockField.get(cell);

            AtomicInteger callbackCount = new AtomicInteger();
            AtomicInteger callbackWhileLocked = new AtomicInteger();
            CountDownLatch observed = new CountDownLatch(1);
            ref.getDone().whenCompleteRuntime((value, error) -> {
                callbackCount.incrementAndGet();
                if (Thread.holdsLock(actorMonitor)) callbackWhileLocked.incrementAndGet();
                observed.countDown();
            });

            Thread first = new Thread(ref::stop, "actor-finalize-first-stop");
            Thread second = new Thread(ref::stop, "actor-finalize-racing-stop");
            first.start();
            second.start();
            try {
                assertFalse(observed.await(75, TimeUnit.MILLISECONDS),
                        "a running actor may not publish DONE before carrier exit");
            } finally {
                release.countDown();
                first.join(5_000);
                second.join(5_000);
            }
            assertFalse(first.isAlive());
            assertFalse(second.isAlive());
            assertTrue(observed.await(5, TimeUnit.SECONDS));
            assertEquals(0, callbackWhileLocked.get(),
                    "DONE must be delivered after releasing actor lifecycle monitor");
            assertEquals(1, callbackCount.get(), "DONE must settle exactly once");
            ref.getDone().get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
    }

    @Test
    void initializationFailureReadyCallbacksNeverHoldActorMonitor() throws Exception {
        CountDownLatch enteredFactory = new CountDownLatch(1);
        CountDownLatch releaseFactory = new CountDownLatch(1);
        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<Integer>spawnShared(() -> {
                enteredFactory.countDown();
                try {
                    if (!releaseFactory.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("factory timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("factory interrupted", interrupted);
                }
                throw new IllegalStateException("expected initialization rejection");
            });

            var actorsField = ActorRuntime.class.getDeclaredField("actors");
            actorsField.setAccessible(true);
            Object cell = ((java.util.Map<?, ?>) actorsField.get(runtime)).get(ref.id());
            assertNotNull(cell);
            var lockField = cell.getClass().getDeclaredField("lifecycleLock");
            lockField.setAccessible(true);
            Object actorMonitor = lockField.get(cell);

            AtomicInteger callbackWhileLocked = new AtomicInteger();
            AtomicInteger readyCount = new AtomicInteger();
            CountDownLatch readyObserved = new CountDownLatch(1);
            ref.getReady().whenCompleteRuntime((value, error) -> {
                readyCount.incrementAndGet();
                if (Thread.holdsLock(actorMonitor)) callbackWhileLocked.incrementAndGet();
                readyObserved.countDown();
            });
            try {
                assertTrue(enteredFactory.await(5, TimeUnit.SECONDS));
                assertFalse(readyObserved.await(30, TimeUnit.MILLISECONDS));
            } finally {
                releaseFactory.countDown();
            }
            assertTrue(readyObserved.await(5, TimeUnit.SECONDS));
            assertEquals(0, callbackWhileLocked.get());
            assertEquals(1, readyCount.get());
            assertThrows(ExecutionException.class, () -> ref.getReady().get(5, TimeUnit.SECONDS));
            assertThrows(ExecutionException.class, () -> ref.getDone().get(5, TimeUnit.SECONDS));
        } finally {
            releaseFactory.countDown();
        }
    }

    @Test
    void parentChildStopPublishesReceiptsWithoutNestedActorMonitors() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            java.util.concurrent.atomic.AtomicReference<ActorRuntime.ActorRef<Integer>> childRef =
                    new java.util.concurrent.atomic.AtomicReference<>();
            CountDownLatch childCreated = new CountDownLatch(1);
            var parent = runtime.<Integer>spawnShared(() -> (message, context) -> {
                var child = context.runtime().<Integer>spawnPrivate(
                        factoryContext -> (childMessage, childContext) -> { });
                childRef.set(child);
                childCreated.countDown();
            });
            parent.send(1);
            assertTrue(childCreated.await(5, TimeUnit.SECONDS),
                    () -> "child spawn never completed; parent failure: "
                            + parent.failure() + ", parent done: " + parent.getDone().isDone());
            var child = childRef.get();
            assertNotNull(child);

            var actorsField = ActorRuntime.class.getDeclaredField("actors");
            actorsField.setAccessible(true);
            var actors = (java.util.Map<?, ?>) actorsField.get(runtime);
            Object parentCell = actors.get(parent.id());
            Object childCell = actors.get(child.id());
            assertNotNull(parentCell);
            assertNotNull(childCell);
            var monitorField = parentCell.getClass().getDeclaredField("lifecycleLock");
            monitorField.setAccessible(true);
            Object parentMonitor = monitorField.get(parentCell);
            Object childMonitor = monitorField.get(childCell);

            AtomicInteger callbacksUnderMonitor = new AtomicInteger();
            AtomicInteger parentReceipts = new AtomicInteger();
            AtomicInteger childReceipts = new AtomicInteger();
            CountDownLatch settled = new CountDownLatch(2);
            parent.getDone().whenCompleteRuntime((value, error) -> {
                if (Thread.holdsLock(parentMonitor) || Thread.holdsLock(childMonitor)) {
                    callbacksUnderMonitor.incrementAndGet();
                }
                parentReceipts.incrementAndGet();
                settled.countDown();
            });
            child.getDone().whenCompleteRuntime((value, error) -> {
                if (Thread.holdsLock(parentMonitor) || Thread.holdsLock(childMonitor)) {
                    callbacksUnderMonitor.incrementAndGet();
                }
                childReceipts.incrementAndGet();
                settled.countDown();
            });

            Thread first = new Thread(parent::stop, "parent-stop-one");
            Thread second = new Thread(parent::stop, "parent-stop-two");
            first.start();
            second.start();
            first.join(5_000);
            second.join(5_000);
            assertFalse(first.isAlive());
            assertFalse(second.isAlive());
            assertTrue(settled.await(5, TimeUnit.SECONDS),
                    "child and parent finalization receipts must both settle");
            assertEquals(0, callbacksUnderMonitor.get(),
                    "child/parent DONE callbacks must never hold either lifecycle monitor");
            assertEquals(1, parentReceipts.get());
            assertEquals(1, childReceipts.get());
            assertTrue(parent.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(child.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void mailboxCloseDeliversPendingSelectOutsideActorLifecycleMonitor() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<Integer>spawnShared(() -> (message, context) -> { });
            actor.getReady().get(5, TimeUnit.SECONDS);
            var actorsField = ActorRuntime.class.getDeclaredField("actors");
            actorsField.setAccessible(true);
            Object cell = ((java.util.Map<?, ?>) actorsField.get(runtime)).get(actor.id());
            assertNotNull(cell);
            var monitorField = cell.getClass().getDeclaredField("lifecycleLock");
            monitorField.setAccessible(true);
            Object monitor = monitorField.get(cell);
            var continuationField = cell.getClass().getDeclaredField("continuationMailbox");
            continuationField.setAccessible(true);
            @SuppressWarnings("unchecked")
            ChannelRuntime.Channel<Object> controlMailbox =
                    (ChannelRuntime.Channel<Object>) continuationField.get(cell);
            OresFuture<Object> pendingRead = controlMailbox.readAsync();
            assertFalse(pendingRead.isDone(), "the CONTROL lane must initially be empty");

            AtomicInteger callbackUnderLock = new AtomicInteger();
            AtomicInteger callbackCount = new AtomicInteger();
            CountDownLatch observed = new CountDownLatch(1);
            pendingRead.whenCompleteRuntime((value, error) -> {
                if (Thread.holdsLock(monitor)) callbackUnderLock.incrementAndGet();
                callbackCount.incrementAndGet();
                observed.countDown();
            });
            actor.stop();
            assertTrue(observed.await(5, TimeUnit.SECONDS),
                    "closing the CONTROL channel must settle pending selectors");
            assertEquals(0, callbackUnderLock.get(),
                    "channel-close completions cannot be published under lifecycleLock");
            assertEquals(1, callbackCount.get(), "channel-close selector must settle once");
            actor.getDone().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void failedMailboxResourceReleaseDoesNotStrandLaterReservations() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<Integer>spawnShared(() -> (message, context) -> { });
            actor.getReady().get(5, TimeUnit.SECONDS);

            var actorsField = ActorRuntime.class.getDeclaredField("actors");
            actorsField.setAccessible(true);
            Object cell = ((java.util.Map<?, ?>) actorsField.get(runtime)).get(actor.id());
            assertNotNull(cell);

            var mailboxField = cell.getClass().getDeclaredField("mailbox");
            mailboxField.setAccessible(true);
            @SuppressWarnings("unchecked")
            ChannelRuntime.Channel<Object> mailbox =
                    (ChannelRuntime.Channel<Object>) mailboxField.get(cell);
            var reserveMethod = cell.getClass().getDeclaredMethod("reserveMailboxSlot");
            reserveMethod.setAccessible(true);
            Class<?> envelopeClass =
                    Class.forName("dev.oreslang.runtime.ActorRuntime$MessageEnvelope");
            var envelopeFactory = envelopeClass.getDeclaredMethod(
                    "message", Object.class, Runnable.class, ActorRuntime.ActorId.class, long.class);
            envelopeFactory.setAccessible(true);
            AtomicInteger secondReleaseCount = new AtomicInteger();
            assertEquals(Boolean.TRUE, reserveMethod.invoke(cell));
            Object first = envelopeFactory.invoke(null, Integer.valueOf(1),
                    (Runnable) () -> { throw new IllegalStateException("synthetic release failure"); },
                    null, 0L);
            assertTrue(mailbox.tryWrite(first));
            assertEquals(Boolean.TRUE, reserveMethod.invoke(cell));
            Object second = envelopeFactory.invoke(null, Integer.valueOf(2),
                    (Runnable) secondReleaseCount::incrementAndGet, null, 1L);
            assertTrue(mailbox.tryWrite(second));

            actor.stop();
            assertEquals(1, secondReleaseCount.get(),
                    "failure releasing one envelope must not strand the next");
            var queuedMessages = cell.getClass().getDeclaredField("queuedMessages");
            queuedMessages.setAccessible(true);
            assertEquals(0, queuedMessages.getInt(cell),
                    "all mailbox reservations must be reclaimed on cleanup failure");
            assertThrows(ExecutionException.class,
                    () -> actor.getDone().get(5, TimeUnit.SECONDS));
            assertTrue(actor.failure().orElseThrow().getMessage()
                    .contains("synthetic release failure"));
        }
    }

    @Test
    void awaitTerminationRequiresDoneEvenAfterRegistryEntryDisappears() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<Integer>spawnShared(() -> (message, context) -> { });
            actor.getReady().get(5, TimeUnit.SECONDS);

            // Reproduce the unregister -> DONE publication window precisely.
            // We restore the entry before stopping so no live actor is lost.
            var actorsField = ActorRuntime.class.getDeclaredField("actors");
            actorsField.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.Map<ActorRuntime.ActorId, Object> actors =
                    (java.util.Map<ActorRuntime.ActorId, Object>) actorsField.get(runtime);
            Object cell = actors.remove(actor.id());
            assertNotNull(cell);
            try {
                int before = actor.getDone().pendingRuntimeWaiterCount();
                assertFalse(actor.awaitTermination(25, TimeUnit.MILLISECONDS),
                        "registry removal does not mean DONE was published");
                assertEquals(before, actor.getDone().pendingRuntimeWaiterCount(),
                        "timed-out awaitTermination must detach its waiter");
            } finally {
                assertNull(actors.put(actor.id(), cell));
            }

            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            actor.getDone().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void blockedExitHookCannotPrematurelyCompleteDoneAndCanReenterStop() throws Exception {
        CountDownLatch exitHookEntered = new CountDownLatch(1);
        CountDownLatch exitHookReentered = new CountDownLatch(1);
        CountDownLatch exitHookRelease = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<ActorRuntime.ActorRef<Integer>> reference =
                new java.util.concurrent.atomic.AtomicReference<>();
        AtomicInteger exitHooks = new AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<Throwable> stopperFailure =
                new java.util.concurrent.atomic.AtomicReference<>();
        try (ActorRuntime runtime = new ActorRuntime()) {
            runtime.setActorExitHook(domain -> {
                exitHooks.incrementAndGet();
                ActorRuntime.ActorRef<Integer> actor = reference.get();
                exitHookEntered.countDown();
                if (actor != null) actor.stop(); // Reenter stop during physical retirement.
                exitHookReentered.countDown();
                try {
                    if (!exitHookRelease.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("exit hook release timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("exit hook interrupted", interrupted);
                }
            });
            var actor = runtime.<Integer>spawnShared(() -> (message, context) -> { });
            reference.set(actor);
            actor.getReady().get(5, TimeUnit.SECONDS);

            Thread stopper = new Thread(() -> {
                try {
                    actor.stop();
                } catch (Throwable failure) {
                    stopperFailure.set(failure);
                }
            }, "actor-reentrant-exit-hook-stop");
            stopper.start();
            try {
                assertTrue(exitHookEntered.await(5, TimeUnit.SECONDS),
                        () -> "exit hook not entered, callback count=" + exitHooks.get()
                                + ", stopperFailure=" + stopperFailure.get()
                                + ", done=" + actor.getDone().isDone());
                assertTrue(exitHookReentered.await(5, TimeUnit.SECONDS),
                        "actor.stop() called from exit hook must return without deadlock");
                assertFalse(actor.getDone().isDone(),
                        "DONE must not precede completion of the exit hook");
                assertFalse(actor.awaitTermination(75, TimeUnit.MILLISECONDS),
                        "awaitTermination must not observe a partially retired actor");
            } finally {
                exitHookRelease.countDown();
                stopper.join(5_000);
            }
            assertFalse(stopper.isAlive(), "reentrant stop must not deadlock finalization");
            assertNull(stopperFailure.get(), "reentrant stop must not throw");
            actor.getDone().get(5, TimeUnit.SECONDS);
            assertEquals(1, exitHooks.get(), "exit hook must run exactly once");
        } finally {
            exitHookRelease.countDown();
        }
    }

    @Test
    void racingGuestFailureAndSupervisorStopNeverPublishDoneInsideTurn() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> stopperFailure =
                new java.util.concurrent.atomic.AtomicReference<>();
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<Integer>spawnShared(() -> (message, context) -> {
                entered.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("turn release timed out");
                }
                throw new IllegalStateException("intentional actor failure");
            });
            actor.send(1);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var actorsField = ActorRuntime.class.getDeclaredField("actors");
            actorsField.setAccessible(true);
            Object cell = ((java.util.Map<?, ?>) actorsField.get(runtime)).get(actor.id());
            assertNotNull(cell);
            var monitorField = cell.getClass().getDeclaredField("lifecycleLock");
            monitorField.setAccessible(true);
            Object monitor = monitorField.get(cell);
            AtomicInteger settled = new AtomicInteger();
            AtomicInteger underMonitor = new AtomicInteger();
            CountDownLatch receiptDelivered = new CountDownLatch(1);
            actor.getDone().whenCompleteRuntime((value, failure) -> {
                if (Thread.holdsLock(monitor)) underMonitor.incrementAndGet();
                settled.incrementAndGet();
                receiptDelivered.countDown();
            });

            Thread stopper = new Thread(() -> {
                try {
                    actor.stop();
                } catch (Throwable failure) {
                    stopperFailure.set(failure);
                }
            }, "actor-failure-versus-stop");
            stopper.start();
            try {
                assertFalse(actor.awaitTermination(75, TimeUnit.MILLISECONDS),
                        "an active carrier must not publish DONE before turn exit");
            } finally {
                release.countDown();
                stopper.join(5_000);
            }
            assertFalse(stopper.isAlive());
            assertNull(stopperFailure.get());
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(receiptDelivered.await(5, TimeUnit.SECONDS),
                    "observe callback publication before checking exactly-once accounting");
            assertEquals(1, settled.get(), "DONE must settle exactly once");
            assertEquals(0, underMonitor.get(), "DONE callbacks cannot run under lifecycleLock");
        } finally {
            release.countDown();
        }
    }

    @Test
    void successfulStartupReadyCallbacksRunOutsideActorLifecycleMonitor() throws Exception {
        CountDownLatch initializerEntered = new CountDownLatch(1);
        CountDownLatch releaseInitializer = new CountDownLatch(1);
        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<Integer>spawnShared(() -> {
                initializerEntered.countDown();
                try {
                    if (!releaseInitializer.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("startup release timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("startup interrupted", interrupted);
                }
                return (message, context) -> { };
            });
            ref.send(1); // Host factories initialize on their first scheduled turn.
            try {
                assertTrue(initializerEntered.await(5, TimeUnit.SECONDS));
                var actorsField = ActorRuntime.class.getDeclaredField("actors");
                actorsField.setAccessible(true);
                Object cell = ((java.util.Map<?, ?>) actorsField.get(runtime)).get(ref.id());
                assertNotNull(cell);
                var lockField = cell.getClass().getDeclaredField("lifecycleLock");
                lockField.setAccessible(true);
                Object monitor = lockField.get(cell);
                AtomicInteger callbackCount = new AtomicInteger();
                AtomicInteger callbackUnderLock = new AtomicInteger();
                CountDownLatch readyObserved = new CountDownLatch(1);
                ref.getReady().whenCompleteRuntime((value, failure) -> {
                    if (Thread.holdsLock(monitor)) callbackUnderLock.incrementAndGet();
                    callbackCount.incrementAndGet();
                    readyObserved.countDown();
                });
                assertFalse(ref.getReady().isDone(), "factory is deliberately suspended");
                releaseInitializer.countDown();
                assertTrue(readyObserved.await(5, TimeUnit.SECONDS));
                ref.getReady().get(5, TimeUnit.SECONDS);
                assertEquals(1, callbackCount.get());
                assertEquals(0, callbackUnderLock.get(),
                        "READY must never invoke dependent Futures under lifecycleLock");
                ref.stop();
                ref.getDone().get(5, TimeUnit.SECONDS);
            } finally {
                releaseInitializer.countDown();
            }
        } finally {
            releaseInitializer.countDown();
        }
    }

    @Test
    void hostStopWaitsForDoneWhenFinalizationReadinessCallbackBlocks() throws Exception {
        CountDownLatch initializerEntered = new CountDownLatch(1);
        CountDownLatch releaseInitializer = new CountDownLatch(1);
        CountDownLatch readinessCallbackEntered = new CountDownLatch(1);
        CountDownLatch releaseReadinessCallback = new CountDownLatch(1);
        CountDownLatch stopReturned = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> stopFailure =
                new java.util.concurrent.atomic.AtomicReference<>();
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<Integer>spawnShared(() -> {
                initializerEntered.countDown();
                try {
                    if (!releaseInitializer.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("initializer release timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("initializer interrupted", interrupted);
                }
                throw new IllegalStateException("deliberate initializer failure");
            });
            actor.send(1); // Admit the first turn before awaiting initializer entry.
            assertTrue(initializerEntered.await(5, TimeUnit.SECONDS));
            actor.getReady().whenCompleteRuntime((value, failure) -> {
                readinessCallbackEntered.countDown();
                try {
                    if (!releaseReadinessCallback.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("readiness observer release timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });

            Thread stopper = new Thread(() -> {
                try {
                    actor.stop();
                } catch (Throwable failure) {
                    stopFailure.set(failure);
                } finally {
                    stopReturned.countDown();
                }
            }, "host-stop-awaits-actual-done");
            stopper.start();
            try {
                // Stop is requested while the initializer still owns the actor
                // carrier. Its READY Future has not been settled yet.
                releaseInitializer.countDown();
                assertTrue(readinessCallbackEntered.await(5, TimeUnit.SECONDS));
                assertFalse(actor.getDone().isDone(),
                        "DONE must follow completion of finalization READY callbacks");
                assertFalse(stopReturned.await(100, TimeUnit.MILLISECONDS),
                        "synchronous host stop must not return before DONE is published");
            } finally {
                releaseInitializer.countDown();
                releaseReadinessCallback.countDown();
                stopper.join(5_000);
            }
            assertFalse(stopper.isAlive(), "host stop must finish after the callback returns");
            assertNull(stopFailure.get(), "host stop must not fail when child finalization completes");
            assertTrue(actor.getDone().isDone());
        } finally {
            releaseInitializer.countDown();
            releaseReadinessCallback.countDown();
        }
    }

    @Test
    void hostStopMustNotReturnDuringRegistryRemovalBeforeDone() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<Integer>spawnShared(() -> (message, context) -> { });
            var actorsField = ActorRuntime.class.getDeclaredField("actors");
            actorsField.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.Map<ActorRuntime.ActorId, Object> actors =
                    (java.util.Map<ActorRuntime.ActorId, Object>) actorsField.get(runtime);
            Object cell = actors.remove(actor.id());
            assertNotNull(cell);

            CountDownLatch stopStarted = new CountDownLatch(1);
            CountDownLatch stopReturned = new CountDownLatch(1);
            java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                    new java.util.concurrent.atomic.AtomicReference<>();
            Thread stopper = new Thread(() -> {
                stopStarted.countDown();
                try {
                    actor.stop();
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    stopReturned.countDown();
                }
            }, "stop-during-registry-retirement");
            try {
                stopper.start();
                assertTrue(stopStarted.await(5, TimeUnit.SECONDS));
                assertFalse(stopReturned.await(75, TimeUnit.MILLISECONDS),
                        "a missing registry entry must not cause stop to return before DONE");
                assertFalse(actor.getDone().isDone());
            } finally {
                assertNull(actors.put(actor.id(), cell));
                actor.stop();
                stopper.join(5_000);
            }
            assertFalse(stopper.isAlive());
            assertNull(failure.get());
            assertTrue(actor.getDone().isDone());
        }
    }

    @Test
    void mailboxClosureCallbackCanReentrantlyStopWithoutWaitingForOwnDone() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<Integer>spawnShared(() -> (message, context) -> { });
            actor.getReady().get(5, TimeUnit.SECONDS);

            var actorsField = ActorRuntime.class.getDeclaredField("actors");
            actorsField.setAccessible(true);
            Object cell = ((java.util.Map<?, ?>) actorsField.get(runtime)).get(actor.id());
            assertNotNull(cell);
            var continuationField = cell.getClass().getDeclaredField("continuationMailbox");
            continuationField.setAccessible(true);
            @SuppressWarnings("unchecked")
            ChannelRuntime.Channel<Object> continuationMailbox =
                    (ChannelRuntime.Channel<Object>) continuationField.get(cell);
            OresFuture<Object> pendingRead = continuationMailbox.readAsync();
            assertFalse(pendingRead.isDone());

            java.util.concurrent.atomic.AtomicReference<Throwable> callbackFailure =
                    new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicReference<Throwable> stopFailure =
                    new java.util.concurrent.atomic.AtomicReference<>();
            CountDownLatch callbackReturned = new CountDownLatch(1);
            AtomicInteger callbacks = new AtomicInteger();
            pendingRead.whenCompleteRuntime((value, failure) -> {
                callbacks.incrementAndGet();
                try {
                    // Channel.close settles pending read waiters inline in
                    // terminateTree(), before DONE can possibly be published.
                    // Reentrant host stop must only request retirement, not wait.
                    actor.stop();
                    assertThrows(IllegalStateException.class,
                            () -> actor.awaitTermination(1, TimeUnit.MILLISECONDS),
                            "direct host wait on a retirement callback must fail fast");
                    assertThrows(IllegalStateException.class, runtime::close,
                            "runtime close from an actor retirement callback must fail fast");
                } catch (Throwable unexpected) {
                    callbackFailure.set(unexpected);
                } finally {
                    callbackReturned.countDown();
                }
            });

            Thread stopper = new Thread(() -> {
                try {
                    actor.stop();
                } catch (Throwable failure) {
                    stopFailure.set(failure);
                }
            }, "actor-stop-reentrant-mailbox-close");
            stopper.start();
            stopper.join(5_000);
            assertFalse(stopper.isAlive(),
                    "reentrant channel-close callback cannot wait for its own DONE");
            assertTrue(callbackReturned.await(1, TimeUnit.SECONDS));
            assertNull(callbackFailure.get());
            assertNull(stopFailure.get());
            assertEquals(1, callbacks.get());
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            actor.getDone().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void rejectedSuspendedReceiveFinalizesOutsideActorMonitor() throws Exception {
        OresFuture<Void> resume = new OresFuture<>();
        CountDownLatch suspended = new CountDownLatch(1);
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<Integer>spawnShared(() -> (message, context) -> {
                context.runtime().suspendCurrentUserMailboxUntil(resume);
                suspended.countDown();
            });
            actor.send(1);
            assertTrue(suspended.await(5, TimeUnit.SECONDS));

            var actorsField = ActorRuntime.class.getDeclaredField("actors");
            actorsField.setAccessible(true);
            Object cell = ((java.util.Map<?, ?>) actorsField.get(runtime)).get(actor.id());
            assertNotNull(cell);
            var monitorField = cell.getClass().getDeclaredField("lifecycleLock");
            monitorField.setAccessible(true);
            Object monitor = monitorField.get(cell);
            var activeTurnsField = cell.getClass().getDeclaredField("activeTurns");
            activeTurnsField.setAccessible(true);
            var carrierActiveField = cell.getClass().getDeclaredField("carrierActive");
            carrierActiveField.setAccessible(true);
            var scheduledField = cell.getClass().getDeclaredField("scheduled");
            scheduledField.setAccessible(true);
            var scheduled = (java.util.concurrent.atomic.AtomicBoolean) scheduledField.get(cell);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            synchronized (monitor) {
                while (activeTurnsField.getInt(cell) != 0
                        || carrierActiveField.getBoolean(cell)
                        || scheduled.get()) {
                    long remaining = deadline - System.nanoTime();
                    assertTrue(remaining > 0, "suspended receive must exit its carrier");
                    TimeUnit.NANOSECONDS.timedWait(monitor, remaining);
                }
            }

            CountDownLatch observed = new CountDownLatch(1);
            AtomicInteger callbackUnderMonitor = new AtomicInteger();
            actor.getDone().whenCompleteRuntime((value, failure) -> {
                if (Thread.holdsLock(monitor)) callbackUnderMonitor.incrementAndGet();
                observed.countDown();
            });

            // Simulate the admission race where the runtime has shut its
            // continuation gate while this suspended actor is still alive.
            // Restore the test-only flag before closing the runtime normally.
            var closedField = ActorRuntime.class.getDeclaredField("closed");
            closedField.setAccessible(true);
            var closed = (java.util.concurrent.atomic.AtomicBoolean) closedField.get(runtime);
            assertFalse(closed.get());
            try {
                closed.set(true);
                assertTrue(resume.completeFromRuntime(null));
            } finally {
                closed.set(false);
            }

            assertTrue(observed.await(5, TimeUnit.SECONDS),
                    "failed continuation admission must not strand DONE");
            assertEquals(0, callbackUnderMonitor.get(),
                    "source receive fail-stop must publish DONE after releasing lifecycleLock");
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertThrows(ExecutionException.class, () -> actor.getDone().get(5, TimeUnit.SECONDS));
        }
    }

}
