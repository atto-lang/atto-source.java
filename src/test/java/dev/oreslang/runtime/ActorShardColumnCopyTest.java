package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
final class ActorShardColumnCopyTest {
    @Test
    void trustedActorCopiesIntoExistingBufferWithoutOverflow() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch finished = new CountDownLatch(1);
            AtomicReference<Throwable> failed = new AtomicReference<>();
            ActorRuntime.ActorRef<Integer> actor = runtime.spawnSharedTrusted(factory -> (message, turn) -> {
                try {
                    ActorShardColumns shard =
                            new ActorShardColumns(ActorShardColumns.Layout.SOA, 3, 2, 1);
                    shard.write(0, 1, 11);
                    shard.write(1, 1, 12);
                    shard.write(2, 1, 13);
                    double[] dest = {-1, -1, -1, -1, -1};
                    shard.copyColumnInto(1, dest, 1);
                    assertArrayEquals(new double[] {-1, 11, 12, 13, -1}, dest);
                    assertThrows(IndexOutOfBoundsException.class,
                            () -> shard.copyColumnInto(1, dest, 3));
                    assertThrows(IndexOutOfBoundsException.class,
                            () -> shard.copyColumnInto(1, dest, -1));
                    assertThrows(IndexOutOfBoundsException.class,
                            () -> shard.copyColumnInto(2, dest, 0));
                    shard.close();
                    java.lang.reflect.Field storage =
                            ActorShardColumns.class.getDeclaredField("storage");
                    storage.setAccessible(true);
                    assertNull(storage.get(shard),
                            "closed shared shard must detach its charged JVM backing array");
                    shard.close(); // idempotent budget release
                    assertThrows(IllegalStateException.class,
                            () -> shard.copyColumnInto(1, dest, 0));
                } catch (Throwable error) {
                    failed.set(error);
                } finally {
                    finished.countDown();
                }
            });
            actor.send(1);
            assertTrue(finished.await(5, TimeUnit.SECONDS));
            assertNull(failed.get());
        }
    }
}
