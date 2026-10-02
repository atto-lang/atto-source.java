package dev.oreslang;

import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.AwaitSupport;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

final class AwaitSupportTest {
    @Test void awaitsCompletionStages() {
        assertEquals(42, AwaitSupport.await(CompletableFuture.completedFuture(42)));
    }

    @Test void awaitsFutureResultsProducedByVirtualThreads() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> future = executor.submit(() -> "virtual-result");
            assertEquals("virtual-result", AwaitSupport.await(future));
        }
    }

    @Test void awaitsVirtualThreadCompletion() throws Exception {
        CountDownLatch ran = new CountDownLatch(1);
        Thread thread = Thread.startVirtualThread(ran::countDown);
        assertNull(AwaitSupport.await(thread));
        assertTrue(ran.await(1, TimeUnit.SECONDS));
    }

    @Test void rejectsUnstartedThreads() {
        Thread thread = Thread.ofVirtual().unstarted(() -> { });
        assertThrows(IllegalArgumentException.class, () -> AwaitSupport.await(thread));
    }

    @Test void pendingAwaitFailsClosedInsideActorTurn() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CompletableFuture<Integer> pending = new CompletableFuture<>();
            var ref = runtime.<String>spawnPrivateTrusted(context -> (message, turn) ->
                    AwaitSupport.await(pending));
            ref.send("go");
            assertTrue(ref.awaitTermination(2, TimeUnit.SECONDS));
            Throwable failure = ref.failure().orElseThrow();
            assertInstanceOf(IllegalStateException.class, failure);
            assertTrue(failure.getMessage().contains("would block an actor dispatcher carrier"));
        }
    }

    @Test void alreadyCompletedAwaitIsAllowedInsideActorTurn() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch received = new CountDownLatch(1);
            var ref = runtime.<String>spawnPrivateTrusted(context -> (message, turn) -> {
                assertEquals(7, AwaitSupport.await(CompletableFuture.completedFuture(7)));
                received.countDown();
            });
            ref.send("go");
            assertTrue(received.await(2, TimeUnit.SECONDS));
            assertTrue(ref.isAlive());
        }
    }

    @Test
    void synchronousValuesPassThroughWithIdentity() {
        Object value = new Object();
        assertSame(value, AwaitSupport.await(value));
        assertEquals(9, AwaitSupport.await(9));
    }

    @Test
    void customAwaitablesUseTheirCompletionStage() {
        AwaitSupport.Awaitable<String> awaitable =
                () -> CompletableFuture.completedFuture("custom");
        assertEquals("custom", AwaitSupport.await(awaitable));
    }

    @Test
    void completionWrappersExposeUnderlyingRuntimeFailure() {
        CompletableFuture<String> failed = new CompletableFuture<>();
        IllegalStateException cause = new IllegalStateException("boom");
        failed.completeExceptionally(cause);

        IllegalStateException observed = assertThrows(
                IllegalStateException.class,
                () -> AwaitSupport.await(failed));
        assertSame(cause, observed);
    }

    @Test
    void pendingVirtualThreadAwaitFailsClosedInsideActorTurn() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch release = new CountDownLatch(1);
            Thread virtual = Thread.startVirtualThread(() -> {
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });

            var ref = runtime.<String>spawnPrivateTrusted(context -> (message, turn) ->
                    AwaitSupport.await(virtual));
            ref.send("go");

            assertTrue(ref.awaitTermination(2, TimeUnit.SECONDS));
            Throwable failure = ref.failure().orElseThrow();
            assertInstanceOf(IllegalStateException.class, failure);
            assertTrue(failure.getMessage().contains("virtual thread"));
            release.countDown();
            virtual.join();
        }
    }

}
