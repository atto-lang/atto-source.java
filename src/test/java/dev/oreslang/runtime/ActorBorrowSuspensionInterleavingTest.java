package dev.oreslang.runtime;

import dev.oreslang.parser.Parser;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Deterministic producer/actor-carrier interleavings for all actor kinds.
 * The counters and latches are HOST TEST instrumentation, never guest-visible.
 */
@Timeout(45)
final class ActorBorrowSuspensionInterleavingTest {
    private static final int CALLBACKS = 24;

    @Test void producerCompletionsCannotRunDuringActiveReceiveOrBeOvertakenByUserMail()
            throws Exception {
        for (ActorRuntime.ActorKind kind : ActorRuntime.ActorKind.values()) {
            AtomicInteger active = new AtomicInteger();
            AtomicInteger maximum = new AtomicInteger();
            AtomicInteger callbacks = new AtomicInteger();
            AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
            AtomicBoolean userMailOvertookContinuation = new AtomicBoolean();
            CountDownLatch registered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch callbacksDone = new CountDownLatch(CALLBACKS);
            CountDownLatch userMailDone = new CountDownLatch(1);
            List<OresFuture<Integer>> pending = new ArrayList<>();
            for (int i = 0; i < CALLBACKS; i++) pending.add(new OresFuture<>());

            try (var images = new SharedCodeImageStore();
                 var runtime = new ActorRuntime(IsolatePolicy.developer(),
                         new ActorRuntime.DispatcherConfig(4, 4, 1, 256));
                 var producers = Executors.newFixedThreadPool(4)) {
                var image = images.publish("loan-interleave.ores",
                        Parser.parse("pub routine main(): void { return; }"));
                var executor = new ActorRuntime.ActorCodeExecutor() {
                    @Override public SharedCodeImageStore.CodeImage codeImage() { return image; }
                    @Override public ActorRuntime.ActorOwnedGuestState initializeActor(
                            String type, ActorRuntime.ActorContext<Object> context) {
                        return context.self()::id;
                    }
                    @Override public void receiveActor(String type,
                            ActorRuntime.ActorOwnedGuestState state,
                            ActorRuntime.ActorInboxMail<Object> mail,
                            ActorRuntime.ActorContext<Object> context) throws Exception {
                        maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
                        try {
                            assertEquals(context.self().id(), ActorRuntime.currentActorId().orElseThrow());
                            if (mail.value().equals("register")) {
                                var target = runtime.captureCurrentContinuationTarget();
                                for (var future : pending) {
                                    runtime.enqueueOnCompletion(future, target, (value, error) -> {
                                        maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
                                        try {
                                            assertNull(error);
                                            assertEquals(7, value);
                                            assertEquals(context.self().id(),
                                                    ActorRuntime.currentActorId().orElseThrow());
                                            callbacks.incrementAndGet();
                                        } catch (Throwable failure) {
                                            callbackFailure.compareAndSet(null, failure);
                                        } finally {
                                            // A failed guest assertion must not turn
                                            // into a mysterious latch timeout.
                                            callbacksDone.countDown();
                                            active.decrementAndGet();
                                        }
                                    });
                                }
                                registered.countDown();
                                assertTrue(release.await(5, TimeUnit.SECONDS));
                            } else {
                                if (callbacks.get() != CALLBACKS) userMailOvertookContinuation.set(true);
                                userMailDone.countDown();
                                context.self().stop();
                            }
                        } finally { active.decrementAndGet(); }
                    }
                };
                var actor = runtime.spawnCodeActor(kind, executor, "LoanWorker");
                try {
                    actor.ready().get(5, TimeUnit.SECONDS);
                    actor.send("register");
                    assertTrue(registered.await(5, TimeUnit.SECONDS), kind.toString());
                    var tasks = new ArrayList<java.util.concurrent.Future<?>>();
                    for (var future : pending) tasks.add(producers.submit(() -> future.completeFromRuntime(7)));
                    for (var task : tasks) task.get(5, TimeUnit.SECONDS);
                    actor.send("probe");
                    assertEquals(0, callbacks.get(), kind + " completed on an active carrier");
                    release.countDown();

                    assertTrue(callbacksDone.await(10, TimeUnit.SECONDS), kind.toString());
                    assertTrue(userMailDone.await(10, TimeUnit.SECONDS), kind.toString());
                    assertTrue(actor.awaitTermination(10, TimeUnit.SECONDS));
                    assertTrue(actor.failure().isEmpty(), () -> actor.failure().toString());
                    assertFalse(userMailOvertookContinuation.get(), kind.toString());
                    assertNull(callbackFailure.get(),
                            () -> kind + " callback assertion failed: " + callbackFailure.get());
                    assertEquals(CALLBACKS, callbacks.get());
                    assertEquals(1, maximum.get(), kind + " overlapped guest execution");
                    assertEquals(0, active.get());
                } finally {
                    release.countDown();
                    actor.cancel();
                }
            }
        }
    }

    @Test void cancellationFencesLateCompletionsAndQueuedUserMail() throws Exception {
        for (ActorRuntime.ActorKind kind : ActorRuntime.ActorKind.values()) {
            CountDownLatch registered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger callbacks = new AtomicInteger();
            AtomicInteger userMessages = new AtomicInteger();
            List<OresFuture<Integer>> pending = new ArrayList<>();
            for (int i = 0; i < CALLBACKS; i++) pending.add(new OresFuture<>());

            try (var images = new SharedCodeImageStore();
                 var runtime = new ActorRuntime(IsolatePolicy.developer(),
                         new ActorRuntime.DispatcherConfig(4, 4, 1, 256))) {
                var image = images.publish("loan-cancel.ores",
                        Parser.parse("pub routine main(): void { return; }"));
                var executor = new ActorRuntime.ActorCodeExecutor() {
                    @Override public SharedCodeImageStore.CodeImage codeImage() { return image; }
                    @Override public ActorRuntime.ActorOwnedGuestState initializeActor(
                            String type, ActorRuntime.ActorContext<Object> context) {
                        return context.self()::id;
                    }
                    @Override public void receiveActor(String type,
                            ActorRuntime.ActorOwnedGuestState state,
                            ActorRuntime.ActorInboxMail<Object> mail,
                            ActorRuntime.ActorContext<Object> context) throws Exception {
                        if (mail.value().equals("register")) {
                            var target = runtime.captureCurrentContinuationTarget();
                            for (var future : pending) {
                                runtime.enqueueOnCompletion(future, target, (value, error) ->
                                        callbacks.incrementAndGet());
                            }
                            registered.countDown();
                            assertTrue(release.await(5, TimeUnit.SECONDS));
                        } else {
                            userMessages.incrementAndGet();
                        }
                    }
                };
                var actor = runtime.spawnCodeActor(kind, executor, "CancelledLoanWorker");
                try {
                    actor.ready().get(5, TimeUnit.SECONDS);
                    actor.send("register");
                    assertTrue(registered.await(5, TimeUnit.SECONDS), kind.toString());
                    actor.send("probe");
                    actor.cancel();
                    release.countDown();
                    assertTrue(actor.awaitTermination(10, TimeUnit.SECONDS));
                    // Inspect BEFORE completing the producer future. A future
                    // naturally discards its waiters on completion, so checking
                    // only afterwards could conceal a cancellation leak.
                    for (var future : pending) {
                        assertEquals(0, future.pendingRuntimeWaiterCount(),
                                kind + " retained callbacks after actor death");
                    }
                    for (var future : pending) future.completeFromRuntime(7);
                    assertEquals(0, callbacks.get(), kind.toString());
                    assertEquals(0, userMessages.get(), kind.toString());
                } finally {
                    release.countDown();
                    actor.cancel();
                }
            }
        }
    }
}
