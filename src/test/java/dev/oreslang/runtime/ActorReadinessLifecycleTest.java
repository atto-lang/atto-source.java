package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
final class ActorReadinessLifecycleTest {

    @Test
    void methodLifecycleObserversAreIdenticalToExistingRuntimeFutures() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            AtomicInteger starts = new AtomicInteger();
            ActorRuntime.ActorRef<String> ref = runtime.spawnPrivate(() -> {
                starts.incrementAndGet();
                return (message, context) -> { };
            });

            assertSame(ref.ready(), ref.get_ready(),
                    "get_ready must return the runtime-owned readiness ticket");
            assertSame(ref.done(), ref.get_done(),
                    "get_done must return the physical finalization ticket");
            assertSame(ref.shutdownResult(), ref.get_shutdown_result(),
                    "shutdown result must be distinct from physical done");
            assertNotSame(ref.done(), ref.shutdownResult());
            assertFalse(ref.get_shutdown_result().cancel(false),
                    "shutdown outcome is a runtime-owned read-only observer");
            assertFalse(ref.get_ready().cancel(true),
                    "get_ready cannot be used as actor cancellation authority");
            ref.get_ready().get(5, TimeUnit.SECONDS);
            assertEquals(1, starts.get());
            assertFalse(ref.get_done().isDone(),
                    "initialization must not fabricate terminal completion");

            ref.stop();
            ref.get_done().get(5, TimeUnit.SECONDS);
            ref.get_shutdown_result().get(5, TimeUnit.SECONDS);
            assertFalse(ref.isAlive());
            assertFalse(ref.get_done().cancel(true),
                    "get_done is an observer, not a stop or cancellation capability");
        }
    }

    @Test
    void readyInitializesActorWithoutAnInitialMailboxMessage() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            AtomicInteger created = new AtomicInteger();
            ActorRuntime.ActorRef<String> ref = runtime.spawnPrivate(
                    () -> {
                        created.incrementAndGet();
                        return (message, context) -> { };
                    });

            assertFalse(ref.ready().cancel(false),
                    "guest observers must not cancel runtime-owned readiness");
            ref.ready().get(5, TimeUnit.SECONDS);
            assertEquals(1, created.get());
            assertTrue(ref.isAlive());

            ref.stop();
            ref.done().get(5, TimeUnit.SECONDS);
            assertFalse(ref.isAlive());
            assertFalse(ref.done().cancel(false),
                    "a finalized observer cannot change actor lifecycle state");
        }
    }

    @Test
    void stopBeforeStartFailsReadyAndCompletesDone() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<String> ref = runtime.spawnPrivate(
                    () -> (message, context) -> { });

            ref.stop();
            ExecutionException notReady = assertThrows(
                    ExecutionException.class,
                    () -> ref.ready().get(5, TimeUnit.SECONDS));
            assertInstanceOf(
                    ActorRuntime.ActorTerminatedException.class,
                    notReady.getCause());
            ref.done().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void initializationFailurePropagatesToReadyAndDone() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<String> ref = runtime.spawnPrivate(
                    () -> { throw new IllegalStateException("constructor failed"); });

            ExecutionException ready = assertThrows(
                    ExecutionException.class,
                    () -> ref.ready().get(5, TimeUnit.SECONDS));
            assertEquals("constructor failed", ready.getCause().getMessage());

            ExecutionException done = assertThrows(
                    ExecutionException.class,
                    () -> ref.done().get(5, TimeUnit.SECONDS));
            assertEquals("constructor failed", done.getCause().getMessage());
        }
    }

    @Test
    void readyDoesNotExposeMutableFutureCompletionToCallers() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<Integer> ref = runtime.spawnShared(
                    () -> (message, context) -> { });

            assertFalse(ref.ready().cancel(true));
            assertFalse(ref.done().cancel(true));
            ref.ready().get(5, TimeUnit.SECONDS);
            ref.stop();
            ref.done().get(5, TimeUnit.SECONDS);
        }
    }
}
