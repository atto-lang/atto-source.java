package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import static dev.oreslang.runtime.ChannelRuntime.*;
import static org.junit.jupiter.api.Assertions.*;

final class DynamicSelectPlanTest {
    @Test
    void replacementRetainsKeyPositionAndVersion() {
        Channel<Integer> a = new Channel<>(1);
        Channel<Integer> b = new Channel<>(1);
        DynamicSelectPlan<String> plan = new DynamicSelectPlan<>();
        plan.put("alpha", read(a));
        plan.put("beta", read(b));
        long before = plan.version();
        plan.put("alpha", read(b));
        assertEquals(before + 1, plan.version());
        assertEquals(java.util.List.of("alpha", "beta"), plan.snapshot().keys());
        plan.put("alpha", read(b));
        assertEquals(before + 1, plan.version());
        assertThrows(IllegalArgumentException.class, () -> {
            plan.put("default1", defaultCase());
            plan.put("default2", defaultCase());
        });
    }

    @Test
    void removalDoesNotChangePendingSnapshot() {
        Channel<Integer> a = new Channel<>(1);
        Channel<Integer> b = new Channel<>(1);
        DynamicSelectPlan<String> plan = new DynamicSelectPlan<>();
        plan.put("old", read(a));
        var pending = plan.selectAsync(SelectPolicy.PRIORITY);
        long before = plan.version();
        assertTrue(plan.remove("old"));
        plan.put("new", read(b));
        assertTrue(b.tryWrite(22));
        var current = plan.trySelect(SelectPolicy.PRIORITY).orElseThrow();
        assertEquals("new", current.key());
        assertEquals(22, current.result().value());
        assertTrue(a.tryWrite(11));
        var previous = pending.join();
        assertEquals(before, previous.version());
        assertEquals("old", previous.key());
        assertEquals(11, previous.result().value());
    }

    @Test
    void emptyBuilderFailsWithoutRegistering() {
        DynamicSelectPlan<String> plan = new DynamicSelectPlan<>();
        assertThrows(IllegalStateException.class, plan::snapshot);
        assertFalse(plan.remove("absent"));
        plan.put("only", defaultCase());
        var selected = plan.trySelect().orElseThrow();
        assertEquals("only", selected.key());
        assertEquals(SelectOperation.DEFAULT, selected.result().operation());
    }

    @Test
    void immutableSnapshotCannotBeChangedThroughKeys() {
        DynamicSelectPlan<String> plan = new DynamicSelectPlan<>();
        plan.put("one", defaultCase());
        var snapshot = plan.snapshot();
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.keys().add("two"));
        plan.remove("one");
        assertEquals(java.util.List.of("one"), snapshot.keys());
    }
}
