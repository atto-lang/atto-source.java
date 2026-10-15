package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The public channel read/write Future must share the source select's
 * cancellation decision, not cancel successfully after a committed delivery.
 */
final class ChannelMappedSelectionCancellationTest {

    @Test
    @Timeout(15)
    void committedSelectCannotBeCanceledBeforeItsMappedValueIsPublished()
            throws Exception {
        OresFuture<ChannelRuntime.SelectResult> source = new OresFuture<>();
        CountDownLatch sourceClaimed = new CountDownLatch(1);
        CountDownLatch releaseNotification = new CountDownLatch(1);
        AtomicReference<Throwable> producerFailure = new AtomicReference<>();

        // Registered first: hold the source's notification drain AFTER the
        // terminal CAS but BEFORE mapSelection's own observer is notified.
        source.whenCompleteRuntime((value, error) -> {
            sourceClaimed.countDown();
            try {
                if (!releaseNotification.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("source notification gate timed out");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        });
        OresFuture<Integer> mapped = ChannelRuntime.mapSelection(
                source, selected -> (Integer) selected.value());

        Thread producer = Thread.startVirtualThread(() -> {
            try {
                assertTrue(source.completeFromRuntime(new ChannelRuntime.SelectResult(
                        0, ChannelRuntime.SelectOperation.READ, 73)));
            } catch (Throwable failure) {
                producerFailure.set(failure);
            }
        });
        try {
            assertTrue(sourceClaimed.await(5, TimeUnit.SECONDS));
            assertTrue(source.isDone(), "selection has committed");
            assertFalse(mapped.isDone(), "mapped notification has not yet run");
            assertFalse(mapped.cancel(false),
                    "a committed selection must reject late mapped cancellation");
            assertFalse(mapped.isCancelled());
        } finally {
            releaseNotification.countDown();
            producer.join(5_000);
        }
        assertFalse(producer.isAlive(), "completion thread stuck");
        assertNull(producerFailure.get(), () -> "producer failed: " + producerFailure.get());
        assertEquals(73, mapped.get(5, TimeUnit.SECONDS));
    }

    @Test
    void cancelingPendingMappedReadCancelsUnderlyingSelection() {
        OresFuture<ChannelRuntime.SelectResult> source = new OresFuture<>();
        OresFuture<Integer> mapped = ChannelRuntime.mapSelection(
                source, selected -> (Integer) selected.value());

        assertTrue(mapped.cancel(false),
                "pending mapped read should revoke its source selection");
        assertTrue(mapped.isCancelled());
        assertTrue(source.isCancelled());
        assertThrows(CancellationException.class, mapped::join);
        assertThrows(CancellationException.class, source::join);
        assertEquals(0, source.pendingRuntimeWaiterCount());
    }

    @Test
    @Timeout(15)
    void committedSelectionFailureCannotBeHiddenByLateCancellation()
            throws Exception {
        OresFuture<ChannelRuntime.SelectResult> source = new OresFuture<>();
        CountDownLatch sourceClaimed = new CountDownLatch(1);
        CountDownLatch releaseNotification = new CountDownLatch(1);
        AtomicReference<Throwable> producerFailure = new AtomicReference<>();
        source.whenCompleteRuntime((value, error) -> {
            sourceClaimed.countDown();
            try {
                if (!releaseNotification.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("source notification gate timed out");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        });
        OresFuture<Integer> mapped = ChannelRuntime.mapSelection(
                source, selected -> (Integer) selected.value());
        Thread producer = Thread.startVirtualThread(() -> {
            try {
                assertTrue(source.failFromRuntime(
                        new ChannelRuntime.ChannelClosedException("closed")));
            } catch (Throwable failure) {
                producerFailure.set(failure);
            }
        });
        try {
            assertTrue(sourceClaimed.await(5, TimeUnit.SECONDS));
            assertTrue(source.isDone());
            assertFalse(mapped.isDone());
            assertFalse(mapped.cancel(false),
                    "already-failed selection must not become mapped cancellation");
        } finally {
            releaseNotification.countDown();
            producer.join(5_000);
        }
        assertFalse(producer.isAlive());
        assertNull(producerFailure.get(), () -> "producer failed: " + producerFailure.get());
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> mapped.get(5, TimeUnit.SECONDS));
        assertInstanceOf(ChannelRuntime.ChannelClosedException.class,
                failure.getCause());
        assertEquals("closed", failure.getCause().getMessage());
    }
}
