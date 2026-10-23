package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Verify the actor Future ownership primitive used by the HTTP body-copy
 * bridge. This does not claim end-to-end native Oreslang guest HTTP support.
 */
final class ProxyActorOwnedFutureTest {
    @Test
    void killingOneActorCancelsOnlyItsOutstandingBodyCopyFuture() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var ready = new CountDownLatch(2);
            var aFuture = new AtomicReference<OresFuture<Integer>>();
            var bFuture = new AtomicReference<OresFuture<Integer>>();
            var upstreamA = new AtomicReference<CompletableFuture<Integer>>();
            var upstreamB = new AtomicReference<CompletableFuture<Integer>>();
            var a = runtime.<String>spawnShared(() -> (message, context) -> {
                CompletableFuture<Integer> stage = new CompletableFuture<>();
                upstreamA.set(stage);
                OresFuture<Integer> owned = OresFuture.from(stage);
                runtime.ownCurrentActorFuture(owned);
                aFuture.set(owned);
                ready.countDown();
            });
            var b = runtime.<String>spawnShared(() -> (message, context) -> {
                CompletableFuture<Integer> stage = new CompletableFuture<>();
                upstreamB.set(stage);
                OresFuture<Integer> owned = OresFuture.from(stage);
                runtime.ownCurrentActorFuture(owned);
                bFuture.set(owned);
                ready.countDown();
            });
            a.send("start");
            b.send("start");
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            assertNotNull(aFuture.get());
            assertNotNull(bFuture.get());
            a.stop();
            assertTrue(a.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(aFuture.get().isCancelled(), "actor A must revoke its own pending I/O");
            assertTrue(upstreamA.get().isCancelled(), "A's HTTP CompletionStage must be canceled");
            assertFalse(bFuture.get().isDone(), "actor B's I/O must not be canceled with A");
            assertFalse(upstreamB.get().isDone(), "B's upstream must remain untouched");
            assertTrue(upstreamB.get().complete(42));
            assertEquals(42, bFuture.get().get(5, TimeUnit.SECONDS));
            b.stop();
            assertTrue(b.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
