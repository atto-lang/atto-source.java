package dev.oreslang.runtime;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A stopped or foreign continuation target has no authority to take ownership
 * of a producer Future. Validation must precede ownFuture(), not just queueing.
 */
@Timeout(15)
final class ActorContinuationFutureOwnershipTest {
    @Test
    void stoppedActorTargetDoesNotCancelIndependentFutureBeforeControlAdmission()
            throws Exception {
        CountDownLatch captured = new CountDownLatch(1);
        CountDownLatch leaveCarrier = new CountDownLatch(1);
        AtomicReference<ActorRuntime.ContinuationTarget> target = new AtomicReference<>();
        AtomicInteger callbacks = new AtomicInteger();

        try (var store = new SharedCodeImageStore();
             var runtime = new ActorRuntime(IsolatePolicy.developer(),
                     new ActorRuntime.DispatcherConfig(2, 2, 1, 16))) {
            var image = store.publish("future-ownership.ores", Parser.parse("""
                    define actor Worker as
                        receive(ActorMail<String> mail): void { return; }
                    end
                    pub routine main(): void { return; }
                    """));
            var executor = new ActorRuntime.ActorCodeExecutor() {
                @Override public SharedCodeImageStore.CodeImage codeImage() {
                    return image;
                }
                @Override public ActorRuntime.ActorOwnedGuestState initializeActor(
                        String type, ActorRuntime.ActorContext<Object> context) {
                    return context.self()::id;
                }
                @Override public void receiveActor(
                        String type,
                        ActorRuntime.ActorOwnedGuestState state,
                        ActorRuntime.ActorInboxMail<Object> mail,
                        ActorRuntime.ActorContext<Object> context) throws Exception {
                    target.set(runtime.captureCurrentContinuationTarget());
                    captured.countDown();
                    assertTrue(leaveCarrier.await(5, TimeUnit.SECONDS),
                            "test must keep original actor cell alive during stop");
                }
            };

            var actor = runtime.spawnCodeActor(ActorRuntime.ActorKind.SHARED,
                    executor, "Worker");
            try {
                actor.ready().get(3, TimeUnit.SECONDS);
                actor.send("capture");
                assertTrue(captured.await(3, TimeUnit.SECONDS));
                assertNotNull(target.get());

                // The first producer is legitimately adopted before stop.
                // Stopping the actor must cancel exactly that owned Future.
                OresFuture<Integer> owned = new OresFuture<>();
                runtime.enqueueOnCompletion(owned, target.get(),
                        (value, failure) -> callbacks.incrementAndGet());

                // The carrier is still inside the source receive, so the old
                // actor cell exists but is now stopped. A stale callback must
                // not cancel another Future it never legally owned.
                assertTrue(actor.cancel());
                assertTrue(owned.isCancelled(),
                        "a successfully registered pending Future must be canceled on stop");
                OresFuture<Integer> external = new OresFuture<>();
                runtime.enqueueOnCompletion(external, target.get(),
                        (value, failure) -> callbacks.incrementAndGet());
                assertFalse(external.isDone(),
                        "rejected continuation must not take ownership and cancel producer");
                external.completeFromRuntime(42);
                assertEquals(42, external.get(1, TimeUnit.SECONDS));
                assertEquals(0, callbacks.get(),
                        "revoked callback may not run on completion thread or any actor carrier");
            } finally {
                leaveCarrier.countDown();
                actor.cancel();
            }
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
        }
    }
}
