package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fail-closed generation lease retirement when accounting errors and
 * multi-threaded callback cancellation/release race upgrade freeze.
 */
@Timeout(30)
final class ActorHotUpgradeExternalLeaseRaceTest {
    @Test
    void damagedAccountingDoesNotConsumeLeaseAndConcurrentRetryDebitsExactlyOnce()
            throws Exception {
        try (var runtime = new ActorRuntime()) {
            var actor = runtime.<Integer>spawnShared(
                    () -> (message, context) -> context.self().stop());
            actor.getReady().get(5, TimeUnit.SECONDS);
            var first = runtime.pinExternalWorkForHotUpgrade(actor);
            var second = runtime.pinExternalWorkForHotUpgrade(actor);
            try (var freeze = runtime.freezeUserIngressForHotUpgrade(actor)) {
                assertEquals(2, freeze.outstandingExternalPins());
                assertFalse(freeze.schedulerDrained());
                var field = first.getClass().getDeclaredField("cell");
                field.setAccessible(true);
                Object cell = field.get(first);
                var counter = cell.getClass().getDeclaredField("hotUpgradeExternalPins");
                counter.setAccessible(true);
                var monitor = cell.getClass().getDeclaredField("lifecycleLock");
                monitor.setAccessible(true);
                synchronized (monitor.get(cell)) {
                    counter.setInt(cell, 0);
                    try {
                        assertThrows(IllegalStateException.class, first::close,
                                "failed debit must not irrevocably close lease");
                    } finally {
                        counter.setInt(cell, 2);
                    }
                }
                assertEquals(2, freeze.outstandingExternalPins());
                assertFalse(freeze.schedulerDrained());
                var go = new CountDownLatch(1);
                var failure = new AtomicReference<Throwable>();
                var threads = new ArrayList<Thread>();
                for (int i = 0; i < 16; i++) {
                    var thread = Thread.ofPlatform().start(() -> {
                        try {
                            if (!go.await(5, TimeUnit.SECONDS))
                                throw new AssertionError("release start timeout");
                            first.close();
                            first.close();
                        } catch (Throwable t) {
                            failure.compareAndSet(null, t);
                        }
                    });
                    threads.add(thread);
                }
                go.countDown();
                for (var thread : threads) {
                    thread.join(5000);
                    assertFalse(thread.isAlive(), "release contender did not finish");
                }
                assertNull(failure.get());
                assertEquals(1, freeze.outstandingExternalPins(),
                        "16 racing releasers must debit one lease only once");
                assertFalse(freeze.schedulerDrained(),
                        "second independent lease still owns old generation");
                second.close();
                second.close();
                assertEquals(0, freeze.outstandingExternalPins());
                assertTrue(freeze.schedulerDrained());
            } finally {
                first.close();
                second.close();
            }
            actor.send(1);
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
