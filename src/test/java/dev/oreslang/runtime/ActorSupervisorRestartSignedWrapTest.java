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
        circuit.admitGeneration(0, 2, (Long.MAX_VALUE - 30) + first.delayNanos());
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

    @Test
    void groupRestartCannotAdmitFirstReplacementBeforeEverySiblingQuiesces() {
        for (Strategy strategy : new Strategy[] {
                Strategy.ONE_FOR_ALL, Strategy.REST_FOR_ONE }) {
            var circuit = new ActorSupervisorRestartCircuit(
                    new Config(3, 3, 100, 0, 0, 0));
            for (int child = 0; child < 3; ++child) {
                circuit.admitGeneration(child, 1, 0);
            }
            var plan = circuit.onExit(0, 1, RestartPolicy.PERMANENT,
                    ExitClass.ABNORMAL, strategy, 1);
            assertEquals(DecisionKind.RESTART, plan.kind());
            assertEquals(3, plan.chargedRestarts());
            assertEquals(1, circuit.snapshot(1).quiescedPendingReplacements());
            assertThrows(IllegalStateException.class,
                    () -> circuit.admitGeneration(0, 2, 2));
            assertThrows(IllegalStateException.class,
                    () -> circuit.acknowledgeQuiesced(1, 1, 2));
            assertEquals(true, circuit.acknowledgeQuiesced(2, 1, 2));
            assertThrows(IllegalStateException.class,
                    () -> circuit.admitGeneration(0, 2, 2));
            assertEquals(true, circuit.acknowledgeQuiesced(1, 1, 2));
            assertThrows(IllegalStateException.class,
                    () -> circuit.admitGeneration(2, 2, 2));
            assertEquals(3, circuit.snapshot(2).pendingReplacements());
            assertEquals(3, circuit.snapshot(2).restartsInWindow());
            assertEquals(1L, circuit.generation(0));
            for (int child = 0; child < 3; ++child) {
                circuit.admitGeneration(child, 2, 2);
            }
            assertEquals(0, circuit.snapshot(2).pendingReplacements());
            assertEquals(3L, circuit.snapshot(2).acceptedReplacementRestarts());
        }
    }


    @Test
    void backoffIsEnforcedForEveryReplacementAcrossSignedWrap() {
        var circuit = new ActorSupervisorRestartCircuit(
                new Config(2, 2, 1_000, 100, 100, 0));
        long exit = Long.MAX_VALUE - 50;
        circuit.admitGeneration(0, 1, exit - 1);
        circuit.admitGeneration(1, 1, exit - 1);
        var plan = circuit.onExit(0, 1, RestartPolicy.PERMANENT,
                ExitClass.ABNORMAL, Strategy.ONE_FOR_ALL, exit);
        assertEquals(100L, plan.delayNanos());
        assertEquals(true, circuit.acknowledgeQuiesced(1, 1, exit + 1));
        long early = exit + plan.delayNanos() - 1;
        assertThrows(IllegalStateException.class,
                () -> circuit.admitGeneration(0, 2, early));
        assertThrows(IllegalStateException.class,
                () -> circuit.admitGeneration(1, 2, early));
        assertEquals(2, circuit.snapshot(early).pendingReplacements());
        assertEquals(2L, circuit.snapshot(early).acceptedReplacementRestarts());
        assertEquals(1L, circuit.generation(0));
        assertEquals(1L, circuit.generation(1));

        long ready = exit + plan.delayNanos();
        circuit.admitGeneration(0, 2, ready);
        circuit.admitGeneration(1, 2, ready);
        assertEquals(0, circuit.snapshot(ready).pendingReplacements());
        assertEquals(2L, circuit.snapshot(ready).acceptedReplacementRestarts());
    }

}
