package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
final class ActorFailureNoticeTest {
    @Test
    void hostFailurePublishesDataOnlyNoticeWithoutInvokingThrowableMethods() throws Exception {
        AtomicInteger unauthorizedCalls = new AtomicInteger();
        final class HostileFailure extends IllegalStateException {
            @Override public String getMessage() {
                unauthorizedCalls.incrementAndGet();
                throw new AssertionError("getMessage must not be called by failure notice");
            }
            @Override public synchronized Throwable getCause() {
                unauthorizedCalls.incrementAndGet();
                throw new AssertionError("getCause must not be called by failure notice");
            }
            @Override public String toString() {
                unauthorizedCalls.incrementAndGet();
                throw new AssertionError("toString must not be called by failure notice");
            }
        }

        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<String> actor =
                    runtime.spawnShared(() -> { throw new HostileFailure(); });
            assertThrows(ExecutionException.class,
                    () -> actor.ready().get(5, TimeUnit.SECONDS));
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));

            ActorRuntime.ActorFailureNotice notice = actor.failureNotice().orElseThrow();
            assertEquals(actor.id(), notice.actorId());
            assertEquals(actor.kind(), notice.actorKind());
            assertTrue(notice.generation() > 0);
            assertEquals(ActorRuntime.ActorFailureCategory.HOST_FAILURE, notice.category());
            assertTrue(notice.guestError().isEmpty(),
                    "a host Throwable is not a guest Error or transportable object");
            assertSame(notice, actor.failureNotice().orElseThrow(),
                    "first failure receipt is immutable and published exactly once");
            assertEquals(0, unauthorizedCalls.get(),
                    "building the notice must never dispatch arbitrary Throwable methods");
        }
    }

    @Test
    void structuredCancellationIsDistinctFromHostFailureAndGracefulStop() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<String> cancelled =
                    runtime.spawnShared(() -> (message, context) -> { });
            assertTrue(cancelled.cancel());
            assertTrue(cancelled.awaitTermination(5, TimeUnit.SECONDS));
            ActorRuntime.ActorFailureNotice cancellation = cancelled.failureNotice().orElseThrow();
            assertEquals(ActorRuntime.ActorFailureCategory.STRUCTURED_CANCELLATION,
                    cancellation.category());
            assertTrue(cancellation.guestError().isEmpty());

            ActorRuntime.ActorRef<String> normal =
                    runtime.spawnShared(() -> (message, context) -> { });
            normal.stop();
            assertTrue(normal.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(normal.failureNotice().isEmpty(),
                    "a graceful stop must never masquerade as a supervisor failure");
        }
    }

    @Test
    void finalCleanupFailureUsesSameSingleReceiptPublication() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<String> actor = runtime.spawnSharedTrusted(
                    context -> (message, turn) -> turn.runtime()
                            .endCurrentActorWithCleanup(
                                    () -> { throw new IllegalStateException("cleanup failed"); }));
            actor.ready().get(5, TimeUnit.SECONDS);
            actor.send("shutdown");
            assertThrows(ExecutionException.class,
                    () -> actor.done().get(5, TimeUnit.SECONDS));
            ActorRuntime.ActorFailureNotice receipt = actor.failureNotice().orElseThrow();
            assertEquals(ActorRuntime.ActorFailureCategory.HOST_FAILURE, receipt.category());
            assertSame(receipt, actor.failureNotice().orElseThrow());
            assertTrue(receipt.guestError().isEmpty());
        }
    }

    @Test
    void actorCannotInspectItsOwnSupervisorReceipt() throws Exception {
        CountDownLatch rejected = new CountDownLatch(1);
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<String> actor =
                    runtime.spawnShared(() -> (message, context) -> {
                        assertThrows(SecurityException.class,
                                () -> context.self().failureNotice());
                        rejected.countDown();
                        context.self().stop();
                    });
            actor.send("verify");
            assertTrue(rejected.await(5, TimeUnit.SECONDS));
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(actor.failureNotice().isEmpty());
        }
    }

    @Test
    void actorIncarnationIsFencedByMonotonicRuntimeGeneration() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<String> first = runtime.spawnShared(() -> {
                throw new IllegalStateException("first");
            });
            ActorRuntime.ActorRef<String> second = runtime.spawnShared(() -> {
                throw new IllegalStateException("second");
            });
            assertThrows(ExecutionException.class,
                    () -> first.ready().get(5, TimeUnit.SECONDS));
            assertThrows(ExecutionException.class,
                    () -> second.ready().get(5, TimeUnit.SECONDS));
            assertTrue(first.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(second.awaitTermination(5, TimeUnit.SECONDS));
            var a = first.failureNotice().orElseThrow();
            var b = second.failureNotice().orElseThrow();
            assertNotEquals(a.actorId(), b.actorId());
            assertTrue(b.generation() > a.generation(),
                    "new actor incarnations cannot reuse prior generations");
        }
    }
}
