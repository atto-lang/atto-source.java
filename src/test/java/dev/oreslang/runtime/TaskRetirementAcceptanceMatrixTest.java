package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Executable acceptance matrix for the shared Task lifetime contract.
 *
 * Logical terminal authority and physical carrier quiescence are independent
 * witnesses. A lexical scope may retire a child only after both have arrived;
 * failed admission is the only no-Future path that supplies both witnesses.
 */
final class TaskRetirementAcceptanceMatrixTest {

    @Test
    void terminalAuthorityAloneCannotDrainScope() {
        TaskScope scope = new TaskScope();
        TaskScope.Registration child = scope.reserve();
        OresFuture<Integer> future = OresFuture.completed(42);

        child.terminalReady(future);
        OresFuture<Void> drained = scope.close();

        assertFalse(
                drained.isDone(),
                "terminal Future state is not a physical-retirement witness");

        child.quiesced();

        assertTrue(drained.isDone());
        assertDoesNotThrow(drained::join);
        assertDoesNotThrow(
                () -> child.attach(future),
                "fast-child late attach of the exact terminal witness remains valid");
    }

    @Test
    void physicalQuiescenceAloneCannotDrainScope() {
        TaskScope scope = new TaskScope();
        TaskScope.Registration child = scope.reserve();

        child.quiesced();
        OresFuture<Void> drained = scope.close();

        assertFalse(
                drained.isDone(),
                "carrier quiescence without terminal authority cannot retire a child");

        OresFuture<Integer> future = OresFuture.completed(7);
        child.attach(future);

        assertTrue(drained.isDone());
        assertDoesNotThrow(drained::join);
    }

    @Test
    void terminalAndQuiescenceAreOrderIndependentButBothRequired() {
        TaskScope first = new TaskScope();
        TaskScope.Registration terminalFirst = first.reserve();
        OresFuture<Integer> firstFuture = OresFuture.completed(1);
        terminalFirst.terminalReady(firstFuture);
        assertFalse(first.close().isDone());
        terminalFirst.quiesced();
        assertTrue(first.drained().isDone());

        TaskScope second = new TaskScope();
        TaskScope.Registration quiescentFirst = second.reserve();
        quiescentFirst.quiesced();
        assertFalse(second.close().isDone());
        OresFuture<Integer> secondFuture = OresFuture.completed(2);
        quiescentFirst.attach(secondFuture);
        assertTrue(second.drained().isDone());
    }

    @Test
    void failedAdmissionIsTheOnlyNoFutureRetirementPath() {
        TaskScope scope = new TaskScope();
        TaskScope.Registration child = scope.reserve();
        IllegalStateException admissionFailure =
                new IllegalStateException("scheduler rejected admission");

        child.failedAdmission(admissionFailure);
        OresFuture<Void> drained = scope.close();

        assertTrue(drained.isDone());
        RuntimeException observed = assertThrows(RuntimeException.class, drained::join);
        assertTrue(
                observed == admissionFailure
                        || observed.getCause() == admissionFailure
                        || observed.toString().contains("scheduler rejected admission"));
        assertThrows(
                IllegalStateException.class,
                () -> child.attach(OresFuture.completed(3)),
                "failed admission must never acquire scheduler Future authority");
    }
}
