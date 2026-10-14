package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

/**
 * Regression: adding Future cases must not weaken existing per-channel quotas.
 * A rejected multi-source select has no retained Future listeners or channel arms.
 */
final class ChannelFutureSelectQuotaTest {
    @Test
    @org.junit.jupiter.api.Timeout(90)
    void globalArmQuotaIncludesFutureCasesAndRefundsOnCancellation() {
        // Use independent producers so this test isolates global arm credits.
        // Repeated aliases of a single Future are covered by the separate
        // ordering and listener-detachment regressions.
        var producers = new java.util.ArrayList<OresFuture<Integer>>();
        var operations = new java.util.ArrayList<ChannelRuntime.SelectCase>();
        for (int i = 0; i < ChannelRuntime.MAX_SELECT_CASES; i++) {
            var producer = new OresFuture<Integer>();
            producers.add(producer);
            operations.add(ChannelRuntime.await(producer));
        }
        var set = new ChannelRuntime.SelectSet(operations);
        int admitted = ChannelRuntime.MAX_PENDING_SELECT_ARM_REGISTRATIONS
                / ChannelRuntime.MAX_SELECT_CASES;
        assertEquals(0, ChannelRuntime.MAX_PENDING_SELECT_ARM_REGISTRATIONS
                % ChannelRuntime.MAX_SELECT_CASES,
                "quota fixture must fill the arm budget exactly");
        var accepted = new java.util.ArrayList<OresFuture<ChannelRuntime.SelectResult>>();
        try {
            for (int i = 0; i < admitted; i++) {
                var selection = set.selectAsync();
                assertFalse(selection.isDone(), "unexpected rejection at " + i);
                accepted.add(selection);
            }
            assertEquals(admitted, producers.getFirst().pendingRuntimeWaiterCount());

            var rejected = set.selectAsync();
            // Fail immediately on a quota regression instead of joining a
            // still-pending Future and hanging the CI runner indefinitely.
            assertTrue(rejected.isDone(), "excess arm selection must reject immediately");
            var failure = assertThrows(CompletionException.class, rejected::join);
            assertTrue(failure.getCause() instanceof ChannelRuntime.SelectAdmissionException);
            assertEquals(admitted, producers.getFirst().pendingRuntimeWaiterCount());
            assertFalse(producers.getFirst().isDone(),
                    "selection failure must not cancel its source");
        } finally {
            for (var selected : accepted) assertTrue(selected.cancel(false));
        }
        for (var producer : producers) {
            assertEquals(0, producer.pendingRuntimeWaiterCount(),
                    "cancellation must refund every Future listener");
            assertFalse(producer.isDone(), "cancellation must not settle the shared source");
        }

        var retry = set.selectAsync();
        assertEquals(1, producers.getFirst().pendingRuntimeWaiterCount());
        assertTrue(retry.cancel(false));
        assertEquals(0, producers.getFirst().pendingRuntimeWaiterCount());
    }

    @Test
    void channelQuotaRejectsWholeHeterogeneousSelectWithoutRetainingListeners() {
        var channel = new ChannelRuntime.Channel<Integer>(0, 1);
        var pendingProducer = new OresFuture<Integer>();
        var existing = ChannelRuntime.SelectSet.of(ChannelRuntime.read(channel)).selectAsync();
        assertEquals(1, channel.pendingRegistrations());

        var rejected = ChannelRuntime.SelectSet.of(
                ChannelRuntime.await(pendingProducer),
                ChannelRuntime.read(channel)).selectAsync();
        var thrown = assertThrows(CompletionException.class, rejected::join);
        assertTrue(thrown.getCause() instanceof ChannelRuntime.ChannelBackpressureException);
        assertEquals(1, channel.pendingRegistrations());
        assertEquals(0, pendingProducer.pendingRuntimeWaiterCount());
        assertFalse(pendingProducer.isDone());
        assertFalse(existing.isDone());

        assertTrue(existing.cancel(false));
        assertEquals(0, channel.pendingRegistrations());

        var reused = ChannelRuntime.SelectSet.of(ChannelRuntime.read(channel)).selectAsync();
        assertEquals(1, channel.pendingRegistrations());
        assertTrue(reused.cancel(false));
        assertEquals(0, channel.pendingRegistrations());
    }

    @Test
    void readyFutureCanWinBeforeQuotaCheckAndDoesNotConsumeAnyChannelArm() {
        var channel = new ChannelRuntime.Channel<Integer>(0, 1);
        var existing = ChannelRuntime.SelectSet.of(ChannelRuntime.read(channel)).selectAsync();
        try {
            var ready = OresFuture.completed(73);
            var result = ChannelRuntime.SelectSet.of(
                    ChannelRuntime.await(ready),
                    ChannelRuntime.read(channel))
                    .selectAsync(ChannelRuntime.SelectPolicy.PRIORITY)
                    .join();
            assertEquals(ChannelRuntime.SelectOperation.FUTURE, result.operation());
            assertEquals(73, result.value());
            assertEquals(1, channel.pendingRegistrations());
            assertEquals(0, ready.pendingRuntimeWaiterCount());
            assertFalse(existing.isDone());
        } finally {
            existing.cancel(false);
        }
        assertEquals(0, channel.pendingRegistrations());
    }

    @Test
    void duplicateArmsCountSeparatelyAndAdmissionCanRetryAfterCancel() {
        var channel = new ChannelRuntime.Channel<Integer>(0, 1);
        var rejected = ChannelRuntime.SelectSet.of(
                ChannelRuntime.read(channel),
                ChannelRuntime.read(channel)).selectAsync();
        var thrown = assertThrows(CompletionException.class, rejected::join);
        assertTrue(thrown.getCause() instanceof ChannelRuntime.ChannelBackpressureException);
        assertEquals(0, channel.pendingRegistrations());

        var one = ChannelRuntime.SelectSet.of(ChannelRuntime.read(channel)).selectAsync();
        assertEquals(1, channel.pendingRegistrations());
        assertTrue(one.cancel(false));
        assertEquals(0, channel.pendingRegistrations());
    }

    @Test
    void immediateProbeLeavesNoWaitersEvenAtPerChannelQuota() {
        var channel = new ChannelRuntime.Channel<Integer>(0, 1);
        var waiting = ChannelRuntime.SelectSet.of(ChannelRuntime.read(channel)).selectAsync();
        try {
            assertTrue(ChannelRuntime.SelectSet.of(
                    ChannelRuntime.read(channel)).trySelect().isEmpty());
            assertEquals(1, channel.pendingRegistrations());
        } finally {
            waiting.cancel(false);
        }
        assertEquals(0, channel.pendingRegistrations());
    }
}
