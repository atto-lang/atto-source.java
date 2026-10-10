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
    void planReusesImmutableDescriptorsUntilMutation() {
        DynamicSelectPlan<String> plan = new DynamicSelectPlan<>();
        Channel<Integer> channel = new Channel<>(1);
        plan.put("one", read(channel));
        var original = plan.snapshot();
        assertSame(original, plan.snapshot(),
                "unmodified dynamic select must not rebuild its case descriptors");
        assertFalse(plan.remove("missing"));
        plan.put("one", read(channel));
        assertSame(original, plan.snapshot(),
                "equivalent descriptor replacement is a no-op");
        plan.put("two", defaultCase());
        var modified = plan.snapshot();
        assertNotSame(original, modified);
        assertEquals(java.util.List.of("one"), original.keys());
        assertEquals(java.util.List.of("one", "two"), modified.keys());
    }

    @Test
    void oldPendingSelectCannotConsumeAfterCancellationAndRemoval() {
        DynamicSelectPlan<String> plan = new DynamicSelectPlan<>();
        Channel<Integer> channel = new Channel<>(1);
        plan.put("old", read(channel));
        var pending = plan.selectAsync();
        assertTrue(plan.remove("old"));
        assertTrue(pending.cancel(false));
        assertTrue(channel.tryWrite(17));
        assertEquals(17, channel.tryRead().orElseThrow());
    }

    @Test
    void fairSelectionRotatesAcrossRepeatedCallsToUnmodifiedPlan() {
        DynamicSelectPlan<String> plan = new DynamicSelectPlan<>();
        Channel<Integer> first = new Channel<>(2);
        Channel<Integer> second = new Channel<>(2);
        assertTrue(first.tryWrite(1));
        assertTrue(first.tryWrite(3));
        assertTrue(second.tryWrite(2));
        plan.put("first", read(first));
        plan.put("second", read(second));
        assertEquals("first", plan.trySelect().orElseThrow().key());
        assertEquals("second", plan.trySelect().orElseThrow().key());
        assertEquals("first", plan.trySelect().orElseThrow().key());
    }

    @Test
    void staleCompletedGenerationDoesNotChangeCurrentSnapshot() {
        DynamicSelectPlan<String> plan = new DynamicSelectPlan<>();
        Channel<Integer> old = new Channel<>(1);
        Channel<Integer> fresh = new Channel<>(1);
        plan.put("old", read(old));
        var pending = plan.selectAsync();
        plan.remove("old");
        plan.put("fresh", read(fresh));
        var active = plan.snapshot();
        assertTrue(old.tryWrite(10));
        assertEquals("old", pending.join().key());
        assertSame(active, plan.snapshot());
        assertEquals(java.util.List.of("fresh"), active.keys());
        assertTrue(fresh.tryWrite(20));
        assertEquals("fresh", plan.trySelect().orElseThrow().key());
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
