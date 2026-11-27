package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import static dev.oreslang.runtime.ActorSupervisorRestartCircuit.*;
import static org.junit.jupiter.api.Assertions.*;

final class ActorSupervisorRestartCircuitTest {
    private static Config config(
            int children,
            int maxRestarts,
            long window,
            long base,
            long cap,
            long stable) {
        return new Config(children, maxRestarts, window, base, cap, stable);
    }

    @Test
    void oneForOneRestartIsGenerationFencedAndDuplicateIsStale() {
        var c = new ActorSupervisorRestartCircuit(
                config(3, 8, 1_000, 10, 1_000, 500));
        c.admitGeneration(1, 10, 100);

        var first = c.onExit(
                1, 10, RestartPolicy.PERMANENT, ExitClass.ABNORMAL,
                Strategy.ONE_FOR_ONE, 150);
        assertEquals(DecisionKind.RESTART, first.kind());
        assertEquals(1, first.firstChild());
        assertEquals(2, first.endExclusive());
        assertEquals(1, first.chargedRestarts());
        assertTrue(c.restartPending(1));

        var duplicate = c.onExit(
                1, 10, RestartPolicy.PERMANENT, ExitClass.ABNORMAL,
                Strategy.ONE_FOR_ONE, 151);
        assertEquals(DecisionKind.STALE, duplicate.kind());
        assertEquals(1, c.snapshot(151).staleNotices());

        c.admitGeneration(1, 11, 160);
        assertEquals(11, c.generation(1));
        assertFalse(c.restartPending(1));
    }

    @Test
    void transientAndTemporaryPoliciesDoNotInventRestarts() {
        var transientCircuit = new ActorSupervisorRestartCircuit(
                config(1, 8, 1_000, 10, 100, 500));
        transientCircuit.admitGeneration(0, 1, 0);
        var normal = transientCircuit.onExit(
                0, 1, RestartPolicy.TRANSIENT, ExitClass.NORMAL,
                Strategy.ONE_FOR_ONE, 10);
        assertEquals(DecisionKind.NO_RESTART, normal.kind());
        assertEquals(0, normal.chargedRestarts());

        var temporaryCircuit = new ActorSupervisorRestartCircuit(
                config(1, 8, 1_000, 10, 100, 500));
        temporaryCircuit.admitGeneration(0, 1, 0);
        var abnormal = temporaryCircuit.onExit(
                0, 1, RestartPolicy.TEMPORARY, ExitClass.ABNORMAL,
                Strategy.ONE_FOR_ONE, 10);
        assertEquals(DecisionKind.NO_RESTART, abnormal.kind());
    }

    @Test
    void oneForAllChargesEveryReplacementBeforeAdmission() {
        var c = new ActorSupervisorRestartCircuit(
                config(4, 4, 1_000, 10, 100, 500));
        for (int i = 0; i < 4; ++i) c.admitGeneration(i, i + 1, 0);

        var plan = c.onExit(
                2, 3, RestartPolicy.PERMANENT, ExitClass.ABNORMAL,
                Strategy.ONE_FOR_ALL, 10);
        assertEquals(DecisionKind.RESTART, plan.kind());
        assertEquals(0, plan.firstChild());
        assertEquals(4, plan.endExclusive());
        assertEquals(4, plan.chargedRestarts());
        assertEquals(4, c.snapshot(10).restartsInWindow());
        for (int i = 0; i < 4; ++i) assertTrue(c.restartPending(i));

        // A fresh episode cannot sneak past the full budget.
        for (int i = 0; i < 4; ++i) c.admitGeneration(i, 10 + i, 20);
        var exhausted = c.onExit(
                0, 10, RestartPolicy.PERMANENT, ExitClass.ABNORMAL,
                Strategy.ONE_FOR_ONE, 21);
        assertEquals(DecisionKind.ESCALATE, exhausted.kind());
        assertEquals(0, exhausted.chargedRestarts());
        assertEquals(1, c.snapshot(21).escalations());
    }

    @Test
    void restForOneSelectsOnlyFailedChildAndYoungerSiblings() {
        var c = new ActorSupervisorRestartCircuit(
                config(5, 10, 1_000, 0, 0, 0));
        for (int i = 0; i < 5; ++i) c.admitGeneration(i, i + 1, 0);

        var plan = c.onExit(
                2, 3, RestartPolicy.PERMANENT, ExitClass.ABNORMAL,
                Strategy.REST_FOR_ONE, 5);
        assertEquals(2, plan.firstChild());
        assertEquals(5, plan.endExclusive());
        assertEquals(3, plan.width());
        assertFalse(c.restartPending(0));
        assertFalse(c.restartPending(1));
        assertTrue(c.restartPending(2));
        assertTrue(c.restartPending(3));
        assertTrue(c.restartPending(4));
    }

    @Test
    void stableGenerationResetsConsecutiveBackoffAttempt() {
        var c = new ActorSupervisorRestartCircuit(
                config(1, 16, 10_000, 100, 10_000, 1_000));
        c.admitGeneration(0, 1, 0);

        var first = c.onExit(
                0, 1, RestartPolicy.PERMANENT, ExitClass.ABNORMAL,
                Strategy.ONE_FOR_ONE, 100);
        assertEquals(1, first.attempt());
        assertTrue(first.delayNanos() >= 100);

        c.admitGeneration(0, 2, 110);
        var second = c.onExit(
                0, 2, RestartPolicy.PERMANENT, ExitClass.ABNORMAL,
                Strategy.ONE_FOR_ONE, 210);
        assertEquals(2, second.attempt());
        assertTrue(second.delayNanos() >= 200);

        c.admitGeneration(0, 3, 220);
        var stable = c.onExit(
                0, 3, RestartPolicy.PERMANENT, ExitClass.ABNORMAL,
                Strategy.ONE_FOR_ONE, 1_500);
        assertEquals(1, stable.attempt(),
                "a generation stable for the reset period starts a new backoff episode");
        assertTrue(stable.delayNanos() >= 100);
        assertTrue(stable.delayNanos() < 200);
    }

    @Test
    void deterministicSpreadIsStableAndCapped() {
        var left = new ActorSupervisorRestartCircuit(
                config(2, 16, 10_000, 1_000, 8_000, 100_000));
        var right = new ActorSupervisorRestartCircuit(
                config(2, 16, 10_000, 1_000, 8_000, 100_000));
        left.admitGeneration(0, 55, 0);
        right.admitGeneration(0, 55, 0);

        var a = left.onExit(
                0, 55, RestartPolicy.PERMANENT, ExitClass.ABNORMAL,
                Strategy.ONE_FOR_ONE, 1);
        var b = right.onExit(
                0, 55, RestartPolicy.PERMANENT, ExitClass.ABNORMAL,
                Strategy.ONE_FOR_ONE, 1);
        assertEquals(a.delayNanos(), b.delayNanos(),
                "same trace must produce the same dephasing");
        assertTrue(a.delayNanos() >= 1_000 && a.delayNanos() <= 1_125);

        for (long generation = 56; generation < 70; ++generation) {
            left.admitGeneration(0, generation, generation);
            var p = left.onExit(
                    0, generation, RestartPolicy.PERMANENT, ExitClass.ABNORMAL,
                    Strategy.ONE_FOR_ONE, generation);
            assertTrue(p.delayNanos() <= 8_000,
                    "backoff plus spread must never exceed the configured cap");
        }
    }

    @Test
    void restartWindowBoundaryIsDeterministicAndThenExpires() {
        var c = new ActorSupervisorRestartCircuit(
                config(1, 1, 100, 0, 0, 0));
        c.admitGeneration(0, 1, 0);
        assertEquals(DecisionKind.RESTART, c.onExit(
                0, 1, RestartPolicy.PERMANENT, ExitClass.ABNORMAL,
                Strategy.ONE_FOR_ONE, 10).kind());

        c.admitGeneration(0, 2, 20);
        assertEquals(DecisionKind.ESCALATE, c.onExit(
                0, 2, RestartPolicy.PERMANENT, ExitClass.ABNORMAL,
                Strategy.ONE_FOR_ONE, 110).kind(),
                "restart exactly at the half-open cutoff still counts");

        // The escalated generation is terminal. Start a new externally admitted
        // generation only to prove the old budget entry expires after cutoff.
        c.admitGeneration(0, 3, 111);
        assertEquals(DecisionKind.RESTART, c.onExit(
                0, 3, RestartPolicy.PERMANENT, ExitClass.ABNORMAL,
                Strategy.ONE_FOR_ONE, 111).kind());
    }

    @Test
    void clockRegressionFailsClosedWithoutConsumingRestartBudget() {
        var c = new ActorSupervisorRestartCircuit(
                config(1, 4, 1_000, 0, 0, 0));
        c.admitGeneration(0, 1, 100);
        assertThrows(IllegalArgumentException.class, () -> c.onExit(
                0, 1, RestartPolicy.PERMANENT, ExitClass.ABNORMAL,
                Strategy.ONE_FOR_ONE, 99));

        var snapshot = c.snapshot(100);
        assertEquals(1, snapshot.clockRegressions());
        assertEquals(0, snapshot.restartsInWindow());
        assertEquals(0, snapshot.acceptedReplacementRestarts());

        assertEquals(DecisionKind.RESTART, c.onExit(
                0, 1, RestartPolicy.PERMANENT, ExitClass.ABNORMAL,
                Strategy.ONE_FOR_ONE, 101).kind());
    }

    @Test
    void zeroRestartBudgetEscalatesBeforeMarkingReplacementPending() {
        var c = new ActorSupervisorRestartCircuit(
                config(2, 0, 1_000, 10, 100, 100));
        c.admitGeneration(0, 1, 0);
        c.admitGeneration(1, 2, 0);

        var plan = c.onExit(
                0, 1, RestartPolicy.PERMANENT, ExitClass.ABNORMAL,
                Strategy.ONE_FOR_ALL, 1);
        assertEquals(DecisionKind.ESCALATE, plan.kind());
        assertFalse(c.restartPending(0));
        assertFalse(c.restartPending(1));
        assertEquals(0, c.snapshot(1).acceptedReplacementRestarts());
    }
}
