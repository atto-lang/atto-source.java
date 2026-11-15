package dev.oreslang;

import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.GuestCallDepthExceededException;
import dev.oreslang.runtime.GuestCallDepthGuard;
import dev.oreslang.runtime.IsolatePolicy;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Host runtime model of a budgeted actor failure. This is NOT compiler-backed
 * .atto guest execution, proof of native stack fault recovery, or a supervisor
 * restart test.
 */
final class GuestCallDepthActorHostModelTest {
    // Main requires stateless BehaviorFactory lambdas (no captured host
    // state), so the sibling's delivery probe lives in a static field.
    private static final AtomicInteger SIBLING_DELIVERIES = new AtomicInteger();

    @Test
    void actorBudgetExhaustionRetiresOnlyThatActorAndAllowsSiblingTurn() throws Exception {
        // Both SHARED actor turns use a one-worker SHARED carrier pool.
        var config = new ActorRuntime.DispatcherConfig(1, 1, 8);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            SIBLING_DELIVERIES.set(0);
            var exhausted = runtime.<String>spawnShared(factory ->
                    (message, turn) -> {
                        int reserved = 0;
                        try {
                            for (int n = 0; n < GuestCallDepthGuard.MAX_SYNCHRONOUS_GUEST_CALLS; n++) {
                                reserved = GuestCallDepthGuard.enter();
                            }
                            GuestCallDepthGuard.enter();
                        } finally {
                            while (reserved > 0) GuestCallDepthGuard.leave(reserved--);
                        }
                    });
            var sibling = runtime.<String>spawnShared(factory ->
                    (message, turn) -> {
                        int depth = GuestCallDepthGuard.enter();
                        try {
                            assertEquals(1, depth, "previous turn polluted the carrier");
                            SIBLING_DELIVERIES.incrementAndGet();
                        } finally {
                            GuestCallDepthGuard.leave(depth);
                            turn.self().stop();
                        }
                    });

            exhausted.send("overrun");
            assertTrue(exhausted.awaitTermination(3, TimeUnit.SECONDS));
            // Force the same single-worker SHARED pool to accept a new turn
            // after the failed actor has retired and released its carrier.
            sibling.send("alive");
            assertTrue(sibling.awaitTermination(3, TimeUnit.SECONDS));
            assertInstanceOf(GuestCallDepthExceededException.class,
                    exhausted.failure().orElseThrow());
            assertTrue(sibling.failure().isEmpty());
            assertEquals(1, SIBLING_DELIVERIES.get());
        }
    }
}
