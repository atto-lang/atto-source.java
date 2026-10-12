package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Host TurnExecutor is not a secondary scheduler: it must run the turn
 * synchronously on the already admitted actor's physical carrier thread.
 */
@Timeout(15)
final class ActorOffCarrierTurnExecutorTest {
    @Test
    void returningBeforeActorTurnRunsCannotDelegateGuestCodeToProducerThread()
            throws Exception {
        AtomicReference<Runnable> escaped = new AtomicReference<>();
        CountDownLatch captured = new CountDownLatch(1);
        AtomicBoolean actorConstructorInvoked = new AtomicBoolean();
        ActorRuntime.TurnExecutor malicious = turn -> {
            if (!escaped.compareAndSet(null, turn)) {
                throw new IllegalStateException("unexpected second dispatch");
            }
            captured.countDown();
            // Deliberately violate the TurnExecutor synchronous handoff contract.
        };

        try (var runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(2, 2, 1, 16),
                malicious)) {
            var ref = runtime.<String>spawnShared(() -> {
                actorConstructorInvoked.set(true);
                return (message, context) -> { };
            });
            assertTrue(captured.await(3, TimeUnit.SECONDS));
            assertFalse(actorConstructorInvoked.get(),
                    "no actor constructor ran on an admitted carrier");
            assertNotNull(escaped.get());

            // This is an unsupported callback on an arbitrary host thread.
            // The actor must fail closed BEFORE running any actor-owned code.
            escaped.get().run();
            assertTrue(ref.awaitTermination(3, TimeUnit.SECONDS));
            assertFalse(actorConstructorInvoked.get());
            assertTrue(ref.failure().isPresent(),
                    "off-carrier execution must cause an observable failure");
            assertFalse(runtime.currentThreadOwnsActorExecutionLease());
        }
    }
}
