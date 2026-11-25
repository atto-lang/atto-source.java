package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Dependency-free, host-only contention probe; not a guest or IPC test. */
public final class ActorDataDecodeQuotaV1Contention {
    private static final int WORKERS = 16, ROUNDS = 256, CAP = 4, BYTES = 64;
    private static void check(boolean ok) {
        if (!ok) throw new AssertionError("quota invariant");
    }
    public static void main(String[] args) throws Exception {
        var quota = new ActorDataDecodeQuotaV1(CAP * BYTES, CAP);
        var barrier = new CyclicBarrier(WORKERS);
        var admitted = new AtomicInteger();
        var pool = Executors.newFixedThreadPool(WORKERS);
        var futures = new ArrayList<Future<?>>();
        try {
            for (int worker = 0; worker < WORKERS; worker++) {
                futures.add(pool.submit(() -> {
                    for (int round = 0; round < ROUNDS; round++) {
                        barrier.await(10, TimeUnit.SECONDS);
                        var lease = quota.tryReserve(BYTES);
                        try {
                            if (lease != null) admitted.incrementAndGet();
                            // All contenders have attempted admission before release.
                            barrier.await(10, TimeUnit.SECONDS);
                            check(quota.active() == CAP);
                            check(quota.usedBytes() == CAP * BYTES);
                            barrier.await(10, TimeUnit.SECONDS);
                        } finally {
                            if (lease != null) { lease.close(); lease.close(); }
                        }
                        barrier.await(10, TimeUnit.SECONDS);
                        check(quota.active() == 0 && quota.usedBytes() == 0);
                    }
                    return null;
                }));
            }
            for (var future : futures) future.get(45, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            check(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
        check(admitted.get() == CAP * ROUNDS);
        System.out.println("ACTOR_DATA_QUOTA_CONTENTION_HOST_PASS attempts=" + WORKERS * ROUNDS
                + " admitted=" + admitted.get() + " rejected=" + (WORKERS * ROUNDS - admitted.get()));
    }
}
