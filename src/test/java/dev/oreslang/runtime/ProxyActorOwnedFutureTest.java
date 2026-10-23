package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
            var aCancellation = new AtomicInteger();
            var bCancellation = new AtomicInteger();
            var a = runtime.<String>spawnShared(() -> (message, context) -> {
                OresFuture<Integer> owned = new OresFuture<>(aCancellation::incrementAndGet);
                runtime.ownCurrentActorFuture(owned);
                aFuture.set(owned);
                ready.countDown();
            });
            var b = runtime.<String>spawnShared(() -> (message, context) -> {
                OresFuture<Integer> owned = new OresFuture<>(bCancellation::incrementAndGet);
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
            assertEquals(1, aCancellation.get(), "A cancellation hook must execute once");
            assertFalse(bFuture.get().isDone(), "actor B's I/O must not be canceled with A");
            assertEquals(0, bCancellation.get());
            assertTrue(bFuture.get().completeFromRuntime(42));
            assertEquals(42, bFuture.get().get(5, TimeUnit.SECONDS));
            b.stop();
            assertTrue(b.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
