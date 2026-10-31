package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

/**
 * A dynamic plan caches case descriptors, not registrations. Tests exercise the
 * heterogeneous Future/Channel runtime and current pending-admission quotas.
 */
final class DynamicSelectPlanGenerationTest {
    @Test
    void repeatedLoopSelectionsReuseTheDescriptorButNotWinnerOrWaiters() {
        var channel = new ChannelRuntime.Channel<Integer>(1);
        var plan = new ChannelRuntime.DynamicSelectPlan<String>();
        plan.put("inbox", ChannelRuntime.read(channel));
        var descriptor = plan.selectSetSnapshot();

        for (int i = 0; i < 2048; i++) {
            assertTrue(channel.tryWrite(i));
            var result = plan.trySelectKeyed(ChannelRuntime.SelectPolicy.FAIR).orElseThrow();
            assertEquals("inbox", result.key());
            assertEquals(1L, result.version());
            assertEquals(i, result.selection().value());
            assertSame(descriptor, plan.selectSetSnapshot());
            assertEquals(0, channel.pendingRegistrations());
        }
    }

    @Test
    void pendingFutureKeepsOriginalKeyGenerationAndDetachesAfterReplacement() {
        var producer = new OresFuture<Integer>();
        var channel = new ChannelRuntime.Channel<Integer>(1);
        var plan = new ChannelRuntime.DynamicSelectPlan<String>();
        plan.put("old", ChannelRuntime.await(producer));
        plan.put("other", ChannelRuntime.read(channel));
        var original = plan.selectSetSnapshot();
        var pending = plan.selectKeyedAsync(ChannelRuntime.SelectPolicy.PRIORITY);
        assertFalse(pending.isDone());
        assertEquals(1, producer.pendingRuntimeWaiterCount());

        assertTrue(plan.remove("old"));
        plan.put("other", ChannelRuntime.read(channel));
        assertNotSame(original, plan.selectSetSnapshot());
        assertTrue(producer.completeFromRuntime(42));

        var resolved = pending.join();
        assertEquals("old", resolved.key());
        assertEquals(2L, resolved.version());
        assertEquals(0, resolved.selection().index());
        assertEquals(42, resolved.selection().value());
        assertEquals(0, producer.pendingRuntimeWaiterCount());
        assertEquals(0, channel.pendingRegistrations());
    }

    @Test
    void canceledPlanSelectionDetachesFutureAndChannelWithoutCancelingProducer() {
        var producer = new OresFuture<Integer>();
        var channel = new ChannelRuntime.Channel<Integer>(1);
        var plan = new ChannelRuntime.DynamicSelectPlan<String>();
        plan.put("future", ChannelRuntime.await(producer));
        plan.put("channel", ChannelRuntime.read(channel));
        var pending = plan.selectKeyedAsync(ChannelRuntime.SelectPolicy.FAIR);
        assertFalse(pending.isDone());
        assertTrue(pending.cancel(false));
        assertThrows(CancellationException.class, pending::join);

        assertEquals(0, producer.pendingRuntimeWaiterCount());
        assertEquals(0, channel.pendingRegistrations());
        assertFalse(producer.isDone());
        assertTrue(channel.tryWrite(9));
        assertEquals(9, channel.tryRead().orElseThrow());
    }

    @Test
    void defaultArmsMustNotAdvanceReusableFairnessCredit() {
        var first = new ChannelRuntime.Channel<Integer>(1);
        var second = new ChannelRuntime.Channel<Integer>(1);
        var plan = new ChannelRuntime.DynamicSelectPlan<String>();
        plan.put("first", ChannelRuntime.read(first));
        plan.put("default", ChannelRuntime.defaultCase());
        plan.put("second", ChannelRuntime.read(second));

        for (int i = 0; i < 16; i++) {
            var fallback = plan.trySelectKeyed(ChannelRuntime.SelectPolicy.FAIR).orElseThrow();
            assertEquals("default", fallback.key());
        }
        assertTrue(first.tryWrite(1));
        assertTrue(second.tryWrite(2));
        assertEquals("first", plan.trySelectKeyed(ChannelRuntime.SelectPolicy.FAIR)
                .orElseThrow().key());
        assertEquals("second", plan.trySelectKeyed(ChannelRuntime.SelectPolicy.FAIR)
                .orElseThrow().key());
    }

    @Test
    void duplicateDefaultAndArmQuotaRejectWithoutVersionOrSnapshotMutation() {
        var plan = new ChannelRuntime.DynamicSelectPlan<Integer>();
        plan.put(0, ChannelRuntime.defaultCase());
        var original = plan.selectSetSnapshot();
        long version = plan.version();
        assertThrows(IllegalArgumentException.class,
                () -> plan.put(1, ChannelRuntime.defaultCase()));
        assertEquals(version, plan.version());
        assertSame(original, plan.selectSetSnapshot());

        var channel = new ChannelRuntime.Channel<Integer>(1);
        for (int i = 1; i < ChannelRuntime.MAX_SELECT_CASES; i++) {
            plan.put(i, ChannelRuntime.read(channel));
        }
        assertEquals(ChannelRuntime.MAX_SELECT_CASES, plan.size());
        long fullVersion = plan.version();
        var fullSnapshot = plan.selectSetSnapshot();
        assertThrows(ChannelRuntime.SelectAdmissionException.class,
                () -> plan.put(ChannelRuntime.MAX_SELECT_CASES, ChannelRuntime.read(channel)));
        assertEquals(fullVersion, plan.version());
        assertEquals(ChannelRuntime.MAX_SELECT_CASES, plan.size());
        assertSame(fullSnapshot, plan.selectSetSnapshot());
        // Replacements at capacity do not increase the pending-arm budget.
        plan.put(1, ChannelRuntime.read(channel));
        assertEquals(ChannelRuntime.MAX_SELECT_CASES, plan.size());
    }

    @Test
    void emptyDynamicPlanIsNotSelectableAndMissingRemovalDoesNotBumpVersion() {
        var plan = new ChannelRuntime.DynamicSelectPlan<String>();
        assertThrows(IllegalStateException.class, plan::selectSetSnapshot);
        assertFalse(plan.remove("missing"));
        assertEquals(0L, plan.version());
    }

    @Test
    void futureOnlyPlanRetainsHeterogeneousSelectableSemantics() {
        var producer = new OresFuture<String>();
        var plan = new ChannelRuntime.DynamicSelectPlan<String>();
        plan.put("producer", ChannelRuntime.await(producer));
        assertSame(plan.selectSetSnapshot(), plan.selectSetSnapshot());
        var pending = plan.selectKeyedAsync(ChannelRuntime.SelectPolicy.FAIR);
        assertEquals(1, producer.pendingRuntimeWaiterCount());
        assertTrue(producer.completeFromRuntime("ready"));
        assertEquals("producer", pending.join().key());
        assertEquals(ChannelRuntime.SelectOperation.FUTURE,
                pending.join().selection().operation());
        assertEquals("ready", pending.join().selection().value());
        assertEquals(0, producer.pendingRuntimeWaiterCount());
    }
    @Test
    void orderedListFactoryPreservesOriginalKeysAndCopiesInputs() {
        var left = new ChannelRuntime.Channel<Integer>(1);
        var right = new ChannelRuntime.Channel<Integer>(1);
        List<ChannelRuntime.SelectCase> cases = new ArrayList<>();
        cases.add(ChannelRuntime.read(left));
        cases.add(ChannelRuntime.read(right));

        var dynamic = ChannelRuntime.DynamicSelectPlan.fromList(cases);
        var immutable = ChannelRuntime.SelectPlan.fromList(cases);
        cases.clear(); // mutations of the caller's input cannot rewrite either plan

        assertEquals(List.of(0, 1), dynamic.keys());
        assertEquals(2L, dynamic.version());
        assertEquals(2, immutable.cases().size());
        assertEquals(2, dynamic.selectSetSnapshot().cases().size());

        assertTrue(left.tryWrite(41));
        assertTrue(right.tryWrite(42));
        var leftResult = dynamic.trySelectKeyed(ChannelRuntime.SelectPolicy.FAIR).orElseThrow();
        assertEquals(0, leftResult.key());
        assertEquals(2L, leftResult.version());
        assertEquals(41, leftResult.selection().value());
        assertEquals(1, right.pendingValues());
        var rightResult = dynamic.trySelectKeyed(ChannelRuntime.SelectPolicy.FAIR).orElseThrow();
        assertEquals(1, rightResult.key());
        assertEquals(42, rightResult.selection().value());
        assertEquals(0, left.pendingRegistrations());
        assertEquals(0, right.pendingRegistrations());
    }

    @Test
    void mapFactoryRetainsStableKeysAndDoesNotRetainCallerMap() {
        var first = new ChannelRuntime.Channel<Integer>(1);
        var second = new ChannelRuntime.Channel<Integer>(1);
        Map<String, ChannelRuntime.SelectCase> cases = new LinkedHashMap<>();
        cases.put("input", ChannelRuntime.read(first));
        cases.put("other", ChannelRuntime.read(second));

        var dynamic = ChannelRuntime.DynamicSelectPlan.fromMap(cases);
        var immutable = ChannelRuntime.SelectPlan.fromMap(cases);
        cases.clear();

        assertEquals(List.of("input", "other"), dynamic.keys());
        assertEquals(2L, dynamic.version());
        assertEquals(2, immutable.cases().size());
        assertTrue(first.tryWrite(7));
        var result = dynamic.trySelectKeyed(ChannelRuntime.SelectPolicy.FAIR).orElseThrow();
        assertEquals("input", result.key());
        assertEquals(7, result.selection().value());
        assertEquals(0, second.pendingRegistrations());
    }

    @Test
    void listFactoryRejectsNullsDuplicateDefaultAndExcessiveArms() {
        assertThrows(NullPointerException.class,
                () -> ChannelRuntime.DynamicSelectPlan.fromList(null));
        assertThrows(NullPointerException.class,
                () -> ChannelRuntime.SelectPlan.fromList(null));
        List<ChannelRuntime.SelectCase> bad = new ArrayList<>();
        bad.add(ChannelRuntime.defaultCase());
        bad.add(null);
        assertThrows(NullPointerException.class,
                () -> ChannelRuntime.DynamicSelectPlan.fromList(bad));
        assertThrows(IllegalArgumentException.class,
                () -> ChannelRuntime.DynamicSelectPlan.fromList(
                        List.of(ChannelRuntime.defaultCase(), ChannelRuntime.defaultCase())));

        var channel = new ChannelRuntime.Channel<Integer>(1);
        var oversized = new ArrayList<ChannelRuntime.SelectCase>();
        for (int i = 0; i <= ChannelRuntime.MAX_SELECT_CASES; i++) {
            oversized.add(ChannelRuntime.read(channel));
        }
        assertThrows(ChannelRuntime.SelectAdmissionException.class,
                () -> ChannelRuntime.DynamicSelectPlan.fromList(oversized));
        assertThrows(ChannelRuntime.SelectAdmissionException.class,
                () -> ChannelRuntime.SelectPlan.fromList(oversized));
    }

}
