package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Deterministic and raced source-task/channel-registration ownership checks. */
final class SourceChannelWaitOwnershipTest {
    @Test
    void cancellingSourceTaskDetachesAllMultiArmReadWaiters() {
        ChannelRuntime.Channel<String> control = new ChannelRuntime.Channel<>(1);
        ChannelRuntime.Channel<String> data = new ChannelRuntime.Channel<>(1);
        ChannelRuntime.SelectPlan plan = ChannelRuntime.SelectPlan.of(
                ChannelRuntime.read(control), ChannelRuntime.read(data));
        SourceChannelWaitOwnership owner = new SourceChannelWaitOwnership();
        OresFuture<ChannelRuntime.SelectResult> pending = plan.selectAsync();
        owner.arm(pending, false);
        assertFalse(pending.isDone());

        owner.cancellationRequested();
        assertThrows(CancellationException.class, pending::join);
        assertTrue(control.tryWrite("control-late"));
        assertTrue(data.tryWrite("data-late"));
        assertEquals("control-late", control.tryRead().orElseThrow());
        assertEquals("data-late", data.tryRead().orElseThrow());
    }

    @Test
    void cancellingOwnedPendingWriterDoesNotPublishItsPayload() {
        ChannelRuntime.Channel<String> output = new ChannelRuntime.Channel<>(1);
        assertTrue(output.tryWrite("first"));
        OresFuture<Void> blocked = output.writeAsync("must-not-arrive");
        SourceChannelWaitOwnership owner = new SourceChannelWaitOwnership();
        owner.arm(blocked, false);
        owner.cancellationRequested();
        assertThrows(CancellationException.class, blocked::join);
        assertEquals("first", output.tryRead().orElseThrow());
        assertTrue(output.tryRead().isEmpty());
    }

    @Test
    void lexicalCleanupCanWaitAfterSourceCancellation() {
        ChannelRuntime.Channel<Integer> cleanup = new ChannelRuntime.Channel<>(1);
        SourceChannelWaitOwnership owner = new SourceChannelWaitOwnership();
        owner.cancellationRequested();
        OresFuture<Integer> pending = cleanup.readAsync();
        owner.arm(pending, true);
        assertFalse(pending.isDone(),
                "cleanup-only source waits must remain authorized after cancellation");
        assertTrue(cleanup.tryWrite(42));
        assertEquals(42, pending.join());
        owner.resumed(pending);
        owner.abort();
        assertFalse(pending.isCancelled());
    }

    @Test
    void registeringAfterStopIsImmediatelyRevoked() {
        ChannelRuntime.Channel<Integer> channel = new ChannelRuntime.Channel<>(0);
        SourceChannelWaitOwnership owner = new SourceChannelWaitOwnership();
        owner.cancellationRequested();
        OresFuture<Integer> pending = channel.readAsync();
        owner.arm(pending, false);
        assertTrue(pending.isCancelled());
        assertFalse(channel.tryWrite(7));
    }

    @Test
    void concurrentCancellationAndArmCannotLeaveLateReadRegistered() throws Exception {
        for (int iteration = 0; iteration < 250; iteration++) {
            SourceChannelWaitOwnership owner = new SourceChannelWaitOwnership();
            ChannelRuntime.Channel<Integer> channel = new ChannelRuntime.Channel<>(1);
            OresFuture<Integer> pending = channel.readAsync();
            CountDownLatch begin = new CountDownLatch(1);
            Thread canceller = Thread.ofPlatform().start(() -> {
                try {
                    if (!begin.await(5, TimeUnit.SECONDS))
                        throw new AssertionError("cancellation race timed out");
                    owner.cancellationRequested();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            });
            begin.countDown();
            owner.arm(pending, false);
            canceller.join(5_000);
            assertFalse(canceller.isAlive());
            assertTrue(pending.isCancelled(), "race " + iteration);
            assertTrue(channel.tryWrite(iteration), "race " + iteration);
            assertEquals(iteration, channel.tryRead().orElseThrow(),
                    "cancelled source wait cannot steal later value");
        }
    }

    @Test
    void genericFutureRemainsUnownedAndIsNeverCancelledByThisGate() {
        OresFuture<String> producer = new OresFuture<>();
        SourceChannelWaitOwnership owner = new SourceChannelWaitOwnership();
        owner.cancellationRequested();
        assertFalse(producer.isDone(),
                "only source-created channel operations may be owned and cancelled");
        assertTrue(producer.completeFromRuntime("producer-still-live"));
        assertEquals("producer-still-live", producer.join());
    }
}
