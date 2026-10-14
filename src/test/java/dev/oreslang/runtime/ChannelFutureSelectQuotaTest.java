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
