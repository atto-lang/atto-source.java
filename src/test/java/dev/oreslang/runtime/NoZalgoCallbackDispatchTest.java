package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * No-Zalgo contract: host-side settlement may be synchronous, but guest
 * continuations run only in a new scheduler/actor dispatch.
 */
@Timeout(20)
final class NoZalgoCallbackDispatchTest {
    @Test
    void synchronousForeignCallbackSettlementNeverResumesGuestOnRegistrarStack()
            throws Exception {
        try (var scheduler = new OresScheduler(2)) {
            AtomicInteger segments = new AtomicInteger();
            AtomicBoolean insideRegistrar = new AtomicBoolean();
            AtomicLong firstDispatch = new AtomicLong();
            AtomicReference<Thread> registrarCarrier = new AtomicReference<>();

            OresFuture<Integer> result = scheduler.start(resume -> {
                if (segments.getAndIncrement() == 0) {
                    firstDispatch.set(OresScheduler.currentDispatchId());
                    registrarCarrier.set(Thread.currentThread());
                    OresFuture<Integer> settled = OresFuture.fromCallback(callback -> {
                        insideRegistrar.set(true);
                        try {
                            callback.resolve(41);
                            assertTrue(callback.isDone());
                            assertEquals(1, segments.get(),
                                    "synchronous settlement must not recurse into guest code");
                        } finally {
                            insideRegistrar.set(false);
                        }
                    });
                    assertEquals(41, settled.get(0, TimeUnit.MILLISECONDS));
                    return OresScheduler.await(settled);
                }

                assertFalse(insideRegistrar.get(), "guest code resumed on registrar stack");
                assertEquals(41, resume.value());
                assertNotEquals(firstDispatch.get(), OresScheduler.currentDispatchId(),
                        "continuation must use a fresh scheduler dispatch");
                // Carrier migration is allowed; distinct OS threads are not required.
                assertNotNull(registrarCarrier.get());
                return OresScheduler.done(42);
            });
            assertEquals(42, result.get(5, TimeUnit.SECONDS));
            assertEquals(2, segments.get());
        }
    }

    @Test
    void completionFromForeignThreadDuringCurrentSegmentCannotEnterGuestInline()
            throws Exception {
        try (var scheduler = new OresScheduler(2)) {
            var source = new OresFuture<Integer>();
            var firstEntered = new CountDownLatch(1);
            var producerFinished = new CountDownLatch(1);
            var insideFirstSegment = new AtomicBoolean();
            var producer = new AtomicReference<Thread>();
            var segments = new AtomicInteger();
            var firstDispatch = new AtomicLong();

            OresFuture<Integer> result = scheduler.start(resume -> {
                if (segments.getAndIncrement() == 0) {
                    firstDispatch.set(OresScheduler.currentDispatchId());
                    insideFirstSegment.set(true);
                    firstEntered.countDown();
                    try {
                        assertTrue(producerFinished.await(5, TimeUnit.SECONDS),
                                "foreign completion failed to race with active guest segment");
                        assertTrue(source.isDone());
                        return OresScheduler.await(source);
                    } finally {
                        insideFirstSegment.set(false);
                    }
                }

                assertFalse(insideFirstSegment.get(), "producer caused reentrant guest execution");
                assertNotSame(producer.get(), Thread.currentThread(),
                        "producer thread must not execute the guest continuation");
                assertNotEquals(firstDispatch.get(), OresScheduler.currentDispatchId());
                assertEquals(9, resume.value());
                return OresScheduler.done(10);
            });
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
            Thread foreign = Thread.startVirtualThread(() -> {
                try {
                    producer.set(Thread.currentThread());
                    assertTrue(source.completeFromRuntime(9));
                } finally {
                    producerFinished.countDown();
                }
            });
            foreign.join(5_000);
            assertFalse(foreign.isAlive());
            assertEquals(10, result.get(5, TimeUnit.SECONDS));
            assertEquals(2, segments.get());
        }
    }

    @Test
    void postStopContinuationRegistrationDoesNotCancelUnownedFuture()
            throws Exception {
        AtomicReference<ActorRuntime.ContinuationTarget> target = new AtomicReference<>();
        AtomicReference<Throwable> receiveFailure = new AtomicReference<>();
        AtomicInteger guestCallbacks = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                try {
                    target.set(runtime.captureCurrentContinuationTarget());
                    entered.countDown();
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("receive release timed out");
                    }
                } catch (Throwable problem) {
                    receiveFailure.compareAndSet(null, problem);
                }
            });
            actor.ready().get(5, TimeUnit.SECONDS);
            actor.send("hold");
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertTrue(actor.cancel(), "actor must be marked stopped");
                // The actor still occupies its receive turn, so its ActorCell
                // has not finalized. The target is valid but no longer admits
                // any new operation. A producer not yet owned by this actor
                // must remain independently settleable.
                var independent = new OresFuture<Integer>();
                runtime.enqueueOnCompletion(independent, target.get(),
                        (value, failure) -> guestCallbacks.incrementAndGet());
                assertFalse(independent.isDone(),
                        "rejected post-stop registration cancelled external Future");
                assertEquals(0, independent.pendingRuntimeWaiterCount(),
                        "rejected registration leaked a Future waiter");
                assertTrue(independent.completeFromRuntime(17),
                        "rejected registration must not consume producer authority");
                assertEquals(17, independent.get(0, TimeUnit.MILLISECONDS));
                assertEquals(0, guestCallbacks.get(),
                        "stopped actor accepted a new guest continuation");
            } finally {
                release.countDown();
            }
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertNull(receiveFailure.get(),
                    () -> String.valueOf(receiveFailure.get()));
            assertEquals(0, guestCallbacks.get());
        }
    }

    @Test
    void continuationTargetCannotBeTransferredIntoAnotherRuntime()
            throws Exception {
        AtomicReference<Throwable> observedFailure = new AtomicReference<>();
        CountDownLatch verified = new CountDownLatch(1);
        try (var owner = new ActorRuntime(IsolatePolicy.developer());
                var foreign = new ActorRuntime(IsolatePolicy.developer())) {
            var actor = owner.<String>spawnShared(() -> (message, context) -> {
                try {
                    var target = owner.captureCurrentContinuationTarget();
                    var source = new OresFuture<Integer>();
                    var rejection = assertThrows(IllegalArgumentException.class,
                            () -> foreign.enqueueOnCompletion(source, target,
                                    (value, error) -> fail("foreign actor continuation ran")));
                    assertTrue(rejection.getMessage().contains("different ActorRuntime"));
                    assertFalse(source.isDone(),
                            "invalid target must be rejected before owning or cancelling source");
                    assertEquals(0, source.pendingRuntimeWaiterCount(),
                            "invalid target must not register a foreign runtime waiter");
                } catch (Throwable failure) {
                    observedFailure.compareAndSet(null, failure);
                } finally {
                    verified.countDown();
                }
            });
            actor.ready().get(5, TimeUnit.SECONDS);
            actor.send("check");
            assertTrue(verified.await(5, TimeUnit.SECONDS));
            assertNull(observedFailure.get(),
                    () -> String.valueOf(observedFailure.get()));
            actor.cancel();
        }
    }

    @Test
    void completedFutureRegisteredWithinActorCannotInvokeGuestBeforeReceiveReturns()
            throws Exception {
        AtomicBoolean insideReceive = new AtomicBoolean();
        AtomicInteger callbacks = new AtomicInteger();
        AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
        CountDownLatch callbackRan = new CountDownLatch(1);
        try (var runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                insideReceive.set(true);
                try {
                    var target = runtime.captureCurrentContinuationTarget();
                    runtime.enqueueOnCompletion(OresFuture.completed(9), target, (value, failure) -> {
                        try {
                            assertFalse(insideReceive.get(),
                                    "already-settled Future reentered the active receive");
                            assertTrue(runtime.currentThreadOwnsActorExecutionLease(),
                                    "actor callback bypassed physical lease admission");
                            assertNull(failure);
                            assertEquals(9, value);
                            callbacks.incrementAndGet();
                        } catch (Throwable problem) {
                            callbackFailure.compareAndSet(null, problem);
                        } finally {
                            callbackRan.countDown();
                        }
                    });
                    assertEquals(0, callbacks.get(),
                            "guest continuation executed synchronously during registration");
                } finally {
                    insideReceive.set(false);
                }
            });
            actor.ready().get(5, TimeUnit.SECONDS);
            actor.send("register");
            assertTrue(callbackRan.await(5, TimeUnit.SECONDS),
                    "settled callback was lost instead of enqueued");
            assertNull(callbackFailure.get(), () -> String.valueOf(callbackFailure.get()));
            assertEquals(1, callbacks.get(), "guest callback must run exactly once");
            actor.cancel();
        }
    }

    @Test
    void foreignProducerCompletionIsEnqueuedAfterActorReceiveAndOnlyOnce()
            throws Exception {
        AtomicBoolean insideReceive = new AtomicBoolean();
        AtomicReference<OresFuture<Integer>> pending = new AtomicReference<>();
        AtomicReference<Thread> producerThread = new AtomicReference<>();
        AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
        AtomicInteger callbacks = new AtomicInteger();
        CountDownLatch registered = new CountDownLatch(1);
        CountDownLatch producerCompleted = new CountDownLatch(1);
        CountDownLatch callbackRan = new CountDownLatch(1);

        try (var runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                insideReceive.set(true);
                try {
                    var future = new OresFuture<Integer>();
                    var target = runtime.captureCurrentContinuationTarget();
                    runtime.enqueueOnCompletion(future, target, (value, failure) -> {
                        try {
                            assertFalse(insideReceive.get(),
                                    "foreign producer reentered a live actor receive");
                            assertNotSame(producerThread.get(), Thread.currentThread(),
                                    "producer thread executed the guest continuation");
                            assertTrue(runtime.currentThreadOwnsActorExecutionLease(),
                                    "callback escaped serialized actor execution");
                            assertNull(failure);
                            assertEquals(42, value);
                            callbacks.incrementAndGet();
                        } catch (Throwable error) {
                            callbackFailure.compareAndSet(null, error);
                        } finally {
                            callbackRan.countDown();
                        }
                    });
                    pending.set(future);
                    registered.countDown();
                    assertTrue(producerCompleted.await(5, TimeUnit.SECONDS),
                            "foreign producer did not settle during receive");
                    assertEquals(0, callbacks.get(),
                            "guest callback ran before receive relinquished its actor lane");
                } finally {
                    insideReceive.set(false);
                }
            });
            actor.ready().get(5, TimeUnit.SECONDS);
            actor.send("register");
            assertTrue(registered.await(5, TimeUnit.SECONDS));
            Thread producer = Thread.startVirtualThread(() -> {
                producerThread.set(Thread.currentThread());
                try {
                    assertTrue(pending.get().completeFromRuntime(42));
                    assertFalse(pending.get().completeFromRuntime(43),
                            "late duplicate completion must lose");
                } catch (Throwable failure) {
                    callbackFailure.compareAndSet(null, failure);
                } finally {
                    producerCompleted.countDown();
                }
            });
            producer.join(5_000);
            assertFalse(producer.isAlive());
            assertTrue(callbackRan.await(5, TimeUnit.SECONDS),
                    "producer completion was lost instead of enqueued");
            assertNull(callbackFailure.get(),
                    () -> String.valueOf(callbackFailure.get()));
            assertEquals(1, callbacks.get());
            actor.cancel();
        }
    }

    @Test
    void failedOrCancelledForeignFutureOnlyResumesOnSerializedActorLane()
            throws Exception {
        for (boolean cancellation : new boolean[] {false, true}) {
            AtomicBoolean insideReceive = new AtomicBoolean();
            AtomicReference<OresFuture<Integer>> pending = new AtomicReference<>();
            AtomicReference<Thread> producerThread = new AtomicReference<>();
            AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
            AtomicInteger guestCallbacks = new AtomicInteger();
            CountDownLatch registered = new CountDownLatch(1);
            CountDownLatch producerCompleted = new CountDownLatch(1);
            CountDownLatch callbackRan = new CountDownLatch(1);
            try (var runtime = new ActorRuntime(IsolatePolicy.developer())) {
                var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                    insideReceive.set(true);
                    try {
                        var future = new OresFuture<Integer>();
                        var target = runtime.captureCurrentContinuationTarget();
                        runtime.enqueueOnCompletion(future, target, (value, failure) -> {
                            try {
                                assertFalse(insideReceive.get(),
                                        "terminal failure reentered active receive");
                                assertNotSame(producerThread.get(), Thread.currentThread(),
                                        "producer thread ran an actor continuation");
                                assertTrue(runtime.currentThreadOwnsActorExecutionLease(),
                                        "failed/cancelled callback bypassed the actor lane");
                                assertNull(value);
                                if (cancellation) {
                                    assertInstanceOf(
                                            java.util.concurrent.CancellationException.class,
                                            failure);
                                } else {
                                    assertInstanceOf(IllegalStateException.class, failure);
                                    assertEquals("producer failed", failure.getMessage());
                                }
                                guestCallbacks.incrementAndGet();
                            } catch (Throwable problem) {
                                callbackFailure.compareAndSet(null, problem);
                            } finally {
                                callbackRan.countDown();
                            }
                        });
                        pending.set(future);
                        registered.countDown();
                        assertTrue(producerCompleted.await(5, TimeUnit.SECONDS),
                                "producer did not settle during the actor receive");
                        assertEquals(0, guestCallbacks.get(),
                                "terminal callback ran before receive released its lane");
                    } finally {
                        insideReceive.set(false);
                    }
                });
                actor.ready().get(5, TimeUnit.SECONDS);
                actor.send("register");
                assertTrue(registered.await(5, TimeUnit.SECONDS));
                Thread producer = Thread.startVirtualThread(() -> {
                    producerThread.set(Thread.currentThread());
                    try {
                        OresFuture<Integer> source = pending.get();
                        if (cancellation) {
                            assertTrue(source.cancel(false));
                        } else {
                            assertTrue(source.failFromRuntime(
                                    new IllegalStateException("producer failed")));
                        }
                        assertFalse(source.completeFromRuntime(99),
                                "a terminal Future must reject a late success");
                    } catch (Throwable problem) {
                        callbackFailure.compareAndSet(null, problem);
                    } finally {
                        producerCompleted.countDown();
                    }
                });
                producer.join(5_000);
                assertFalse(producer.isAlive(), "producer deadlocked");
                assertTrue(callbackRan.await(5, TimeUnit.SECONDS),
                        "failed/cancelled Future lost its actor callback");
                assertNull(callbackFailure.get(),
                        () -> String.valueOf(callbackFailure.get()));
                assertEquals(1, guestCallbacks.get());
                assertEquals(0, pending.get().pendingRuntimeWaiterCount(),
                        "settled Future retained runtime waiter registrations");
                actor.cancel();
            }
        }
    }

    @Test
    void throwingActorContinuationFailsItsActorWithoutBeingSwallowed()
            throws Exception {
        IllegalStateException original = new IllegalStateException(
                "continuation consumer failed");
        CountDownLatch registered = new CountDownLatch(1);
        AtomicInteger invoked = new AtomicInteger();
        try (var runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                var target = runtime.captureCurrentContinuationTarget();
                runtime.enqueueOnCompletion(OresFuture.completed(5), target,
                        (value, failure) -> {
                            invoked.incrementAndGet();
                            assertTrue(runtime.currentThreadOwnsActorExecutionLease(),
                                    "throwing continuation must still own the actor turn");
                            throw original;
                        });
                registered.countDown();
            });
            actor.ready().get(5, TimeUnit.SECONDS);
            actor.send("register");
            assertTrue(registered.await(5, TimeUnit.SECONDS));
            var thrown = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> actor.done().get(5, TimeUnit.SECONDS));
            assertSame(original, thrown.getCause(),
                    "actor supervisor must retain the original continuation failure");
            assertSame(original, actor.failure().orElse(null));
            assertEquals(1, invoked.get(),
                    "a throwing continuation cannot be retried or duplicated");
        }
    }

    @Test
    void cancellationAfterCompletionEnqueueDropsQueuedGuestContinuation()
            throws Exception {
        AtomicReference<OresFuture<Integer>> sourceRef = new AtomicReference<>();
        AtomicReference<Throwable> receiveFailure = new AtomicReference<>();
        AtomicInteger guestCallbacks = new AtomicInteger();
        CountDownLatch registered = new CountDownLatch(1);
        CountDownLatch releaseReceive = new CountDownLatch(1);
        CountDownLatch receiveReturned = new CountDownLatch(1);

        try (var runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                try {
                    var source = new OresFuture<Integer>();
                    var target = runtime.captureCurrentContinuationTarget();
                    runtime.enqueueOnCompletion(source, target, (value, failure) ->
                            guestCallbacks.incrementAndGet());
                    sourceRef.set(source);
                    registered.countDown();
                    if (!releaseReceive.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException(
                                "receive was never released after cancellation");
                    }
                } catch (Throwable failure) {
                    receiveFailure.compareAndSet(null, failure);
                } finally {
                    receiveReturned.countDown();
                }
            });
            actor.ready().get(5, TimeUnit.SECONDS);
            actor.send("register");
            try {
                assertTrue(registered.await(5, TimeUnit.SECONDS));
                OresFuture<Integer> source = sourceRef.get();
                assertNotNull(source);
                assertTrue(source.completeFromRuntime(9),
                        "producer must complete before actor cancellation");
                assertEquals(0, guestCallbacks.get(),
                        "queued continuation must not bypass an active receive");
                assertTrue(actor.cancel(), "cancellation must fence queued actor work");
            } finally {
                // Never strand the carrier if a preceding assertion fails.
                releaseReceive.countDown();
            }
            assertTrue(receiveReturned.await(5, TimeUnit.SECONDS));
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS),
                    "actor must fully finalize before checking callback suppression");
            assertNull(receiveFailure.get(),
                    () -> String.valueOf(receiveFailure.get()));
            assertEquals(0, guestCallbacks.get(),
                    "cancelled actor must discard its already-enqueued continuation");
            assertEquals(0, sourceRef.get().pendingRuntimeWaiterCount(),
                    "completed Future must not retain an actor waiter");
        }
    }

    @Test
    void failedInternalContinuationEnqueueFailsActorInsteadOfLosingNotification()
            throws Exception {
        AtomicReference<OresFuture<Integer>> sourceRef = new AtomicReference<>();
        AtomicReference<Throwable> receiveFailure = new AtomicReference<>();
        AtomicInteger guestCallbacks = new AtomicInteger();
        CountDownLatch registered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                try {
                    var source = new OresFuture<Integer>();
                    runtime.enqueueOnCompletion(source,
                            runtime.captureCurrentContinuationTarget(),
                            (value, failure) -> guestCallbacks.incrementAndGet());
                    sourceRef.set(source);
                    registered.countDown();
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("fault injection release timed out");
                    }
                } catch (Throwable failure) {
                    receiveFailure.compareAndSet(null, failure);
                }
            });
            actor.ready().get(5, TimeUnit.SECONDS);
            actor.send("register");
            try {
                assertTrue(registered.await(5, TimeUnit.SECONDS));
                // Model a broken internal channel after admission but before
                // publication. A runtime waiter exception alone would be
                // swallowed by OresFuture.notifyWaiter. ActorRuntime must
                // terminate the actor instead of losing a guest continuation.
                var actorsField = ActorRuntime.class.getDeclaredField("actors");
                actorsField.setAccessible(true);
                java.util.Map<?, ?> cells =
                        (java.util.Map<?, ?>) actorsField.get(runtime);
                Object cell = cells.get(actor.id());
                assertNotNull(cell);
                var continuationField =
                        cell.getClass().getDeclaredField("continuationMailbox");
                continuationField.setAccessible(true);
                @SuppressWarnings("unchecked")
                ChannelRuntime.Channel<Object> channel =
                        (ChannelRuntime.Channel<Object>) continuationField.get(cell);
                channel.close(new IllegalStateException("injected channel fault"));

                assertTrue(sourceRef.get().completeFromRuntime(42),
                        "producer terminal result must remain authoritative");
                assertEquals(0, guestCallbacks.get(),
                        "failed enqueue must not run guest code inline");
            } finally {
                release.countDown();
            }
            var failed = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> actor.done().get(5, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, failed.getCause());
            assertTrue(failed.getCause().getMessage().contains(
                            "continuation mailbox capacity invariant violated"),
                    () -> String.valueOf(failed.getCause()));
            assertNull(receiveFailure.get(),
                    () -> String.valueOf(receiveFailure.get()));
            assertEquals(0, guestCallbacks.get());
            assertEquals(0, sourceRef.get().pendingRuntimeWaiterCount(),
                    "failed notification retained a Future waiter");
        }
    }

    @Test
    void actorCancellationRevokesPendingCompletionAndDropsLateProducer()
            throws Exception {
        AtomicReference<OresFuture<Integer>> pending = new AtomicReference<>();
        AtomicInteger guestCallbacks = new AtomicInteger();
        CountDownLatch registered = new CountDownLatch(1);
        try (var runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                var source = new OresFuture<Integer>();
                var target = runtime.captureCurrentContinuationTarget();
                runtime.enqueueOnCompletion(source, target, (value, failure) ->
                        guestCallbacks.incrementAndGet());
                pending.set(source);
                registered.countDown();
            });
            actor.ready().get(5, TimeUnit.SECONDS);
            actor.send("register");
            assertTrue(registered.await(5, TimeUnit.SECONDS),
                    "receive did not register its pending actor-owned Future");
            assertTrue(actor.cancel(), "actor must accept structured cancellation");

            OresFuture<Integer> source = pending.get();
            assertNotNull(source);
            assertTrue(source.isCancelled(),
                    "actor shutdown must cancel and revoke its pending Future");
            assertFalse(source.completeFromRuntime(77),
                    "late producer must not resurrect cancelled actor work");
            assertEquals(0, source.pendingRuntimeWaiterCount(),
                    "cancelled operation must not retain its actor waiter");
            assertEquals(0, guestCallbacks.get(),
                    "guest continuations must not execute after actor termination");
        }
    }

    @Test
    void completionRacingWaiterRegistrationIsDeliveredOnceOnFreshGuestTurn()
            throws Exception {
        try (var scheduler = new OresScheduler(2)) {
            // Alternate already-settled and genuinely pending registration.
            // Both must preserve the no-Zalgo dispatch boundary.
            for (int round = 0; round < 64; round++) {
                final int expected = round;
                var source = new OresFuture<Integer>();
                var segments = new AtomicInteger();
                var currentSegmentRunning = new AtomicBoolean();
                var firstDispatch = new AtomicLong();
                OresFuture<Integer> task = scheduler.start(resume -> {
                    if (segments.getAndIncrement() == 0) {
                        currentSegmentRunning.set(true);
                        firstDispatch.set(OresScheduler.currentDispatchId());
                        try {
                            return OresScheduler.await(source);
                        } finally {
                            currentSegmentRunning.set(false);
                        }
                    }
                    assertFalse(currentSegmentRunning.get(), "guest callback reentered await");
                    assertNotEquals(firstDispatch.get(), OresScheduler.currentDispatchId());
                    assertEquals(expected, resume.value());
                    return OresScheduler.done(expected);
                });

                if ((round & 1) != 0) {
                    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (source.pendingRuntimeWaiterCount() == 0 && System.nanoTime() < until) {
                        Thread.onSpinWait();
                    }
                    assertEquals(1, source.pendingRuntimeWaiterCount(),
                            "pending-path await was never registered");
                }
                assertTrue(source.completeFromRuntime(expected));
                assertEquals(expected, task.get(5, TimeUnit.SECONDS));
                assertEquals(2, segments.get(), "completion caused a duplicate guest resume");
                assertEquals(0, source.pendingRuntimeWaiterCount(),
                        "completed await retained a registration");
            }
        }
    }
}
