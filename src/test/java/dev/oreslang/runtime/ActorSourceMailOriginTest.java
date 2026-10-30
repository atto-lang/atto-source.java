package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real actor-mailbox SourceTask admission, not a synthetic trace:
 * the same TaskRunner must retain immutable message provenance across an
 * await resolved on a foreign producer thread. No HTTP server is required
 * to prove the scheduler handoff; NativeHttpTest verifies Exchange ownership.
 */
@Timeout(30)
final class ActorSourceMailOriginTest {

    @Test
    void sourceTaskKeepsItsAdmittedMailOriginAcrossProducerCompletion() throws Exception {
        CountDownLatch firstTurn = new CountDownLatch(1);
        OresFuture<Integer> gate = new OresFuture<>();
        AtomicInteger resumptions = new AtomicInteger();
        AtomicReference<ActorRuntime.SourceMailOrigin> initialOrigin = new AtomicReference<>();
        AtomicReference<ExecutionContext.ExecutionId> initialExecution = new AtomicReference<>();
        AtomicReference<OresFuture<Integer>> taskCompletion = new AtomicReference<>();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();

        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(3, 1, 8, 16))) {
            var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                try {
                    assertEquals("request", message);
                    OresFuture<Integer> source = context.runtime().startActorTask(resume -> {
                        ActorRuntime.SourceMailOrigin origin =
                                ActorRuntime.captureSourceMailOrigin();
                        assertNotNull(origin, "source task lost admitted mail origin");
                        assertSame(runtime, origin.runtime());
                        assertEquals(context.self().id(), origin.actorId());
                        assertEquals(OresScheduler.currentExecutionContext().id(),
                                initialExecution.get() == null
                                        ? OresScheduler.currentExecutionContext().id()
                                        : initialExecution.get());

                        if (resumptions.getAndIncrement() == 0) {
                            assertTrue(resume.initial());
                            initialOrigin.set(origin);
                            initialExecution.set(OresScheduler.currentExecutionContext().id());
                            firstTurn.countDown();
                            return OresScheduler.await(gate);
                        }
                        assertFalse(resume.initial());
                        assertEquals(7, resume.value());
                        assertEquals(initialOrigin.get(), origin,
                                "mailbox sequence must be the same across await");
                        assertEquals(initialExecution.get(),
                                OresScheduler.currentExecutionContext().id(),
                                "logical task identity must persist across carrier changes");
                        return OresScheduler.done(42);
                    });
                    taskCompletion.set(source);
                    context.runtime().suspendCurrentUserMailboxUntil(source);
                } catch (Throwable failure) {
                    unexpected.set(failure);
                    throw failure;
                }
            });

            actor.send("request");
            assertTrue(firstTurn.await(10, TimeUnit.SECONDS),
                    "first source task did not cross authenticated mailbox admission");

            AtomicReference<ActorRuntime.SourceMailOrigin> foreignOrigin =
                    new AtomicReference<>();
            Thread producer = Thread.ofPlatform().start(() -> {
                foreignOrigin.set(ActorRuntime.captureSourceMailOrigin());
                gate.completeFromRuntime(7);
            });
            producer.join(10_000);
            assertFalse(producer.isAlive());
            assertNull(foreignOrigin.get(),
                    "producer completion must not inherit actor/mailbox identity");

            assertNotNull(taskCompletion.get());
            assertEquals(42, taskCompletion.get().get(10, TimeUnit.SECONDS));
            assertEquals(2, resumptions.get());
            assertNull(unexpected.get(), () -> "actor handler failed: " + unexpected.get());
            assertNull(ActorRuntime.captureSourceMailOrigin(),
                    "test harness must not inherit a source task's request authority");
            actor.stop();
            assertTrue(actor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }
}
