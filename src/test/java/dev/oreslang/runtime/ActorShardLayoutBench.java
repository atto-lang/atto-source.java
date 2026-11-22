package dev.oreslang.runtime;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Opt-in host microbenchmark. Run manually after compiling tests:
 * java ... dev.oreslang.runtime.ActorShardLayoutBench
 *
 * Not a JUnit gate: timing on shared CI runners is not reliable.
 * Reports dense-kernel performance, NOT the comparative speed of
 * 100,000 distinct actors or real parallel ECS Task scheduling.
 */
public final class ActorShardLayoutBench {
    private ActorShardLayoutBench() { }

    public static void main(String[] args) throws Exception {
        for (int rows : new int[] {1_000, 10_000, 100_000}) {
            for (ActorShardColumns.Layout layout : ActorShardColumns.Layout.values()) {
                try (ActorRuntime runtime = new ActorRuntime()) {
                    CountDownLatch complete = new CountDownLatch(1);
                    AtomicReference<Throwable> failure = new AtomicReference<>();
                    AtomicReference<Long> duration = new AtomicReference<>();
                    ActorRuntime.ActorRef<Integer> actor = runtime.spawnSharedTrusted(
                            factory -> (iteration, context) -> {
                                try {
                                    ActorShardColumns shard = new ActorShardColumns(
                                            layout, rows, 3,
                                            layout == ActorShardColumns.Layout.AOSOA ? 8 : 1);
                                    for (int i = 0; i < rows; i++) {
                                        shard.write(i, 0, i);
                                        shard.write(i, 1, 2);
                                    }
                                    for (int w = 0; w < 10; w++) shard.integrate(0, 1, 1);
                                    long begin = System.nanoTime();
                                    for (int i = 0; i < iteration; i++) {
                                        shard.integrate(0, 1, 1);
                                    }
                                    duration.set(System.nanoTime() - begin);
                                    if (shard.read(rows - 1, 0) != rows - 1 + 2.0 * (10 + iteration)) {
                                        throw new AssertionError("invalid benchmark result");
                                    }
                                } catch (Throwable e) {
                                    failure.set(e);
                                } finally {
                                    complete.countDown();
                                }
                            });
                    final int iterations = 200;
                    actor.send(iterations);
                    if (!complete.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("benchmark timed out");
                    }
                    if (failure.get() != null) throw new RuntimeException(failure.get());
                    System.out.printf("java rows=%d layout=%s ns_per_row_update=%.3f%n",
                            rows, layout, (double) duration.get() / (iterations * (double) rows));
                }
            }
        }
    }
}
