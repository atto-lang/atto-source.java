package dev.oreslang;

import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.OresFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Logical actor-state exclusion across suspension: handing the physical
 * carrier back must NOT permit a second user-mail handler to interleave.
 * This exercises the existing mailbox-turn fence, not source handle_state loan
 * integration or parallel partition proofs.
 */
@Timeout(20)
final class ActorLogicalTurnSuspensionTest {
    @Test
    void userMailCannotOvertakeSuspendedLogicalReceive() throws Exception {
        AtomicReference<OresFuture.Callback<Void>> completion = new AtomicReference<>();
        OresFuture<Void> gate = OresFuture.fromCallback(completion::set);
        CountDownLatch firstSuspended = new CountDownLatch(1);
        CountDownLatch secondDelivered = new CountDownLatch(1);
        AtomicInteger observed = new AtomicInteger();

        var config = new ActorRuntime.DispatcherConfig(2, 4, 64, 32);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            var ref = runtime.<Integer>spawnShared(() -> (message, context) -> {
                if (message == 1) {
                    assertEquals(0, observed.get());
                    context.runtime().suspendCurrentUserMailboxUntil(gate);
                    firstSuspended.countDown();
                    return;
                }
                assertEquals(2, message);
                observed.incrementAndGet();
                secondDelivered.countDown();
                context.self().stop();
            });

            ref.send(1);
            assertTrue(firstSuspended.await(5, TimeUnit.SECONDS));
            ref.send(2);
            assertFalse(secondDelivered.await(150, TimeUnit.MILLISECONDS),
                    "later user mail cannot enter while the first logical turn is suspended");
            assertEquals(0, observed.get());

            completion.get().resolve(null);
            assertTrue(secondDelivered.await(5, TimeUnit.SECONDS),
                    "later user mail must resume after first logical receive settles");
            assertTrue(ref.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(1, observed.get());
            assertTrue(ref.failure().isEmpty(), () -> "actor failed: " + ref.failure());
        }
    }
}
