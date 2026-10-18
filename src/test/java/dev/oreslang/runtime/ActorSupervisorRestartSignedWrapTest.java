package dev.oreslang.runtime;

import static dev.oreslang.runtime.ActorSupervisorRestartCircuit.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class ActorSupervisorRestartSignedWrapTest {
    @Test
    void expiredRestartBudgetIsPrunedAcrossSignedNanoTimeWrap() {
        var circuit = new ActorSupervisorRestartCircuit(
                new Config(1, 1, 100, 0, 0, 0));

        long firstStart = Long.MAX_VALUE - 20;
        long firstExit = Long.MAX_VALUE - 10;
        long replacementStart = Long.MAX_VALUE - 5;
        long afterSignedWrap = Long.MIN_VALUE + 100;

        circuit.admitGeneration(0, 1, firstStart);
        assertEquals(DecisionKind.RESTART, circuit.onExit(
                0,
                1,
                RestartPolicy.PERMANENT,
                ExitClass.ABNORMAL,
                Strategy.ONE_FOR_ONE,
                firstExit).kind());

        circuit.admitGeneration(0, 2, replacementStart);

        // 111ns have elapsed since firstExit modulo signed long arithmetic.
        // With a 100ns restart window, the first restart must have expired even
        // though the signed nanoTime representation crossed MAX -> MIN.
        var second = circuit.onExit(
                0,
                2,
                RestartPolicy.PERMANENT,
                ExitClass.ABNORMAL,
                Strategy.ONE_FOR_ONE,
                afterSignedWrap);

        assertEquals(DecisionKind.RESTART, second.kind(),
                "signed nanoTime wrap must not manufacture restart-storm escalation");
        assertEquals(1, circuit.snapshot(afterSignedWrap).restartsInWindow());
        assertEquals(2L, circuit.snapshot(afterSignedWrap).acceptedReplacementRestarts());
    }

    @Test
    void exactWindowBoundaryStillCountsAcrossSignedWrap() {
        var circuit = new ActorSupervisorRestartCircuit(
                new Config(1, 1, 111, 0, 0, 0));

        long firstStart = Long.MAX_VALUE - 20;
        long firstExit = Long.MAX_VALUE - 10;
        long replacementStart = Long.MAX_VALUE - 5;
        long exactBoundary = Long.MIN_VALUE + 100;

        circuit.admitGeneration(0, 1, firstStart);
        assertEquals(DecisionKind.RESTART, circuit.onExit(
                0,
                1,
                RestartPolicy.PERMANENT,
                ExitClass.ABNORMAL,
                Strategy.ONE_FOR_ONE,
                firstExit).kind());
        circuit.admitGeneration(0, 2, replacementStart);

        assertEquals(DecisionKind.ESCALATE, circuit.onExit(
                0,
                2,
                RestartPolicy.PERMANENT,
                ExitClass.ABNORMAL,
                Strategy.ONE_FOR_ONE,
                exactBoundary).kind(),
                "restart exactly at now-window must still count after signed wrap");
    }
}
