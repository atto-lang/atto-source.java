package dev.oreslang;

import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.IsolatePolicy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

/**
 * AF02/AF03/AF04 host-runtime checks. These are not native AOT or source
 * guest parity receipts; a stopped actor must never admit a queued turn.
 */
@Timeout(20)
final class ActorFinalizationV1Test {
    @Test
    void actorSelfStopRejectsQueuedMessageAndDoesNotWaitOnItsOwnTurn() throws Exception {
        var config = new ActorRuntime.DispatcherConfig(1, 2, 32, 16);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger delivered = new AtomicInteger();
            AtomicBoolean exitedSelfStop = new AtomicBoolean();

            var ref = runtime.<Integer>spawnShared(() -> (message, context) -> {
                delivered.incrementAndGet();
                if (message == 1) {
                    entered.countDown();
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test could not release first turn");
                    }
                    context.self().stop();
                    exitedSelfStop.set(true);
                }
            });

            ref.send(1);
            assertTrue(entered.await(5, TimeUnit.SECONDS), "first turn not admitted");
            // The second message is accepted before the first turn calls stop.
            // A stop request must fence this queued work from dispatch.
            try {
                ref.send(2);
            } finally {
                release.countDown();
            }
            assertTrue(ref.awaitTermination(8, TimeUnit.SECONDS),
                    "actor self-stop failed to quiesce");
            assertTrue(exitedSelfStop.get(), "self-stop parked or lost the turn");
            assertEquals(1, delivered.get(),
                    "stopped actor executed a previously queued user turn");
            assertTrue(ref.failure().isEmpty(),
                    "normal self-stop unexpectedly reported a supervisor failure");
        }
    }

    @Test
    void selfStopNeverAuthorizesNewMailboxAdmission() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch stopped = new CountDownLatch(1);
            AtomicBoolean rejected = new AtomicBoolean();
            var ref = runtime.<Integer>spawnShared(() -> (message, context) -> {
                context.self().stop();
                try {
                    context.self().send(99);
                } catch (RuntimeException expected) {
                    rejected.set(true);
                } finally {
                    stopped.countDown();
                }
            });
            ref.send(1);
            assertTrue(stopped.await(5, TimeUnit.SECONDS));
            assertTrue(ref.awaitTermination(8, TimeUnit.SECONDS));
            assertTrue(rejected.get(),
                    "same-turn self-send remained authorized after actor stop");
        }
    }
}
