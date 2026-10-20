package dev.oreslang.nodes;

import dev.oreslang.runtime.TaskScope;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

final class TaskSpawnAdmissionOrderingTest {
    @Test
    void cancelledScopeRejectsBeforeOwnershipTransferSupplierRuns() {
        TaskScope scope = new TaskScope();
        scope.cancelAndDrain();
        AtomicBoolean transferred = new AtomicBoolean();

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> OresEvalRootNode.reserveBeforeTaskTransfer(
                        scope,
                        null,
                        () -> {
                            transferred.set(true);
                            return "must-not-run";
                        }));

        assertTrue(failure.getMessage().contains("cancelled"));
        assertFalse(
                transferred.get(),
                "scope denial must happen before runtime ownership transfer");
    }

    @Test
    void transferPreparationFailureRetiresReservedScopeTicket() {
        TaskScope scope = new TaskScope();
        RuntimeException marker = new RuntimeException("projection failed");

        RuntimeException failure = assertThrows(
                RuntimeException.class,
                () -> OresEvalRootNode.reserveBeforeTaskTransfer(
                        scope,
                        null,
                        () -> {
                            throw marker;
                        }));
        assertSame(marker, failure);

        // failedAdmission() must retire the ticket; close cannot remain stuck
        // behind a child that never reached scheduler admission.
        assertTrue(scope.close().isDone());
    }
}
