package dev.oreslang.runtime;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Source-free, dependency-free concurrency regression for the CGL trace sink. */
public final class CglActorMessageTraceConcurrencySmoke {
    private static void require(boolean ok, String detail) {
        if (!ok) throw new AssertionError(detail);
    }

    private static void exercise(int workers, int perWorker, int capacity) throws Exception {
        final UUID actor = UUID.randomUUID();
        final CglActorMessageTrace trace = new CglActorMessageTrace(capacity);
        final CountDownLatch begin = new CountDownLatch(1);
        final AtomicInteger accepted = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            @SuppressWarnings("unchecked")
            Future<?>[] tasks = new Future<?>[workers];
            for (int worker = 0; worker < workers; worker++) {
                final int id = worker;
                tasks[worker] = pool.submit(() -> {
                    try {
                        begin.await();
                        for (int i = 0; i < perWorker; i++) {
                            long sequence = (long) id * perWorker + i;
                            if (trace.record(actor, 1, sequence, 100 + sequence,
                                    101 + sequence, true)) {
                                accepted.incrementAndGet();
                            }
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(ex);
                    }
                });
            }
            begin.countDown();
            for (Future<?> task : tasks) task.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            require(pool.awaitTermination(5, TimeUnit.SECONDS), "worker shutdown");
        }
        trace.sealAfterActorsStopped();
        var snapshot = trace.snapshot();
        int total = workers * perWorker;
        require(snapshot.samples().size() == accepted.get(), "accepted count mismatch");
        require(snapshot.samples().size() == Math.min(total, capacity), "capacity mismatch");
        require(snapshot.dropped() == total - capacity ||
                (total <= capacity && snapshot.dropped() == 0), "lost record count");
        require(snapshot.invalid() == 0, "invalid records");
        require(snapshot.hasCompleteCapture(total) == (total <= capacity),
                "overflow should never pass complete capture");
        require(!snapshot.hasCompleteCapture(total + 1), "wrong expected count");
    }

    public static void main(String[] args) throws Exception {
        for (int repeat = 0; repeat < 10; repeat++) {
            exercise(8, 32, 256); // complete, zero loss
            exercise(8, 64, 128); // overflow, 384 drops
        }
    }
}
