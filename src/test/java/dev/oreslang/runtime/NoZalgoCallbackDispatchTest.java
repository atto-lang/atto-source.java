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
            AtomicBoolean callbackDoneInsideRegistrar = new AtomicBoolean();
            AtomicInteger segmentsSeenInsideRegistrar = new AtomicInteger(-1);

            OresFuture<Integer> result = scheduler.start(resume -> {
                if (segments.getAndIncrement() == 0) {
                    firstDispatch.set(OresScheduler.currentDispatchId());
                    registrarCarrier.set(Thread.currentThread());
                    OresFuture<Integer> settled = OresFuture.fromCallback(callback -> {
                        insideRegistrar.set(true);
                        try {
                            callback.resolve(41);
                            // Registrar exceptions after resolve() are intentionally
                            // swallowed by the callback adapter. Capture evidence here
                            // and assert it *after* fromCallback has returned.
                            callbackDoneInsideRegistrar.set(callback.isDone());
                            segmentsSeenInsideRegistrar.set(segments.get());
                        } finally {
                            insideRegistrar.set(false);
                        }
                    });
                    assertTrue(callbackDoneInsideRegistrar.get());
                    assertEquals(1, segmentsSeenInsideRegistrar.get(),
                            "synchronous foreign settlement recursively entered guest code");
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
    void alreadyCompletedHostCompletionStageCannotResumeGuestInline()
            throws Exception {
        try (var scheduler = new OresScheduler(2)) {
            AtomicInteger segments = new AtomicInteger();
            AtomicLong registeringDispatch = new AtomicLong();
            AtomicBoolean registering = new AtomicBoolean();
            OresFuture<Integer> result = scheduler.start(resume -> {
                if (segments.getAndIncrement() == 0) {
                    registering.set(true);
                    registeringDispatch.set(OresScheduler.currentDispatchId());
                    try {
                        var host = java.util.concurrent.CompletableFuture.completedFuture(73);
                        OresFuture<Integer> awaited = OresFuture.from(host);
                        assertEquals(1, segments.get(),
                                "host whenComplete must not invoke guest continuation inline");
                        return OresScheduler.await(awaited);
                    } finally {
                        registering.set(false);
                    }
                }
                assertFalse(registering.get());
                assertEquals(73, resume.value());
                assertNotEquals(registeringDispatch.get(), OresScheduler.currentDispatchId());
                return OresScheduler.done(74);
            });
            assertEquals(74, result.get(5, TimeUnit.SECONDS));
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
            var producerFailure = new AtomicReference<Throwable>();
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
                } catch (Throwable failure) {
                    producerFailure.compareAndSet(null, failure);
                } finally {
                    producerFinished.countDown();
                }
            });
            foreign.join(5_000);
            assertFalse(foreign.isAlive());
            assertNull(producerFailure.get(), () -> String.valueOf(producerFailure.get()));
            assertEquals(10, result.get(5, TimeUnit.SECONDS));
            assertEquals(2, segments.get());
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
    void settledFutureRegisteredDuringBlockedReceiveCannotRunGuestOnAnotherCarrier()
            throws Exception {
        CountDownLatch registered = new CountDownLatch(1);
        CountDownLatch releaseReceive = new CountDownLatch(1);
        CountDownLatch callbackRan = new CountDownLatch(1);
        AtomicBoolean receiving = new AtomicBoolean();
        AtomicInteger callbacks = new AtomicInteger();
        AtomicReference<Throwable> violation = new AtomicReference<>();
        try (var runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(4, 4, 1, 128))) {
            var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                receiving.set(true);
                try {
                    var target = runtime.captureCurrentContinuationTarget();
                    runtime.enqueueOnCompletion(OresFuture.completed(17), target, (value, failure) -> {
                        try {
                            assertFalse(receiving.get(),
                                    "asynchronous guest callback overtook active receive");
                            assertTrue(runtime.currentThreadOwnsActorExecutionLease());
                            assertNull(failure);
                            assertEquals(17, value);
                            callbacks.incrementAndGet();
                        } catch (Throwable problem) {
                            violation.compareAndSet(null, problem);
                        } finally {
                            callbackRan.countDown();
                        }
                    });
                    registered.countDown();
                    assertTrue(releaseReceive.await(5, TimeUnit.SECONDS));
                    assertEquals(0, callbacks.get(),
                            "callback executed before synchronous receive finished");
                } finally {
                    receiving.set(false);
                }
            });
            try {
                actor.ready().get(5, TimeUnit.SECONDS);
                actor.send("register");
                assertTrue(registered.await(5, TimeUnit.SECONDS));
                assertEquals(0, callbacks.get(), "settled callback ran inline on registration");
                assertEquals(1L, callbackRan.getCount(),
                        "callback bypassed the active actor physical lease");
            } finally {
                releaseReceive.countDown();
            }
            assertTrue(callbackRan.await(5, TimeUnit.SECONDS), "deferred callback was lost");
            assertNull(violation.get(), () -> String.valueOf(violation.get()));
            assertEquals(1, callbacks.get());
            actor.cancel();
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
