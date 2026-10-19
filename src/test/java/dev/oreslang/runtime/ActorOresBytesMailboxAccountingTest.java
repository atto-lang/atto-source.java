package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
final class ActorOresBytesMailboxAccountingTest {
    private static final long HEAP_LIMIT = 16L * 1024 * 1024;
    private static final long MESSAGE_BYTES =
            24L + UnaryTransportFrame.MAX_PAYLOAD_BYTES;

    private static IsolatePolicy boundedPolicy() {
        return new IsolatePolicy(
                IsolatePolicy.developer().capabilities(),
                HEAP_LIMIT,
                64,
                Duration.ofSeconds(30),
                false);
    }

    @Test
    void sharedMailboxChargesByteLengthAndFailsBeforeHeapCeiling() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ActorRuntime runtime = new ActorRuntime(
                boundedPolicy(),
                new ActorRuntime.DispatcherConfig(1, 1, 64, 128))) {
            var actor = runtime.<Object>spawnShared(() -> (message, context) -> {
                if ("hold".equals(message)) {
                    entered.countDown();
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                }
            });
            actor.send("hold");
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            long baseline = runtime.sharedMemoryBytes();
            OresBytes oneMiB = OresBytes.copyOf(
                    new byte[UnaryTransportFrame.MAX_PAYLOAD_BYTES]);

            for (int i = 0; i < 15; i++) actor.send(oneMiB);
            assertEquals(baseline + 15L * MESSAGE_BYTES, runtime.sharedMemoryBytes());
            assertTrue(runtime.actorMemoryBytes() <= HEAP_LIMIT);

            IllegalStateException rejected = assertThrows(
                    IllegalStateException.class, () -> actor.send(oneMiB));
            assertTrue(rejected.getMessage().contains("memory"));
            assertEquals(baseline + 15L * MESSAGE_BYTES, runtime.sharedMemoryBytes(),
                    "failed admission must not retain bytes");

            release.countDown();
            actor.drain().get(5, TimeUnit.SECONDS);
            assertEquals(baseline - scalarBytes("hold"), runtime.sharedMemoryBytes(),
                    "draining all queued messages must release mailbox bytes");
            actor.stop();
            actor.getDone().get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
    }

    @Test
    void privateMailboxChargesByteLengthAndFailsBeforeHeapCeiling() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ActorRuntime runtime = new ActorRuntime(
                boundedPolicy(),
                new ActorRuntime.DispatcherConfig(1, 1, 64, 128))) {
            var actor = runtime.<Object>spawnPrivate(() -> (message, context) -> {
                if ("hold".equals(message)) {
                    entered.countDown();
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                }
            });
            actor.send("hold");
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            long baseline = runtime.privateMemoryBytes();
            OresBytes oneMiB = OresBytes.copyOf(
                    new byte[UnaryTransportFrame.MAX_PAYLOAD_BYTES]);

            for (int i = 0; i < 15; i++) actor.send(oneMiB);
            assertEquals(baseline + 15L * MESSAGE_BYTES, runtime.privateMemoryBytes());
            assertTrue(runtime.actorMemoryBytes() <= HEAP_LIMIT);

            IllegalStateException rejected = assertThrows(
                    IllegalStateException.class, () -> actor.send(oneMiB));
            assertTrue(rejected.getMessage().contains("memory"));
            assertEquals(baseline + 15L * MESSAGE_BYTES, runtime.privateMemoryBytes(),
                    "failed admission must not retain bytes");

            release.countDown();
            actor.drain().get(5, TimeUnit.SECONDS);
            assertEquals(baseline - scalarBytes("hold"), runtime.privateMemoryBytes(),
                    "draining all queued messages must release mailbox bytes");
            actor.stop();
            actor.getDone().get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
    }

    private static long scalarBytes(String value) {
        return 40L + (long) value.length() * 2L;
    }
}
