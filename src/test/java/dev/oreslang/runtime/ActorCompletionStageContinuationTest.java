package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression coverage for the host CompletionStage-to-actor mailbox adapter.
 * A producer may synchronously fire CompletionStage callbacks, but it must
 * never execute the actor-owned guest continuation on that producer thread.
 */
final class ActorCompletionStageContinuationTest {
    @Test
    void alreadySettledCompletionWaitsForReceiveTurnToUnwind() throws Exception {
        var entered = new CountDownLatch(1);
        var resumed = new CountDownLatch(1);
        var insideReceive = new AtomicBoolean();
        var failure = new AtomicReference<Throwable>();
        var calls = new CopyOnWriteArrayList<String>();
        try (var runtime = new ActorRuntime()) {
            var actor = runtime.<Integer>spawn(() -> (message, context) -> {
                insideReceive.set(true);
                try {
                    calls.add("receive-before");
                    runtime.enqueueOnCompletion(
                            CompletableFuture.completedFuture(message * 2),
                            runtime.captureCurrentContinuationTarget(),
                            (value, error) -> {
                                try {
                                    assertTrue(ActorRuntime.inActorExecution());
                                    assertTrue(runtime.currentThreadOwnsActorExecutionLease());
                                    assertEquals(context.self().id(),
                                            ActorRuntime.currentActorId().orElseThrow());
                                    assertFalse(insideReceive.get(),
                                            "already-settled host stage must never run inline");
                                    assertNull(error);
                                    assertEquals(42, value);
                                    calls.add("callback");
                                } catch (Throwable thrown) {
                                    failure.set(thrown);
                                } finally {
                                    resumed.countDown();
                                }
                            });
                    calls.add("receive-after");
                } finally {
                    insideReceive.set(false);
                    entered.countDown();
                }
            });
            actor.send(21);
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertTrue(resumed.await(3, TimeUnit.SECONDS));
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            assertEquals(List.of("receive-before", "receive-after", "callback"), calls);
        }
    }

    @Test
    void pendingStageSettledByForeignProducerResumesOnActorCarrier() throws Exception {
        var registered = new CountDownLatch(1);
        var completed = new CountDownLatch(1);
        var stage = new CompletableFuture<Integer>();
        var producerThread = new AtomicReference<Thread>();
        var failure = new AtomicReference<Throwable>();
        try (var runtime = new ActorRuntime()) {
            var actor = runtime.<Integer>spawn(() -> (message, context) -> {
                runtime.enqueueOnCompletion(
                        stage, runtime.captureCurrentContinuationTarget(),
                        (value, error) -> {
                            try {
                                assertTrue(ActorRuntime.inActorExecution());
                                assertTrue(runtime.currentThreadOwnsActorExecutionLease());
                                assertNotEquals(producerThread.get(), Thread.currentThread());
                                assertEquals(context.self().id(),
                                        ActorRuntime.currentActorId().orElseThrow());
                                assertEquals(9, value);
                                assertNull(error);
                            } catch (Throwable thrown) {
                                failure.set(thrown);
                            } finally {
                                completed.countDown();
                            }
                        });
                registered.countDown();
            });
            actor.send(1);
            assertTrue(registered.await(3, TimeUnit.SECONDS));
            Thread producer = new Thread(() -> {
                producerThread.set(Thread.currentThread());
                stage.complete(9);
            }, "host-producer-not-actor");
            producer.start();
            producer.join(3000);
            assertFalse(producer.isAlive());
            assertTrue(completed.await(3, TimeUnit.SECONDS));
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
        }
    }

    @Test
    void exceptionallyCompletedStageDeliversFailureOnlyOnOwningActorLane() throws Exception {
        var ready = new CountDownLatch(1);
        var observed = new CountDownLatch(1);
        var stage = new CompletableFuture<Integer>();
        var producer = new AtomicReference<Thread>();
        var callbackError = new AtomicReference<Throwable>();
        var reason = new IllegalStateException("host stage failed");
        try (var runtime = new ActorRuntime()) {
            var actor = runtime.<Integer>spawn(() -> (message, context) -> {
                runtime.enqueueOnCompletion(
                        stage, runtime.captureCurrentContinuationTarget(),
                        (value, failure) -> {
                            try {
                                assertTrue(runtime.currentThreadOwnsActorExecutionLease());
                                assertNotEquals(producer.get(), Thread.currentThread());
                                assertEquals(context.self().id(),
                                        ActorRuntime.currentActorId().orElseThrow());
                                assertNull(value);
                                assertSame(reason, failure);
                            } catch (Throwable err) {
                                callbackError.set(err);
                            } finally {
                                observed.countDown();
                            }
                        });
                ready.countDown();
            });
            actor.send(1);
            assertTrue(ready.await(3, TimeUnit.SECONDS));
            Thread failingProducer = new Thread(() -> {
                producer.set(Thread.currentThread());
                stage.completeExceptionally(reason);
            }, "host-error-producer");
            failingProducer.start();
            failingProducer.join(3000);
            assertFalse(failingProducer.isAlive());
            assertTrue(observed.await(3, TimeUnit.SECONDS));
            assertNull(callbackError.get(), () -> String.valueOf(callbackError.get()));
        }
    }

    @Test
    void foreignRuntimeCannotRetargetCapturedActorContinuation() throws Exception {
        var ready = new CountDownLatch(1);
        var captured = new AtomicReference<ActorRuntime.ContinuationTarget>();
        try (var first = new ActorRuntime(); var second = new ActorRuntime()) {
            var actor = first.<Integer>spawn(() -> (message, context) -> {
                captured.set(first.captureCurrentContinuationTarget());
                ready.countDown();
            });
            actor.send(1);
            assertTrue(ready.await(3, TimeUnit.SECONDS));
            assertThrows(SecurityException.class, () ->
                    second.enqueueOnCompletion(
                            CompletableFuture.completedFuture(4),
                            captured.get(), (value, error) -> fail("wrong runtime")));
        }
    }

    @Test
    void stoppedActorDropsLateHostStageWithoutRunningGuestCallback() throws Exception {
        var ready = new CountDownLatch(1);
        var callbackCount = new AtomicInteger();
        var stage = new CompletableFuture<Integer>();
        try (var runtime = new ActorRuntime()) {
            var actor = runtime.<Integer>spawn(() -> (message, context) -> {
                runtime.enqueueOnCompletion(
                        stage, runtime.captureCurrentContinuationTarget(),
                        (value, error) -> callbackCount.incrementAndGet());
                ready.countDown();
            });
            actor.send(1);
            assertTrue(ready.await(3, TimeUnit.SECONDS));
            actor.stop();
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            stage.complete(50);
            assertEquals(0, callbackCount.get());
        }
    }
}
