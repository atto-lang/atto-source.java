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
}
