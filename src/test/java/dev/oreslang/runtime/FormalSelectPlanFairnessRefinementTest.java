package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

/**
 * Bounded trace refinement of the *concrete* SelectPlan against a three-arm
 * round-robin selection state machine. Every combination of readiness masks
 * (including empty/cancelled selection) across three consecutive generations
 * is replayed from a new concrete plan.
 *
 * This is exhaustive over 8^3 bounded traces, not a proof of unbounded
 * scheduler liveness or of compiler-emitted source-level plan caching.
 */
final class FormalSelectPlanFairnessRefinementTest {
    private static final int ARMS = 3;
    private static final int TRACES = 8 * 8 * 8;

    private static int modelWinner(int cursor, int mask) {
        for (int step = 0; step < ARMS; step++) {
            int arm = (cursor + step) % ARMS;
            if ((mask & (1 << arm)) != 0) return arm;
        }
        return -1;
    }

    @Test
    void allThreeGenerationReadinessTracesRefineToConcreteAtomicFairSelect() {
        for (int encoded = 0; encoded < TRACES; encoded++) {
            @SuppressWarnings("unchecked")
            ChannelRuntime.Channel<Integer>[] channels = new ChannelRuntime.Channel[ARMS];
            for (int arm = 0; arm < ARMS; arm++) {
                channels[arm] = new ChannelRuntime.Channel<>(1);
            }
            ChannelRuntime.SelectPlan plan = ChannelRuntime.SelectPlan.of(
                    ChannelRuntime.read(channels[0]),
                    ChannelRuntime.read(channels[1]),
                    ChannelRuntime.read(channels[2]));

            int cursor = 0;
            int sequence = encoded;
            for (int generation = 0; generation < 3; generation++) {
                int mask = sequence & 7;
                sequence >>= 3;
                for (int arm = 0; arm < ARMS; arm++) {
                    if ((mask & (1 << arm)) != 0) {
                        assertTrue(channels[arm].tryWrite(generation * ARMS + arm));
                    }
                }

                int expected = modelWinner(cursor, mask);
                OresFuture<ChannelRuntime.SelectResult> selected =
                        plan.selectAsync(ChannelRuntime.SelectPolicy.FAIR);
                if (expected < 0) {
                    assertFalse(selected.isDone(), "empty readiness must suspend");
                    assertTrue(selected.cancel(false), "cancel pending registration");
                    assertThrows(CancellationException.class, selected::join);
                    // Cancellation cannot rotate the plan's fairness cursor.
                } else {
                    assertEquals(expected, selected.join().index(),
                            "trace " + encoded + ", generation " + generation);
                    assertEquals(generation * ARMS + expected,
                            selected.join().value());
                    cursor = (expected + 1) % ARMS;
                }

                for (int arm = 0; arm < ARMS; arm++) {
                    if ((mask & (1 << arm)) != 0 && arm != expected) {
                        assertEquals(generation * ARMS + arm,
                                channels[arm].tryRead().orElseThrow(),
                                "losing arm must retain its committed value");
                    } else {
                        assertTrue(channels[arm].tryRead().isEmpty(),
                                "winner consumed exactly one value");
                    }
                }
            }
        }
    }
}
