package dev.oreslang.runtime;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

/** A carrier remains scarce even after its guest identity has unwound. */
@Timeout(10)
final class CarrierPostTurnNonparkingTest {
    @Test
    void unboundActorCarrierRejectsPendingFutureAndContendedCriticalWait() throws Exception {
        CriticalGate gate = new CriticalGate();
        CriticalGate.Ticket owner = gate.newTicket();
        assertTrue(owner.enterAsync().isDone());
        OresFuture<Void> pending = new OresFuture<>();
        CountDownLatch completedChecks = new CountDownLatch(1);
        AtomicReference<Throwable> unexpected = new AtomicReference<>();

        ActorRuntime.TurnExecutor afterGuestTurn = guest -> {
            guest.run();
            // ActorRuntime clears its guest actor execution context before
            // returning to TurnExecutor, but its dispatcher carrier is still
            // physically occupied by this callback.
            if (!ActorRuntime.isActorCarrierThread()) return;
            try {
                assertFalse(ActorRuntime.inActorExecution());
                assertThrows(IllegalStateException.class,
                        () -> gate.newTicket().enterSync());
                assertEquals(0, gate.queuedCount(),
                        "fail-fast contention must not enqueue a cancelled waiter");
                assertThrows(IllegalStateException.class, pending::join);
                assertThrows(IllegalStateException.class, pending::get);
                assertThrows(IllegalStateException.class,
                        () -> pending.get(1, TimeUnit.SECONDS));
                assertFalse(pending.isDone());
            } catch (Throwable failure) {
                unexpected.compareAndSet(null, failure);
            } finally {
                completedChecks.countDown();
            }
        };

        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8, 64),
                afterGuestTurn)) {
            var actor = runtime.<String>spawnShared(() -> (message, turn) -> { });
            assertTrue(completedChecks.await(3, TimeUnit.SECONDS),
                    "unbound carrier must never park on a pending Future or gate");
            assertNull(unexpected.get(), () -> String.valueOf(unexpected.get()));
            actor.stop();
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
        } finally {
            owner.close();
        }
    }
}
