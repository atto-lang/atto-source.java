package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * Dependency-free verification against the actual production CONTROL retry queue.
 * Run with scripts/verify_actor_control_retry_standalone.sh.
 * This is not full actor integration, JUnit, a native carrier, or GraalVM evidence.
 */
public final class ControlWakeRetryStandaloneStress {
    private ControlWakeRetryStandaloneStress() {}

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    private static void verifyFairnessAndCapacity() {
        ControlWakeRetryQueue q = new ControlWakeRetryQueue(48);
        AtomicInteger oldest = new AtomicInteger();
        AtomicInteger local = new AtomicInteger();
        long epoch = q.epoch();
        require(q.register(oldest::incrementAndGet, epoch, 0), "oldest registration");
        for (int i = 0; i < 32; ++i) {
            require(q.register(local::incrementAndGet, epoch, 1), "local registration");
        }
        for (int i = 0; i < 16; ++i) q.slotReleased(1);
        require(oldest.get() == 0 && local.get() == 16,
                "locality should win first sixteen releases");
        q.slotReleased(1);
        require(oldest.get() == 1 && local.get() == 16,
                "FIFO head must be scheduled on seventeenth release");
        for (int i = 0; i < 16; ++i) q.slotReleased(1);
        require(q.pending() == 0 && local.get() == 32,
                "fairness must preserve all pending notifications");
        ControlWakeRetryQueue bounded = new ControlWakeRetryQueue(1);
        long old = bounded.epoch();
        require(bounded.register(() -> {}, old), "first registration");
        require(!bounded.register(() -> {}, old), "capacity overflow must fail closed");
        bounded.slotReleased();
        require(bounded.pending() == 0, "queued callback not drained");
    }

    private static void verifyLifecycleAndEpoch() {
        ControlWakeRetryQueue q = new ControlWakeRetryQueue(3);
        AtomicInteger called = new AtomicInteger();
        long beforeRelease = q.epoch();
        q.slotReleased();
        require(q.register(called::incrementAndGet, beforeRelease), "stale epoch admission");
        require(called.get() == 1 && q.pending() == 0, "stale-epoch callback stranded");
        Runnable cancelled = called::incrementAndGet;
        require(q.register(cancelled, q.epoch()), "cancellable admission");
        q.cancel(cancelled);
        q.slotReleased();
        require(called.get() == 1 && q.pending() == 0, "cancelled callback delivered");
        require(q.register(cancelled, q.epoch()), "close admission");
        q.close();
        q.slotReleased();
        require(called.get() == 1 && q.pending() == 0, "closed callback delivered");
        require(!q.register(cancelled, q.epoch()), "closed queue accepted registration");

        ControlWakeRetryQueue effects = new ControlWakeRetryQueue(1);
        require(effects.register(() -> { throw new AssertionError("nonfatal retry failure"); },
                effects.epoch()), "nonfatal callback admission");
        effects.slotReleased();
        require(effects.callbacksFailed() == 1, "nonfatal callback not accounted");
        require(effects.register(() -> { throw new InternalError("fatal VM failure"); },
                effects.epoch()), "fatal callback admission");
        boolean propagated = false;
        try { effects.slotReleased(); }
        catch (InternalError expected) { propagated = true; }
        require(propagated && effects.callbacksFailed() == 1,
                "fatal VM effect was swallowed or misclassified");
    }

    private static int verifyConcurrentRegistrations() throws Exception {
        final int rounds = 4;
        final int perRound = 25_000;
        int deliveredTotal = 0;
        for (int round = 0; round < rounds; ++round) {
            ControlWakeRetryQueue queue = new ControlWakeRetryQueue(perRound);
            AtomicIntegerArray seen = new AtomicIntegerArray(perRound);
            AtomicInteger cancelled = new AtomicInteger();
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService workers = Executors.newFixedThreadPool(12);
            List<Future<?>> tasks = new ArrayList<>();
            try {
                for (int producer = 0; producer < 8; ++producer) {
                    final int offset = producer;
                    tasks.add(workers.submit(() -> {
                        start.await();
                        for (int i = offset; i < perRound; i += 8) {
                            final int index = i;
                            Runnable callback = () -> seen.incrementAndGet(index);
                            if (!queue.register(callback, queue.epoch(), index % 11)) {
                                throw new AssertionError("registration rejected at " + index);
                            }
                            if (index % 19 == 0) {
                                queue.cancel(callback);
                                cancelled.incrementAndGet();
                            }
                        }
                        return null;
                    }));
                }
                for (int consumer = 0; consumer < 4; ++consumer) {
                    final int lane = consumer;
                    tasks.add(workers.submit(() -> {
                        start.await();
                        for (int i = 0; i < perRound; ++i) {
                            queue.slotReleased((lane + i) % 11);
                        }
                        return null;
                    }));
                }
                start.countDown();
                for (Future<?> task : tasks) task.get(30, TimeUnit.SECONDS);
                for (int i = 0; i < perRound; ++i) queue.slotReleased(i % 11);
                int deliveries = 0;
                for (int i = 0; i < perRound; ++i) {
                    int value = seen.get(i);
                    require(value <= 1, "duplicate callback at index " + i);
                    deliveries += value;
                }
                require(queue.pending() == 0, "retained pending callbacks after drain");
                require(deliveries >= perRound - cancelled.get(),
                        "missing noncancelled callbacks");
                deliveredTotal += deliveries;
            } finally {
                start.countDown();
                workers.shutdownNow();
                require(workers.awaitTermination(5, TimeUnit.SECONDS),
                        "concurrent test workers did not terminate");
            }
        }
        return deliveredTotal;
    }

    public static void main(String[] args) throws Exception {
        verifyFairnessAndCapacity();
        verifyLifecycleAndEpoch();
        int delivered = verifyConcurrentRegistrations();
        System.out.println("PASS: 100,000 concurrent registrations, " + delivered
                + " delivered; no duplicates, missing noncancelled callbacks, "
                + "or pending leaks; fairness, epoch, cancellation and fatality checked");
    }
}
