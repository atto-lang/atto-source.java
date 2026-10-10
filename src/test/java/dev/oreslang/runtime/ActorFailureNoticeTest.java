package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
final class ActorFailureNoticeTest {

    @Test
    void panicSignalsSupervisorWithoutUsingOrdinaryMailOrKillingOtherActors() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch survivorObserved = new CountDownLatch(1);
            var survivor = runtime.<String>spawnShared(
                    () -> (message, actor) -> survivorObserved.countDown());
            var failed = runtime.<String>spawnShared(
                    () -> (message, actor) -> { throw new OresPanic("actor invariant"); });

            assertFalse(failed.failureNotice().cancel(false),
                    "guest code must not cancel runtime-owned failure notices");
            failed.send("crash");
            ActorRuntime.ActorFailureNotice notice =
                    failed.failureNotice().get(5, TimeUnit.SECONDS);
            assertEquals(failed.id(), notice.actorId());
            assertEquals(ActorRuntime.ActorFailureKind.PANIC, notice.kind());
            assertEquals("actor invariant", notice.summary());
            assertFalse(failed.isAlive());
            assertTrue(failed.failureNotice().isDone(),
                    "supervisor signal must be independent of the data mailbox");

            survivor.send("unaffected");
            assertTrue(survivorObserved.await(5, TimeUnit.SECONDS),
                    "an actor panic must not terminate an unrelated actor or runtime");
            assertTrue(survivor.isAlive());
        }
    }

    @Test
    void raisesAndOrdinaryErrorsHaveDistinctSupervisorTypes() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var raised = runtime.<String>spawnShared(
                    () -> (message, actor) -> { throw new OresRaised("escalated"); });
            var thrown = runtime.<String>spawnShared(
                    () -> (message, actor) -> { throw new IllegalStateException("ordinary"); });

            raised.send("go");
            thrown.send("go");

            assertEquals(ActorRuntime.ActorFailureKind.RAISE,
                    raised.failureNotice().get(5, TimeUnit.SECONDS).kind());
            assertEquals(ActorRuntime.ActorFailureKind.THROW,
                    thrown.failureNotice().get(5, TimeUnit.SECONDS).kind());
        }
    }

    @Test
    void ordinaryStopDoesNotFabricateASupervisorPanic() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(
                    () -> (message, context) -> { });
            actor.ready().get(5, TimeUnit.SECONDS);
            actor.stop();
            actor.done().get(5, TimeUnit.SECONDS);
            assertFalse(actor.failureNotice().isDone(),
                    "normal stop is not a panic/raise/throw event");
        }
    }
}
