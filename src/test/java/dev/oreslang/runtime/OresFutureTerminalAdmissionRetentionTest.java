package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Settled Futures must not retain their cancellation-admission closure.
 * Select admission closes over the entire SelectRegistration, including case
 * descriptors, channels, producer Futures, and buffered write payloads.
 */
final class OresFutureTerminalAdmissionRetentionTest {
    private static BooleanSupplier admission(OresFuture<?> future) throws Exception {
        Field field = OresFuture.class.getDeclaredField("cancelAdmission");
        field.setAccessible(true);
        return (BooleanSupplier) field.get(future);
    }

    private static Runnable cancellationHook(OresFuture<?> future) throws Exception {
        Field field = OresFuture.class.getDeclaredField("cancelHook");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        AtomicReference<Runnable> hook =
                (AtomicReference<Runnable>) field.get(future);
        return hook.get();
    }

    private static Runnable drainedCallback(OresFuture<?> future) throws Exception {
        Field field = OresFuture.class.getDeclaredField("onNotificationDrained");
        field.setAccessible(true);
        return (Runnable) field.get(future);
    }

    @Test
    void terminalCallbacksRunOnceAndTheirCapturesAreReleased() throws Exception {
        AtomicInteger successRevocation = new AtomicInteger();
        AtomicInteger successDrain = new AtomicInteger();
        OresFuture<Integer> success = new OresFuture<>(
                () -> true, successRevocation::incrementAndGet,
                successDrain::incrementAndGet);
        assertNotNull(cancellationHook(success));
        assertNotNull(drainedCallback(success));
        assertTrue(success.completeFromRuntime(7));
        assertNull(cancellationHook(success));
        assertNull(drainedCallback(success));
        assertEquals(0, successRevocation.get());
        assertEquals(1, successDrain.get());
        assertFalse(success.completeFromRuntime(8));
        assertEquals(1, successDrain.get());

        AtomicInteger failedRevocation = new AtomicInteger();
        AtomicInteger failedDrain = new AtomicInteger();
        OresFuture<Integer> failed = new OresFuture<>(
                () -> true, failedRevocation::incrementAndGet,
                failedDrain::incrementAndGet);
        assertTrue(failed.failFromRuntime(new IllegalStateException("failed")));
        assertNull(cancellationHook(failed));
        assertNull(drainedCallback(failed));
        assertEquals(0, failedRevocation.get());
        assertEquals(1, failedDrain.get());

        AtomicInteger cancelRevocation = new AtomicInteger();
        AtomicInteger cancelDrain = new AtomicInteger();
        OresFuture<Integer> canceled = new OresFuture<>(
                () -> true, cancelRevocation::incrementAndGet,
                cancelDrain::incrementAndGet);
        assertTrue(canceled.cancel(false));
        assertNull(cancellationHook(canceled));
        assertNull(drainedCallback(canceled));
        assertEquals(1, cancelRevocation.get());
        assertEquals(1, cancelDrain.get());
        assertFalse(canceled.cancel(false));
        assertEquals(1, cancelRevocation.get());
        assertEquals(1, cancelDrain.get());
    }

    @Test
    void successfulFailedAndCancelledFuturesDropAdmissionCapability() throws Exception {
        OresFuture<Integer> completed = new OresFuture<>(
                () -> true, () -> { });
        assertNotNull(admission(completed));
        assertTrue(completed.completeFromRuntime(17));
        assertNull(admission(completed));
        assertFalse(completed.cancel(false));
        assertEquals(17, completed.get(5, TimeUnit.SECONDS));

        OresFuture<Integer> failed = new OresFuture<>(
                () -> true, () -> { });
        assertNotNull(admission(failed));
        assertTrue(failed.failFromRuntime(new IllegalStateException("sentinel")));
        assertNull(admission(failed));
        assertFalse(failed.cancel(false));
        ExecutionException failure = assertThrows(
                ExecutionException.class, () -> failed.get(5, TimeUnit.SECONDS));
        assertEquals("sentinel", failure.getCause().getMessage());

        OresFuture<Integer> cancelled = new OresFuture<>(
                () -> true, () -> { });
        assertNotNull(admission(cancelled));
        assertTrue(cancelled.cancel(false));
        assertNull(admission(cancelled));
        assertTrue(cancelled.isCancelled());
        assertThrows(CancellationException.class, cancelled::join);
        assertFalse(cancelled.cancel(false));
    }

    @Test
    void completedChannelReadWriteAndSelectFuturesDropCaseCaptures()
            throws Exception {
        var channel = new ChannelRuntime.Channel<Integer>(2);
        OresFuture<Void> write = channel.writeAsync(41);
        assertNull(write.get(5, TimeUnit.SECONDS));
        assertNull(admission(write), "mapped write must not retain its source select");

        OresFuture<Integer> read = channel.readAsync();
        assertEquals(41, read.get(5, TimeUnit.SECONDS));
        assertNull(admission(read), "mapped read must not retain its source select");

        OresFuture<ChannelRuntime.SelectResult> selected =
                ChannelRuntime.SelectSet.of(ChannelRuntime.write(channel, 57))
                        .selectAsync(ChannelRuntime.SelectPolicy.PRIORITY);
        assertEquals(ChannelRuntime.SelectOperation.WRITE,
                selected.get(5, TimeUnit.SECONDS).operation());
        assertNull(admission(selected),
                "completed select must not retain channel and write-case descriptors");
        assertEquals(57, channel.tryRead().orElseThrow());
    }

    @Test
    void canceledPendingChannelFutureDropsAdmissionAndReleasesRegistrations()
            throws Exception {
        var channel = new ChannelRuntime.Channel<Integer>(0);
        OresFuture<Integer> reader = channel.readAsync();
        assertNotNull(admission(reader));
        assertEquals(1, channel.pendingRegistrations());
        assertTrue(reader.cancel(false));
        assertNull(admission(reader));
        assertEquals(0, channel.pendingRegistrations());
        assertThrows(CancellationException.class, reader::join);
    }

    @Test
    @Timeout(15)
    void admissionReferenceIsReleasedBeforeSynchronousSubscriberDrainFinishes()
            throws Exception {
        OresFuture<Integer> future = new OresFuture<>(
                () -> true, () -> { });
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        AtomicReference<Throwable> workerError = new AtomicReference<>();
        future.whenCompleteRuntime((value, failure) -> {
            callbackEntered.countDown();
            try {
                if (!releaseCallback.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("listener release timed out");
                }
            } catch (InterruptedException interruption) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interruption);
            }
        });
        Thread settler = Thread.startVirtualThread(() -> {
            try {
                assertTrue(future.completeFromRuntime(101));
            } catch (Throwable failure) {
                workerError.set(failure);
            }
        });
        try {
            assertTrue(callbackEntered.await(5, TimeUnit.SECONDS));
            assertTrue(future.isDone());
            assertNull(admission(future),
                    "terminal CAS must drop the admission closure before callbacks");
            assertNull(cancellationHook(future),
                    "successful CAS must release cancel hook before listener drain");
            assertNotNull(drainedCallback(future),
                    "notification drain release must wait until subscribers return");
            assertFalse(future.cancel(false));
        } finally {
            releaseCallback.countDown();
            settler.join(5_000);
        }
        assertFalse(settler.isAlive(), "subscriber drain stalled");
        assertNull(workerError.get(), () -> "settlement failure: " + workerError.get());
        assertNull(drainedCallback(future),
                "completed notification drain must release callback ownership");
        assertEquals(101, future.get(5, TimeUnit.SECONDS));
    }
}
