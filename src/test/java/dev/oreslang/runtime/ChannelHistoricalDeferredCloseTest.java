package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression for a selection whose registration was removed before its
 * synchronous Future observer finished. A later close must not infer that
 * physical notifications are drained merely from zero live registrations.
 *
 * IMPORTANT: This test is a release blocker until the close certificate
 * accounts for already-committed asynchronous selections, not just
 * registrations visible at the instant close() begins.
 */
@Timeout(20)
final class ChannelHistoricalDeferredCloseTest {
    @Test
    void previouslyUnregisteredSelectCanStillHaveAnInFlightCallback()
            throws Exception {
        var channel = new ChannelRuntime.Channel<Integer>(0);
        var future = channel.readAsync();
        var callbackEntered = new CountDownLatch(1);
        var callbackRelease = new CountDownLatch(1);
        var writerFailure = new AtomicReference<Throwable>();
        future.whenCompleteRuntime((result, failure) -> {
            callbackEntered.countDown();
            try {
                if (!callbackRelease.await(5, TimeUnit.SECONDS))
                    throw new AssertionError("callback release deadline exceeded");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("callback unexpectedly interrupted", interrupted);
            }
        });
        Thread writer = Thread.ofPlatform().start(() -> {
            try {
                channel.writeAsync(12).get(5, TimeUnit.SECONDS);
            } catch (Throwable failure) {
                writerFailure.set(failure);
            }
        });
        try {
            assertTrue(callbackEntered.await(5, TimeUnit.SECONDS),
                    "committed receive must reach its synchronous observer");
            assertEquals(0, channel.pendingRegistrations(),
                    "selection may be unregistered while observer is still active");
            channel.close();
            assertFalse(channel.closeSettledWithoutSelectorsForTeardown(),
                    "historical async callback is in-flight despite zero registrations");
        } finally {
            callbackRelease.countDown();
            writer.join(5000);
        }
        assertFalse(writer.isAlive());
        assertNull(writerFailure.get());
    }
}
