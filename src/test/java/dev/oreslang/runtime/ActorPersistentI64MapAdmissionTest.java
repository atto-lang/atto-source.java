package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Regression: host state scopes may never queue a carrier on monitor entry. */
final class ActorPersistentI64MapAdmissionTest {
    @Test
    void foreignCallerFailsPromptlyWhileCriticalScopeIsHeld() throws Exception {
        assumeTrue(ExplicitNativeI64Region.isAvailable());
        var map = new AtomicReference<ActorPersistentI64Map>();
        var failure = new AtomicReference<Throwable>();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                try {
                    var arena = context.actorOwnedMemory().orElseThrow().newActorArena(24, 1);
                    var state = arena.newPersistentI64Map(8);
                    map.set(state);
                    state.critical(view -> {
                        entered.countDown();
                        try {
                            if (!release.await(15, TimeUnit.SECONDS)) {
                                throw new AssertionError("test critical scope timed out");
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError("test actor interrupted", interrupted);
                        }
                        view.put(1L, 42L);
                        return null;
                    });
                } catch (Throwable problem) {
                    failure.set(problem);
                } finally {
                    finished.countDown();
                }
            });
            actor.send("start");
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            try {
                long start = System.nanoTime();
                assertThrows(IllegalStateException.class,
                        () -> map.get().critical(view -> view.size()),
                        "foreign host callers must not acquire actor state");
                assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2),
                        "a foreign caller must be rejected before waiting on a critical monitor");
                start = System.nanoTime();
                assertFalse(map.get().closed(),
                        "reading retirement status must not park on a held critical scope");
                assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2),
                        "closed() must be lock free");
            } finally {
                release.countDown();
            }
            assertTrue(finished.await(10, TimeUnit.SECONDS));
            if (failure.get() != null) fail(failure.get());
        }
    }
}
