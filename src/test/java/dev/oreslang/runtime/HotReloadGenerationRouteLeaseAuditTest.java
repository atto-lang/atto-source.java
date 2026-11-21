package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The actor source Generation is a separate code-image lifetime authority
 * from the actor cell's external Task pin counter. An errored lease debit must
 * not be consumed, and allocating a lease must precede charging its pin.
 */
@Timeout(40)
final class HotReloadGenerationRouteLeaseAuditTest {
    private static final String SOURCE =
            "pub routine main(): void { return; }";

    @Test
    void failedGenerationDebitRemainsRetryableAndBlocksImageRetirement()
            throws Exception {
        try (var manager = new HotReloadManager(
                IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            var generation = manager.stageActorGeneration("lease-test.ores", SOURCE);
            var first = generation.pinActorRoute();
            var second = generation.pinActorRoute();

            assertThrows(IllegalStateException.class,
                    () -> manager.retire(generation.id()));

            Field counter = generation.getClass().getDeclaredField("actorRoutePins");
            counter.setAccessible(true);
            synchronized (generation) {
                assertEquals(2, counter.getInt(generation));
                counter.setInt(generation, 0);
                try {
                    assertThrows(IllegalStateException.class, first::close,
                            "counter corruption must not consume the lease");
                } finally {
                    counter.setInt(generation, 2);
                }
            }

            var ready = new CountDownLatch(1);
            var unexpected = new AtomicReference<Throwable>();
            Thread[] racers = new Thread[16];
            for (int i = 0; i < racers.length; i++) {
                racers[i] = Thread.ofPlatform().start(() -> {
                    try {
                        if (!ready.await(5, TimeUnit.SECONDS))
                            throw new AssertionError("generation lease race start timeout");
                        first.close();
                        first.close();
                    } catch (Throwable failure) {
                        unexpected.compareAndSet(null, failure);
                    }
                });
            }
            ready.countDown();
            for (Thread racer : racers) {
                racer.join(5000);
                assertFalse(racer.isAlive());
            }
            assertNull(unexpected.get());
            assertEquals(1, counter.getInt(generation),
                    "racing close calls may debit one lease only once");
            assertThrows(IllegalStateException.class,
                    () -> manager.retire(generation.id()),
                    "second independent lease keeps executable image pinned");

            second.close();
            second.close();
            assertEquals(0, counter.getInt(generation));
            manager.retire(generation.id());
            assertTrue(generation.closed());
            assertThrows(IllegalStateException.class, generation::pinActorRoute);
        }
    }

    @Test
    void closeAndRetireCompeteWithoutAllowingLateGenerationPin()
            throws Exception {
        try (var manager = new HotReloadManager(
                IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            var generation = manager.stageActorGeneration("lease-race.ores", SOURCE);
            var first = generation.pinActorRoute();
            var start = new CountDownLatch(1);
            var failure = new AtomicReference<Throwable>();
            Thread retire = Thread.ofPlatform().start(() -> {
                try {
                    if (!start.await(5, TimeUnit.SECONDS))
                        throw new AssertionError("retire start timeout");
                    manager.retire(generation.id());
                } catch (IllegalStateException stillPinned) {
                    // Correct if the retirement request linearizes first.
                } catch (Throwable unexpected) {
                    failure.compareAndSet(null, unexpected);
                }
            });
            Thread release = Thread.ofPlatform().start(() -> {
                try {
                    if (!start.await(5, TimeUnit.SECONDS))
                        throw new AssertionError("release start timeout");
                    first.close();
                } catch (Throwable unexpected) {
                    failure.compareAndSet(null, unexpected);
                }
            });
            start.countDown();
            retire.join(5000);
            release.join(5000);
            assertFalse(retire.isAlive() || release.isAlive());
            assertNull(failure.get());
            manager.retire(generation.id());
            assertThrows(IllegalStateException.class, generation::pinActorRoute);
        }
    }
}
