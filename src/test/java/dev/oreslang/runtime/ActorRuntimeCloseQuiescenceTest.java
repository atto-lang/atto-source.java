package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Physical quiescence must precede final memory/stream/group teardown.
 * The deliberately noncooperative host turn is an injected test stand-in,
 * not an acceptable Oreslang trusted-actor behavior.
 */
@Timeout(15)
final class ActorRuntimeCloseQuiescenceTest {
    private static volatile CountDownLatch entered;
    private static volatile CountDownLatch release;

    @Test
    void timedOutCloseRetainsActorUntilCarrierExitsAndRetryCompletes() throws Exception {
        entered = new CountDownLatch(1);
        release = new CountDownLatch(1);
        ActorRuntime runtime = new ActorRuntime();
        try {
            ActorRuntime.ActorRef<Integer> actor = runtime.spawnShared(
                    () -> (message, context) -> {
                        entered.countDown();
                        boolean interrupted = false;
                        while (true) {
                            try {
                                if (release.await(10, TimeUnit.MILLISECONDS)) break;
                            } catch (InterruptedException ignored) {
                                // Explicitly simulate a host callback ignoring
                                // interruption after shutdownNow. Cancellation
                                // does NOT confer physical finalization.
                                interrupted = true;
                            }
                        }
                        if (interrupted) Thread.currentThread().interrupt();
                    });
            actor.send(1);
            assertTrue(entered.await(3, TimeUnit.SECONDS));

            IllegalStateException incomplete = assertThrows(
                    IllegalStateException.class, runtime::close);
            assertTrue(incomplete.getMessage().contains("did not observe full actor termination"));
            assertFalse(actor.done().isDone(),
                    "an active physical carrier must not have a completed actor DONE");

            release.countDown();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(actor.done().isDone());
            // A retry after physical retirement can safely release retained
            // runtime resources. First close must not have zeroed them early.
            runtime.close();
        } finally {
            release.countDown();
            try { runtime.close(); } catch (IllegalStateException ignored) { }
        }
    }
}
