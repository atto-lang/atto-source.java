package dev.oreslang.runtime;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Historical #433 reusable-plan property on the currently supported channel runtime. */
final class SelectPlanForwardPortTest {
    @Test
    void reusableSelectPlanPreservesFairnessAcrossGenerations() {
        ChannelRuntime.Channel<Integer> first = new ChannelRuntime.Channel<>(2);
        ChannelRuntime.Channel<Integer> second = new ChannelRuntime.Channel<>(2);
        assertTrue(first.tryWrite(10));
        assertTrue(first.tryWrite(11));
        assertTrue(second.tryWrite(20));
        assertTrue(second.tryWrite(21));

        ChannelRuntime.SelectPlan plan = new ChannelRuntime.SelectPlan(List.of(
                ChannelRuntime.read(first),
                ChannelRuntime.read(second)));

        ChannelRuntime.SelectResult a =
                plan.trySelect(ChannelRuntime.SelectPolicy.FAIR).orElseThrow();
        ChannelRuntime.SelectResult b =
                plan.trySelect(ChannelRuntime.SelectPolicy.FAIR).orElseThrow();

        assertEquals(0, a.index());
        assertEquals(10, a.value());
        assertEquals(1, b.index());
        assertEquals(20, b.value());
    }


    @Test
    void immutablePlanSharesOneDescriptorAcrossTenThousandFairSelections() {
        ChannelRuntime.Channel<Integer> first = new ChannelRuntime.Channel<>(1);
        ChannelRuntime.Channel<Integer> second = new ChannelRuntime.Channel<>(1);
        ChannelRuntime.SelectPlan plan = ChannelRuntime.SelectPlan.fromList(List.of(
                ChannelRuntime.read(first), ChannelRuntime.read(second)));
        ChannelRuntime.SelectSet descriptor = plan.selectSetSnapshot();

        for (int i = 0; i < 10_000; i++) {
            assertTrue(first.tryWrite(i));
            assertTrue(second.tryWrite(-i));
            ChannelRuntime.SelectResult a =
                    plan.trySelect(ChannelRuntime.SelectPolicy.FAIR).orElseThrow();
            ChannelRuntime.SelectResult b =
                    plan.trySelect(ChannelRuntime.SelectPolicy.FAIR).orElseThrow();
            assertEquals(0, a.index(), "FAIR first index at iteration " + i);
            assertEquals(i, a.value());
            assertEquals(1, b.index(), "FAIR second index at iteration " + i);
            assertEquals(-i, b.value());
        }
        assertSame(descriptor, plan.selectSetSnapshot());
        assertEquals(0, first.pendingRegistrations());
        assertEquals(0, second.pendingRegistrations());
    }

    @Test
    void immutablePlanDefaultDoesNotSpendFairCursorCredit() {
        ChannelRuntime.Channel<Integer> first = new ChannelRuntime.Channel<>(1);
        ChannelRuntime.Channel<Integer> second = new ChannelRuntime.Channel<>(1);
        ChannelRuntime.SelectPlan plan = ChannelRuntime.SelectPlan.fromList(List.of(
                ChannelRuntime.read(first),
                ChannelRuntime.read(second),
                ChannelRuntime.defaultCase()));

        assertEquals(ChannelRuntime.SelectOperation.DEFAULT,
                plan.trySelect(ChannelRuntime.SelectPolicy.FAIR)
                        .orElseThrow().operation());
        assertTrue(first.tryWrite(7));
        assertTrue(second.tryWrite(8));
        assertEquals(0, plan.trySelect(ChannelRuntime.SelectPolicy.FAIR)
                .orElseThrow().index());
        assertEquals(1, plan.trySelect(ChannelRuntime.SelectPolicy.FAIR)
                .orElseThrow().index());
    }

    @Test
    void immutablePlanCancellationDetachesPendingWaiters() {
        ChannelRuntime.Channel<Integer> first = new ChannelRuntime.Channel<>(0);
        ChannelRuntime.Channel<Integer> second = new ChannelRuntime.Channel<>(0);
        ChannelRuntime.SelectPlan plan = ChannelRuntime.SelectPlan.fromList(List.of(
                ChannelRuntime.read(first), ChannelRuntime.read(second)));

        OresFuture<ChannelRuntime.SelectResult> pending =
                plan.selectAsync(ChannelRuntime.SelectPolicy.FAIR);
        assertEquals(1, first.pendingRegistrations());
        assertEquals(1, second.pendingRegistrations());
        assertTrue(pending.cancel(false));
        assertEquals(0, first.pendingRegistrations());
        assertEquals(0, second.pendingRegistrations());
    }

}
