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
