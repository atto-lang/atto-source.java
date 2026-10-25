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

}
