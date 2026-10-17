package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
final class ActorLifecyclePublicationTest {
    @Test
    void readyCallbacksNeverRunUnderActorLifecycleMonitor() throws Exception {
        var factoryEntered = new CountDownLatch(1);
        var releaseFactory = new CountDownLatch(1);
        var callbackRan = new CountDownLatch(1);
        var callbackUnderLock = new AtomicInteger();

        try (var runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(() -> {
                factoryEntered.countDown();
                try {
                    if (!releaseFactory.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("actor factory release timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("actor factory interrupted", interrupted);
                }
                return (message, context) -> { };
            });

            var actorsField = ActorRuntime.class.getDeclaredField("actors");
            actorsField.setAccessible(true);
            @SuppressWarnings("unchecked")
            var actors = (Map<ActorRuntime.ActorId, Object>) actorsField.get(runtime);
            var cell = actors.get(actor.id());
            assertNotNull(cell);

            Field lockField = cell.getClass().getDeclaredField("lifecycleLock");
            lockField.setAccessible(true);
            Object lifecycleLock = lockField.get(cell);

            var ready = actor.ready();
            assertTrue(factoryEntered.await(5, TimeUnit.SECONDS));

            ready.whenCompleteRuntime((ignored, failure) -> {
                if (Thread.holdsLock(lifecycleLock)) callbackUnderLock.incrementAndGet();
                callbackRan.countDown();
            });

            releaseFactory.countDown();
            assertTrue(callbackRan.await(5, TimeUnit.SECONDS));
            ready.get(5, TimeUnit.SECONDS);
            assertEquals(
                    0,
                    callbackUnderLock.get(),
                    "READY publication must not invoke dependent callbacks under lifecycleLock");

            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
        } finally {
            releaseFactory.countDown();
        }
    }
}
