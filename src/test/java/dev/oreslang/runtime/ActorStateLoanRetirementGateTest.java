package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

final class ActorStateLoanRetirementGateTest {
    @Test
    void stateLoanGateBlocksDoneAndActorMemoryRetirementUntilPhysicalReceipt()
            throws Exception {
        try (var runtime = new ActorRuntime()) {
            var retired = new AtomicBoolean();
            runtime.setStateLoanRetirementGate(ignored -> retired.get());

            var actor = runtime.<String>spawnShared(
                    () -> (message, context) -> { });
            actor.ready().get(3, TimeUnit.SECONDS);

            actor.cancel();
            assertFalse(actor.done().isDone(),
                    "logical stop cannot complete DONE while physical state loan is live");

            retired.set(true);
            runtime.onStateLoanRetired(actor.id());

            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS),
                    "final physical state-loan receipt must unblock actor retirement");
            assertTrue(actor.done().isDone());
        }
    }

    @Test
    void nativeIoAndStateLoanRetirementGatesAreIndependent() throws Exception {
        try (var runtime = new ActorRuntime()) {
            var ioRetired = new AtomicBoolean();
            var stateRetired = new AtomicBoolean();
            runtime.setNativeIoLoanRetirementGate(ignored -> ioRetired.get());
            runtime.setStateLoanRetirementGate(ignored -> stateRetired.get());

            var actor = runtime.<String>spawnShared(
                    () -> (message, context) -> { });
            actor.ready().get(3, TimeUnit.SECONDS);
            actor.cancel();

            stateRetired.set(true);
            runtime.onStateLoanRetired(actor.id());
            assertFalse(actor.done().isDone(),
                    "state receipt must not bypass an outstanding native I/O loan");

            ioRetired.set(true);
            runtime.onNativeIoLoanRetired(actor.id());
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void stateLoanRetirementGateFailureQuarantinesFinalization() throws Exception {
        var runtime = new ActorRuntime();
        var permit = new AtomicBoolean();
        runtime.setStateLoanRetirementGate(id -> {
            if (!permit.get()) throw new IllegalStateException("state receipt corrupt");
            return true;
        });
        var actor = runtime.<String>spawnShared(
                () -> (message, context) -> { });
        try {
            actor.ready().get(3, TimeUnit.SECONDS);
            actor.cancel();
            assertFalse(actor.done().isDone(),
                    "gate failure must not release actor DONE or memory");
        } finally {
            permit.set(true);
            // A failed physical receipt is intentionally fail-closed; normal
            // ActorRuntime cleanup may retain/quarantine the cell rather than
            // trusting a later success as proof of the failed receipt.
            try { runtime.close(); } catch (IllegalStateException ignored) { }
        }
    }
}
