package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * A host-visible termination wait is a physical retirement barrier, not just
 * the beginning of actor cleanup. In particular, the actor cell can be marked
 * finalized and removed from the registry before its done notification is
 * published. Neither event alone may satisfy awaitTermination().
 */
final class ActorTerminationRetirementBarrierTest {

    @Test
    void awaitTerminationWaitsForDoneAndMessageAccountingRetirement() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            // Repeated short-lived actors expose the deregistration versus
            // terminal-notification race without sleeps or scheduler parking.
            for (int iteration = 0; iteration < 32; iteration++) {
                ActorRuntime.ActorRef<Integer> actor = runtime.spawnShared(
                        () -> (message, context) -> { });
                actor.send(iteration);
                actor.stop();

                assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS),
                        "actor retirement deadline exceeded on iteration " + iteration);
                assertTrue(actor.done().isDone(),
                        "awaitTermination returned before get_done settled");
                assertFalse(actor.isAlive(),
                        "awaitTermination returned before actor retirement");
                assertEquals(0L, runtime.sharedMemoryBytes(),
                        "awaitTermination returned before shared message memory was released");
            }
        }
    }
}
