package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

final class SelectPlanCollectionInputTest {
    @Test
    void staticListAndMapPlansUseReusableDescriptorsAndFreshSelections() {
        var channel = new ChannelRuntime.Channel<Integer>(1);
        var listPlan = ChannelRuntime.SelectPlan.from(List.of(ChannelRuntime.read(channel)));
        assertSame(listPlan.selectSetSnapshot(), listPlan.selectSetSnapshot());
        for (int i = 0; i < 128; i++) {
            assertTrue(channel.tryWrite(i));
            assertEquals(i, listPlan.trySelect().orElseThrow().value());
            assertEquals(0, channel.pendingRegistrations());
        }

        var ordered = new LinkedHashMap<String, ChannelRuntime.SelectCase>();
        ordered.put("rx", ChannelRuntime.read(channel));
        var mapPlan = ChannelRuntime.SelectPlan.fromMap(ordered);
        assertTrue(channel.tryWrite(401));
        assertEquals(401, mapPlan.trySelect().orElseThrow().value());
    }

    @Test
    void dynamicListUsesOrdinalKeysAndMapRetainsKeysAfterMutation() {
        var channel = new ChannelRuntime.Channel<Integer>(1);
        var fromList = ChannelRuntime.DynamicSelectPlan.fromList(
                List.of(ChannelRuntime.read(channel)));
        assertTrue(channel.tryWrite(1));
        assertEquals(0, fromList.trySelectKeyed(ChannelRuntime.SelectPolicy.FAIR)
                .orElseThrow().key());

        var input = new LinkedHashMap<String, ChannelRuntime.SelectCase>();
        input.put("first", ChannelRuntime.read(channel));
        var plan = new ChannelRuntime.DynamicSelectPlan<>(input);
        input.clear(); // Construction copied the entries.
        var snapshot = plan.selectSetSnapshot();
        assertTrue(channel.tryWrite(2));
        var first = plan.trySelectKeyed(ChannelRuntime.SelectPolicy.FAIR).orElseThrow();
        assertEquals("first", first.key());
        assertEquals(1L, first.version());

        plan.put("second", ChannelRuntime.defaultCase());
        assertNotSame(snapshot, plan.selectSetSnapshot());
        assertEquals("second", plan.trySelectKeyed(ChannelRuntime.SelectPolicy.FAIR)
                .orElseThrow().key());
    }

    @Test
    void invalidInputsDoNotPublishPartialSelections() {
        var channel = new ChannelRuntime.Channel<Integer>(1);
        assertThrows(IllegalArgumentException.class, () -> ChannelRuntime.SelectPlan.from(
                List.of(ChannelRuntime.defaultCase(), ChannelRuntime.defaultCase())));
        assertThrows(IllegalArgumentException.class, () -> ChannelRuntime.DynamicSelectPlan.fromList(
                List.of(ChannelRuntime.defaultCase(), ChannelRuntime.defaultCase())));
        assertEquals(0, channel.pendingRegistrations());
    }
}
