package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.*;

final class DynamicSelectPlanTest {
    @Test
    void addReplaceRemoveAndSnapshotIndexes() {
        var a = new ChannelRuntime.Channel<String>(2);
        var b = new ChannelRuntime.Channel<String>(2);
        var plan = new ChannelRuntime.DynamicSelectPlan<String>();
        assertThrows(IllegalStateException.class, plan::trySelect);
        plan.put("a", ChannelRuntime.read(a));
        plan.put("b", ChannelRuntime.read(b));
        assertEquals(List.of("a", "b"), plan.keys());

        b.tryWrite("one");
        var first = plan.trySelectKeyed(ChannelRuntime.SelectPolicy.PRIORITY).orElseThrow();
        assertEquals("b", first.key());
        assertEquals(1, first.selection().index());
        long version = first.version();

        plan.put("b", ChannelRuntime.read(a));
        assertEquals(List.of("a", "b"), plan.keys(), "replacing an entry must not reorder it");
        assertTrue(plan.remove("a"));
        assertFalse(plan.remove("a"));
        assertEquals(List.of("b"), plan.keys());
        a.tryWrite("two");
        var second = plan.trySelectKeyed(ChannelRuntime.SelectPolicy.PRIORITY).orElseThrow();
        assertEquals("b", second.key());
        assertEquals(0, second.selection().index());
        assertTrue(second.version() > version);
        assertEquals("two", second.selection().value());
    }

    @Test
    void inflightSelectionUsesOldSnapshotAfterMutation() {
        var oldChannel = new ChannelRuntime.Channel<String>(0);
        var newChannel = new ChannelRuntime.Channel<String>(1);
        var plan = new ChannelRuntime.DynamicSelectPlan<String>();
        plan.put("old", ChannelRuntime.read(oldChannel));
        var pending = plan.selectKeyedAsync(ChannelRuntime.SelectPolicy.PRIORITY);
        assertFalse(pending.isDone());

        plan.remove("old");
        plan.put("new", ChannelRuntime.read(newChannel));
        newChannel.tryWrite("new value");
        assertEquals("new", plan.selectKeyedAsync(ChannelRuntime.SelectPolicy.PRIORITY).join().key());

        assertTrue(oldChannel.tryWrite("old value"), "old waiter remains armed for its snapshot");
        var oldResult = pending.join();
        assertEquals("old", oldResult.key());
        assertEquals("old value", oldResult.selection().value());
        assertTrue(oldResult.version() < plan.version());
    }

    @Test
    void cancellationDetachesOldSnapshotEvenAfterRemoval() {
        var channel = new ChannelRuntime.Channel<String>(0);
        var plan = new ChannelRuntime.DynamicSelectPlan<String>();
        plan.put("work", ChannelRuntime.read(channel));
        var pending = plan.selectKeyedAsync(ChannelRuntime.SelectPolicy.FAIR);
        plan.remove("work");
        assertTrue(pending.cancel(false));
        assertThrows(CancellationException.class, pending::join);
        assertFalse(channel.tryWrite("orphan"), "cancelled waiter must not consume");
    }

    @Test
    void versionedSnapshotsShareFairnessAcrossReplacement() {
        var a = new ChannelRuntime.Channel<String>(2);
        var b = new ChannelRuntime.Channel<String>(2);
        var plan = new ChannelRuntime.DynamicSelectPlan<String>();
        plan.put("a", ChannelRuntime.read(a));
        plan.put("b", ChannelRuntime.read(b));
        a.tryWrite("a1");
        b.tryWrite("b1");
        assertEquals("a", plan.selectKeyedAsync(ChannelRuntime.SelectPolicy.FAIR).join().key());
        plan.put("b", ChannelRuntime.read(b));
        a.tryWrite("a2");
        assertEquals("b", plan.selectKeyedAsync(ChannelRuntime.SelectPolicy.FAIR).join().key());
    }

    @Test
    void cachesDescriptorsUntilMutationWithoutReusingPendingOperations() {
        var channel = new ChannelRuntime.Channel<Integer>(1);
        var plan = new ChannelRuntime.DynamicSelectPlan<String>();
        plan.put("reader", ChannelRuntime.read(channel));
        var before = plan.selectSetSnapshot();
        assertSame(before, plan.selectSetSnapshot(), "repeated selection should reuse descriptor snapshot");

        var pending = plan.selectKeyedAsync(ChannelRuntime.SelectPolicy.FAIR);
        plan.put("reader", ChannelRuntime.read(channel));
        var after = plan.selectSetSnapshot();
        assertNotSame(before, after, "mutation must publish a new immutable descriptor");
        assertSame(after, plan.selectSetSnapshot());
        assertEquals(1, before.cases().size());

        channel.tryWrite(77);
        assertEquals("reader", pending.join().key());
        assertEquals(77, pending.join().selection().value());
    }

    @Test
    void staleWinnerDoesNotRotateNewGenerationFairness() {
        var plan = new ChannelRuntime.DynamicSelectPlan<String>();
        var oldA = new ChannelRuntime.Channel<Integer>(0);
        var oldB = new ChannelRuntime.Channel<Integer>(0);
        var freshA = new ChannelRuntime.Channel<Integer>(1);
        var freshB = new ChannelRuntime.Channel<Integer>(1);
        plan.put("oldA", ChannelRuntime.read(oldA));
        plan.put("oldB", ChannelRuntime.read(oldB));
        var pending = plan.selectKeyedAsync(ChannelRuntime.SelectPolicy.FAIR);

        plan.remove("oldA");
        plan.remove("oldB");
        plan.put("freshA", ChannelRuntime.read(freshA));
        plan.put("freshB", ChannelRuntime.read(freshB));
        var newGeneration = plan.selectSetSnapshot();

        assertTrue(oldA.tryWrite(11));
        assertEquals("oldA", pending.join().key());
        assertSame(newGeneration, plan.selectSetSnapshot());
        assertTrue(freshA.tryWrite(21));
        assertTrue(freshB.tryWrite(22));
        assertEquals("freshA",
                plan.trySelectKeyed(ChannelRuntime.SelectPolicy.FAIR).orElseThrow().key());
    }

    @Test
    void defaultDispatchDoesNotAdvanceFairnessCursor() {
        var a = new ChannelRuntime.Channel<Integer>(1);
        var b = new ChannelRuntime.Channel<Integer>(1);
        var set = ChannelRuntime.SelectSet.of(
                ChannelRuntime.read(a),
                ChannelRuntime.defaultCase(),
                ChannelRuntime.read(b));
        assertEquals(ChannelRuntime.SelectOperation.DEFAULT,
                set.trySelect(ChannelRuntime.SelectPolicy.FAIR).orElseThrow().operation());
        assertTrue(a.tryWrite(11));
        assertTrue(b.tryWrite(22));
        assertEquals(0, set.trySelect(ChannelRuntime.SelectPolicy.FAIR).orElseThrow().index());
    }

    @Test
    void cachedSnapshotStillReusesCaseListAfterRepeatedSelects() {
        var plan = new ChannelRuntime.DynamicSelectPlan<String>();
        var channel = new ChannelRuntime.Channel<Integer>(3);
        plan.put("reader", ChannelRuntime.read(channel));
        var stable = plan.selectSetSnapshot();
        for (int i = 0; i < 3; i++) {
            assertTrue(channel.tryWrite(i));
            assertEquals(i, plan.trySelectKeyed(ChannelRuntime.SelectPolicy.FAIR)
                    .orElseThrow().selection().value());
            assertSame(stable, plan.selectSetSnapshot());
        }
    }

    @Test
    void rejectsSecondDefaultAtomicallyAndKeepsPreviousSnapshot() {
        var channel = new ChannelRuntime.Channel<String>(1);
        var plan = new ChannelRuntime.DynamicSelectPlan<String>();
        plan.put("default", ChannelRuntime.defaultCase());
        long before = plan.version();
        assertThrows(IllegalArgumentException.class,
                () -> plan.put("other", ChannelRuntime.defaultCase()));
        assertEquals(before, plan.version());
        assertEquals(List.of("default"), plan.keys());
        assertEquals("default",
                plan.trySelectKeyed(ChannelRuntime.SelectPolicy.PRIORITY).orElseThrow().key());

        plan.put("default", ChannelRuntime.read(channel));
        plan.put("other", ChannelRuntime.defaultCase());
        assertEquals("other",
                plan.trySelectKeyed(ChannelRuntime.SelectPolicy.PRIORITY).orElseThrow().key());
    }
}
