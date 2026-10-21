package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CancellationException;

/**
 * Regression gates for forward-porting versioned plans onto the modern
 * ChannelRuntime: FutureCase, coordinator admission and static-site FAIR
 * ordering must remain compatible with fresh cancellation registrations.
 */
final class DynamicSelectMainlineInteropTest {

    @Test
    void channelOnlyPendingRendezvousNeedsNoFutureListener() {
        var rendezvous = new ChannelRuntime.Channel<Integer>(0);
        var other = new ChannelRuntime.Channel<Integer>(0);
        var plan = new ChannelRuntime.DynamicSelectPlan<String>();
        plan.put("rendezvous", ChannelRuntime.read(rendezvous));
        plan.put("other", ChannelRuntime.read(other));

        for (int i = 0; i < 128; i++) {
            var pending = plan.selectKeyedAsync(ChannelRuntime.SelectPolicy.FAIR);
            assertFalse(pending.isDone(), "no value should be available before rendezvous");
            assertTrue(rendezvous.tryWrite(i),
                    "the coordinator must still wake a pending channel-only selection");
            var selected = pending.join();
            assertEquals("rendezvous", selected.key());
            assertEquals(i, selected.selection().value());
            assertFalse(other.tryWrite(i),
                    "loser registrations must be detached after winner publication");
        }
    }

    @Test
    void channelOnlyCancellationReleasesAllRendezvousArms() {
        var a = new ChannelRuntime.Channel<Integer>(0);
        var b = new ChannelRuntime.Channel<Integer>(0);
        var set = ChannelRuntime.SelectSet.of(
                ChannelRuntime.read(a), ChannelRuntime.read(b));
        for (int i = 0; i < 128; i++) {
            var pending = set.selectAsync(ChannelRuntime.SelectPolicy.FAIR);
            assertFalse(pending.isDone());
            assertTrue(pending.cancel(false));
            assertThrows(CancellationException.class, pending::join);
            assertFalse(a.tryWrite(i));
            assertFalse(b.tryWrite(i));
        }
    }

    @Test
    void mixedPendingFutureAndChannelArmsRetainFutureWakeupAndDetachLoser() {
        var producer = new OresFuture<Integer>();
        var channel = new ChannelRuntime.Channel<Integer>(0);
        var plan = new ChannelRuntime.DynamicSelectPlan<String>();
        plan.put("future", ChannelRuntime.await(producer));
        plan.put("channel", ChannelRuntime.read(channel));

        var pending = plan.selectKeyedAsync(ChannelRuntime.SelectPolicy.PRIORITY);
        assertFalse(pending.isDone());
        assertTrue(channel.tryWrite(54));
        assertEquals("channel", pending.join().key());
        assertFalse(producer.isCancelled(),
                "losing select must not transfer cancellation to the producer");
        assertTrue(producer.completeFromRuntime(55),
                "a losing Future must retain its producer's settlement authority");
    }

    @Test
    void typedFutureCaseAndBufferedChannelRemainSelectableInOnePlan() {
        var inbox = new ChannelRuntime.Channel<Integer>(2);
        var plan = new ChannelRuntime.DynamicSelectPlan<String>();
        plan.put("channel", ChannelRuntime.read(inbox));
        plan.put("future", ChannelRuntime.await(OresFuture.completed(91)));
        var result = plan.trySelectKeyed(ChannelRuntime.SelectPolicy.PRIORITY)
                .orElseThrow();
        assertEquals("future", result.key());
        assertEquals(ChannelRuntime.SelectOperation.FUTURE,
                result.selection().operation());
        assertEquals(91, result.selection().value());
        assertEquals(2, plan.size());
    }

    @Test
    void oldFutureSnapshotAndNewGenerationRemainIndependent() {
        var producer = new OresFuture<Integer>();
        var inbox = new ChannelRuntime.Channel<Integer>(1);
        var plan = new ChannelRuntime.DynamicSelectPlan<String>();
        plan.put("future", ChannelRuntime.await(producer));

        var old = plan.selectKeyedAsync(ChannelRuntime.SelectPolicy.FAIR);
        assertFalse(old.isDone());
        plan.remove("future");
        plan.put("inbox", ChannelRuntime.read(inbox));
        assertTrue(inbox.tryWrite(17));
        var current = plan.trySelectKeyed(ChannelRuntime.SelectPolicy.FAIR)
                .orElseThrow();
        assertEquals("inbox", current.key());

        assertTrue(producer.completeFromRuntime(42));
        assertEquals("future", old.join().key());
        assertEquals(42, old.join().selection().value());
        assertTrue(old.join().version() < current.version());
    }

    @Test
    void cancellingSnapshotDetachesFutureListenerWithoutCancellingProducer() {
        var producer = new OresFuture<Integer>();
        var plan = new ChannelRuntime.DynamicSelectPlan<String>();
        plan.put("future", ChannelRuntime.await(producer));
        var pending = plan.selectKeyedAsync(ChannelRuntime.SelectPolicy.FAIR);
        assertTrue(pending.cancel(false));
        assertThrows(CancellationException.class, pending::join);
        assertFalse(producer.isCancelled(),
                "losing select must not cancel the producer future");
        assertTrue(producer.completeFromRuntime(9));
        assertTrue(producer.isDone());
    }

    @Test
    void staticTicketsCannotAdvanceAnIndependentPlanCursor() {
        var a = new ChannelRuntime.Channel<Integer>(4);
        var b = new ChannelRuntime.Channel<Integer>(4);
        var set = ChannelRuntime.SelectSet.of(
                ChannelRuntime.read(a), ChannelRuntime.read(b));
        for (int i = 0; i < 3; i++) {
            assertTrue(a.tryWrite(i));
            assertTrue(b.tryWrite(i));
        }

        assertEquals(1, set.tryStaticSelect(ChannelRuntime.SelectPolicy.FAIR, 1)
                .orElseThrow().index());
        assertEquals(0, set.trySelect(ChannelRuntime.SelectPolicy.FAIR)
                .orElseThrow().index());
        assertEquals(1, set.trySelect(ChannelRuntime.SelectPolicy.FAIR)
                .orElseThrow().index());
        assertEquals(0, set.tryStaticSelect(ChannelRuntime.SelectPolicy.FAIR, 2)
                .orElseThrow().index());
        assertEquals(0, set.trySelect(ChannelRuntime.SelectPolicy.FAIR)
                .orElseThrow().index());
    }

    @Test
    void multipleAliasesOfOneSettledFuturePreservePriorityAfterOrderOptimization() {
        var producer = OresFuture.completed(81);
        var plan = new ChannelRuntime.DynamicSelectPlan<String>();
        plan.put("first", ChannelRuntime.await(producer));
        plan.put("second", ChannelRuntime.await(producer));
        for (int i = 0; i < 512; i++) {
            var selected = plan.trySelectKeyed(ChannelRuntime.SelectPolicy.PRIORITY)
                    .orElseThrow();
            assertEquals("first", selected.key());
            assertEquals(0, selected.selection().index());
            assertEquals(81, selected.selection().value());
        }
    }

    @Test
    void mixedFutureChannelFairRotationUsesAdmissionOrder() {
        var ready = OresFuture.completed(19);
        var inbox = new ChannelRuntime.Channel<Integer>(8);
        var plan = new ChannelRuntime.DynamicSelectPlan<String>();
        plan.put("ready", ChannelRuntime.await(ready));
        plan.put("inbox", ChannelRuntime.read(inbox));
        for (int i = 0; i < 4; i++) assertTrue(inbox.tryWrite(i));

        var first = plan.trySelectKeyed(ChannelRuntime.SelectPolicy.FAIR).orElseThrow();
        var second = plan.trySelectKeyed(ChannelRuntime.SelectPolicy.FAIR).orElseThrow();
        var third = plan.trySelectKeyed(ChannelRuntime.SelectPolicy.FAIR).orElseThrow();
        assertEquals("ready", first.key());
        assertEquals("inbox", second.key());
        assertEquals("ready", third.key());
        assertEquals(19, first.selection().value());
        assertEquals(0, second.selection().value());
    }

    @Test
    void failedFutureIsAnAtomicTerminalSelectionNotAStaleReader() {
        var failure = new IllegalStateException("producer failed");
        var failed = OresFuture.<Integer>failed(failure);
        var channel = new ChannelRuntime.Channel<Integer>(1);
        var set = ChannelRuntime.SelectSet.of(
                ChannelRuntime.await(failed), ChannelRuntime.read(channel));
        assertThrows(java.util.concurrent.CompletionException.class,
                () -> set.selectAsync(ChannelRuntime.SelectPolicy.PRIORITY).join());

        assertTrue(channel.tryWrite(7));
        assertEquals(7, channel.tryRead().orElseThrow(),
                "failed selection must detach its loser and leave future writes readable");
        assertTrue(channel.tryRead().isEmpty());
    }

    @Test
    void randomOrderingStillProducesValidArmsWithoutRetainingWaiters() {
        var a = new ChannelRuntime.Channel<Integer>(1);
        var b = new ChannelRuntime.Channel<Integer>(1);
        var plan = new ChannelRuntime.DynamicSelectPlan<String>();
        plan.put("a", ChannelRuntime.read(a));
        plan.put("b", ChannelRuntime.read(b));
        for (int i = 0; i < 512; i++) {
            assertTrue(a.tryWrite(i));
            assertTrue(b.tryWrite(i));
            var chosen = plan.trySelectKeyed(ChannelRuntime.SelectPolicy.RANDOM)
                    .orElseThrow();
            assertTrue(chosen.selection().index() >= 0
                    && chosen.selection().index() < 2);
            assertEquals(chosen.key(),
                    chosen.selection().index() == 0 ? "a" : "b");
            var other = chosen.selection().index() == 0 ? b : a;
            assertTrue(other.tryRead().isPresent(),
                    "nonwinning arm must remain ready and retain its value");
        }
    }

    @Test
    void dynamicPlanRejectsExcessArmsWithoutChangingItsVersion() {
        var channel = new ChannelRuntime.Channel<Integer>(1);
        var plan = new ChannelRuntime.DynamicSelectPlan<Integer>();
        for (int i = 0; i < ChannelRuntime.MAX_SELECT_CASES; i++) {
            plan.put(i, ChannelRuntime.read(channel));
        }
        long before = plan.version();
        var stable = plan.selectSetSnapshot();
        assertThrows(ChannelRuntime.SelectAdmissionException.class,
                () -> plan.put(ChannelRuntime.MAX_SELECT_CASES,
                        ChannelRuntime.read(channel)));
        assertEquals(before, plan.version());
        assertSame(stable, plan.selectSetSnapshot());
        assertEquals(ChannelRuntime.MAX_SELECT_CASES, plan.size());

        // Replacing an existing slot does not increase resource usage.
        plan.put(0, ChannelRuntime.read(channel));
        assertEquals(before + 1, plan.version());
        assertEquals(ChannelRuntime.MAX_SELECT_CASES, plan.size());
    }

    @Test
    void allDefaultArmsCannotPolluteFairnessOnReusedSet() {
        var inbox = new ChannelRuntime.Channel<Integer>(1);
        var set = ChannelRuntime.SelectSet.of(
                ChannelRuntime.read(inbox), ChannelRuntime.defaultCase());
        for (int i = 0; i < 4; i++) {
            assertEquals(ChannelRuntime.SelectOperation.DEFAULT,
                    set.trySelect(ChannelRuntime.SelectPolicy.FAIR)
                            .orElseThrow().operation());
        }
        assertTrue(inbox.tryWrite(5));
        assertEquals(0, set.trySelect(ChannelRuntime.SelectPolicy.FAIR)
                .orElseThrow().index());
    }
}
