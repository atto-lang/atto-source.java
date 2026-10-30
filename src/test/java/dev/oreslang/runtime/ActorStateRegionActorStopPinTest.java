package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end ActorRuntime STOP/finalization gate. The test deliberately uses
 * reflection to introduce a physical loan into one live actor's *real*
 * state-region ledger; it is NOT a source-level authority/admission test.
 * Compiled critical-mode guest tests cover the authenticated permit path.
 */
@Timeout(30)
final class ActorStateRegionActorStopPinTest {

    private static Object actorCell(ActorRuntime runtime, ActorRuntime.ActorId id)
            throws Exception {
        Field actorsField = ActorRuntime.class.getDeclaredField("actors");
        actorsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<ActorRuntime.ActorId, ?> actors =
                (Map<ActorRuntime.ActorId, ?>) actorsField.get(runtime);
        Object cell = actors.get(id);
        assertNotNull(cell, "actor cell must exist before STOP");
        return cell;
    }

    private static Object internalField(Object cell, String name) throws Exception {
        Field field = cell.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(cell);
    }

    @Test
    void stopMustNotReclaimPrivateActorHeapWhileItsReadLoanIsPhysical()
            throws Exception {
        proveFinalizationWaitsForPhysicalLoan(false);
    }

    @Test
    void structuredCancelMustNotReclaimPrivateActorHeapWhileItsWriteLoanIsPhysical()
            throws Exception {
        proveFinalizationWaitsForPhysicalLoan(true);
    }

    private static void proveFinalizationWaitsForPhysicalLoan(boolean cancel)
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var actor = runtime.<Integer>spawnPrivate(
                    () -> (message, context) -> { });
            actor.ready().get(10, TimeUnit.SECONDS);
            Object cell = actorCell(runtime, actor.id());
            ActorStateRwLoans region =
                    (ActorStateRwLoans) internalField(cell, "stateRegion");
            ActorRuntime.ActorMemorySlice arena =
                    (ActorRuntime.ActorMemorySlice) internalField(cell, "memorySlice");
            assertNotNull(arena);
            assertFalse(arena.closed());

            // A test-only region admission simulates a physically held
            // compiler-issued loan, without pretending it proves the compiler
            // minted this ExecutionContext. The real finalization gate cannot
            // distinguish a legitimate region receipt from this test fixture.
            ExecutionContext execution = ExecutionContext.actorBound(
                    411, 27, 27, actor.generation(),
                    411, actor.generation(), actor.generation(), 0, 0);
            ActorStateRwLoans.StateLoan loan = (cancel
                    ? region.acquireWriteAsync(execution)
                    : region.acquireReadAsync(execution)).get(5, TimeUnit.SECONDS);

            try {
                if (cancel) {
                    assertTrue(actor.cancel(), "first structured STOP must be admitted");
                } else {
                    actor.stop();
                }
                assertFalse(arena.closed(),
                        "actor heap closed with a physically live state loan");
                assertFalse(region.physicallyRetired(),
                        "logical region close is not physical loan retirement");
                assertFalse(actor.done().isDone(),
                        "DONE was published before state-region physical release");
                assertThrows(java.util.concurrent.ExecutionException.class,
                        () -> region.acquireReadAsync(
                                ExecutionContext.actorBound(
                                        411, 28, 28, actor.generation(),
                                        411, actor.generation(), actor.generation(), 0, 0))
                                .get(5, TimeUnit.SECONDS),
                        "STOP must revoke new state-region admission");
            } finally {
                loan.close();
            }

            assertTrue(actor.awaitTermination(10, TimeUnit.SECONDS),
                    "actor failed to finalize after last state loan released");
            assertTrue(region.physicallyRetired());
            assertTrue(arena.closed(), "private actor arena not closed at physical retirement");
        }
    }
}
