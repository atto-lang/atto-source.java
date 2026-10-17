package dev.oreslang;

import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.IsolatePolicy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Backward-compatibility stress for the physical max=1 default.
 * The future v2 parallel gate must not affect actors with no thread policy.
 */
final class ActorThreadPolicyRuntimeTest {
    @Test
    void manyConcurrentProducersDoNotCreateTwoPhysicalTurnsInOneActor() throws Exception {
        int producers = 12;
        int messagesEach = 16;
        int total = producers * messagesEach;
        var dispatcher = new ActorRuntime.DispatcherConfig(4, 4, 16);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), dispatcher)) {
            var active = new AtomicInteger();
            var maximum = new AtomicInteger();
            var received = new CountDownLatch(total);
            var first = new CountDownLatch(1);
            var ref = runtime.<Integer>spawnShared(() -> (message, context) -> {
                int executing = active.incrementAndGet();
                maximum.accumulateAndGet(executing, Math::max);
                try {
                    first.countDown();
                    Thread.sleep(1);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(interrupted);
                } finally {
                    active.decrementAndGet();
                    received.countDown();
                }
            });

            try (ExecutorService pool = Executors.newFixedThreadPool(producers)) {
                CountDownLatch begin = new CountDownLatch(1);
                List<Future<?>> senderTasks = new ArrayList<>();
                for (int i = 0; i < producers; i++) {
                    senderTasks.add(pool.submit(() -> {
                        try {
                            if (!begin.await(5, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("producer start barrier timed out");
                            }
                            for (int n = 0; n < messagesEach; n++) ref.send(n);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException(interrupted);
                        }
                    }));
                }
                begin.countDown();
                assertTrue(first.await(5, TimeUnit.SECONDS), "actor was never scheduled");
                for (Future<?> task : senderTasks) task.get(10, TimeUnit.SECONDS);
                assertTrue(received.await(15, TimeUnit.SECONDS), "not every admitted message completed");
            }
            assertEquals(1, maximum.get(), "default actor must retain exclusive carrier admission");
            assertEquals(0, active.get(), "all physical turns must leave before teardown");
        }
    }
}
