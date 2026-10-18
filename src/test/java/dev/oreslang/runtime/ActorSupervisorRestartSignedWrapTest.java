package dev.oreslang.runtime;

import static dev.oreslang.runtime.ActorSupervisorRestartCircuit.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
    void stableLifetimeResetsBackoffAcrossSignedTimeWrap() {
        var circuit = new ActorSupervisorRestartCircuit(
                new Config(1, 3, 1_000, 10, 80, 100));
        long start = Long.MAX_VALUE - 50;
        circuit.admitGeneration(0, 1, start);
        var first = circuit.onExit(0, 1, RestartPolicy.PERMANENT,
                ExitClass.ABNORMAL, Strategy.ONE_FOR_ONE, Long.MAX_VALUE - 30);
        assertEquals(1, first.attempt());
        circuit.admitGeneration(0, 2, Long.MAX_VALUE - 20);
        var second = circuit.onExit(0, 2, RestartPolicy.PERMANENT,
                ExitClass.ABNORMAL, Strategy.ONE_FOR_ONE, Long.MIN_VALUE + 200);
        assertEquals(DecisionKind.RESTART, second.kind());
        assertEquals(1, second.attempt(),
                "a stable child spanning signed wrap must reset backoff");
        assertEquals(2L, circuit.snapshot(Long.MIN_VALUE + 200).acceptedReplacementRestarts());
    }

    @Test
    void backwardClockSampleAcrossWrapFailsBeforeMutatingRestartHistory() {
        var circuit = new ActorSupervisorRestartCircuit(
                new Config(1, 2, 1_000, 0, 0, 0));
        circuit.admitGeneration(0, 1, Long.MIN_VALUE + 10);
        assertThrows(IllegalArgumentException.class,
                () -> circuit.snapshot(Long.MAX_VALUE - 10));
        var recovered = circuit.snapshot(Long.MIN_VALUE + 11);
        assertEquals(1L, recovered.clockRegressions());
        assertEquals(0, recovered.restartsInWindow());
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
