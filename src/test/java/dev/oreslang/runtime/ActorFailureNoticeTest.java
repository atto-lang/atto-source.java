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
    void supervisorNoticeDiagnosticIsBoundedOnUtf8BoundaryForBothKinds() throws Exception {
        String longUnicodeDiagnostic = "🚨".repeat(300) + "tail";
        var direct = new ActorRuntime.ActorFailureNotice(
                ActorRuntime.ActorId.create(),
                ActorRuntime.ActorFailureKind.RAISE,
                longUnicodeDiagnostic);
        assertEquals("🚨".repeat(256), direct.summary(),
                "an actor cannot forge an unbounded parent CONTROL diagnostic");
        assertEquals(1024,
                direct.summary().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);

        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(
                    () -> (message, context) -> {
                        throw new OresPanic(longUnicodeDiagnostic);
                    });
            actor.send("crash");
            var signal = actor.failureNotice().get(5, TimeUnit.SECONDS);
            assertEquals(ActorRuntime.ActorFailureKind.PANIC, signal.kind());
            assertEquals("🚨".repeat(256), signal.summary());
            assertTrue(signal.summary().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 1024);
        }
    }

    @Test
    void supervisorNoticeKeepsShortMultilingualDiagnosticUnchanged() {
        String diagnostic = "actor invariant: café — 状态";
        var notice = new ActorRuntime.ActorFailureNotice(
                ActorRuntime.ActorId.create(),
                ActorRuntime.ActorFailureKind.PANIC,
                diagnostic);
        assertEquals(diagnostic, notice.summary());
    }

    @Test
    void malformedUtf16CannotEscapeInAControlNotice() throws Exception {
        // Java permits isolated UTF-16 surrogates in String. CONTROL messages
        // must contain well-formed Unicode, matching native invalid-UTF-8
        // replacement policy rather than copying malformed code units.
        String malformed = "\uD83D" + "🚨" + "\uDE80";
        var direct = new ActorRuntime.ActorFailureNotice(
                ActorRuntime.ActorId.create(),
                ActorRuntime.ActorFailureKind.PANIC,
                malformed);
        assertEquals("?🚨?", direct.summary());
        assertEquals(6, direct.summary().getBytes(
                java.nio.charset.StandardCharsets.UTF_8).length);

        var boundary = new ActorRuntime.ActorFailureNotice(
                ActorRuntime.ActorId.create(),
                ActorRuntime.ActorFailureKind.RAISE,
                "x".repeat(1023) + "\uD83D" + "🚨");
        assertEquals("x".repeat(1023) + "?", boundary.summary());
        assertEquals(1024, boundary.summary().getBytes(
                java.nio.charset.StandardCharsets.UTF_8).length);

        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(
                    () -> (message, context) -> {
                        throw new OresPanic(malformed);
                    });
            actor.send("crash");
            assertEquals("?🚨?",
                    actor.failureNotice().get(5, TimeUnit.SECONDS).summary());
        }
    }

    @Test
    void genericFutureCancellationDoesNotMasqueradeAsActorThrow() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(
                    () -> (message, context) -> {
                        throw new java.util.concurrent.CancellationException("cancelled");
                    });
            actor.send("cancel");
            try {
                actor.done().get(5, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException
                    | java.util.concurrent.CancellationException expected) {
                // The actor terminates, but no unhandled THROW notice is emitted.
            }
            assertTrue(actor.done().isDone());
            assertFalse(actor.failureNotice().isDone(),
                    "a cancellation control effect is not an ordinary throw");
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
