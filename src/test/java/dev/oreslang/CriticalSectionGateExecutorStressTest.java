package dev.oreslang;

import dev.oreslang.runtime.CriticalSectionGate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Host-only proof: actual queued logical tickets through pooled scheduler executors. */
public final class CriticalSectionGateExecutorStressTest {
    private static void check(boolean condition, String why) {
        if (!condition) throw new AssertionError(why);
    }

    public static void main(String[] args) throws Exception {
        final int batches = 2;
        final int perBatch = 5000;
        AtomicInteger increments = new AtomicInteger();
        AtomicInteger simultaneous = new AtomicInteger();
        AtomicInteger maxSimultaneous = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        AtomicReference<Throwable> firstError = new AtomicReference<>();
        try (ExecutorService workers = Executors.newFixedThreadPool(4)) {
            for (int batch = 0; batch < batches; batch++) {
                CriticalSectionGate gate = new CriticalSectionGate(batch + 1L, 8192);
                var owner = gate.enter(new Object(), batch + 1L);
                CountDownLatch finished = new CountDownLatch(perBatch);
                List<Integer> order = new ArrayList<>(perBatch);
                for (int i = 0; i < perBatch; i++) {
                    final int ordinal = i;
                    var ticket = gate.enter(new Object(), batch + 1L);
                    ticket.whenReadyOn(workers).whenComplete((granted, failure) -> {
                        try {
                            if (failure != null) {
                                failures.incrementAndGet();
                                firstError.compareAndSet(null, failure);
                                return;
                            }
                            granted.execute(() -> {
                                int active = simultaneous.incrementAndGet();
                                maxSimultaneous.accumulateAndGet(active, Math::max);
                                try {
                                    synchronized (order) { order.add(ordinal); }
                                    increments.incrementAndGet();
                                } finally {
                                    simultaneous.decrementAndGet();
                                }
                            });
                        } catch (Throwable e) {
                            failures.incrementAndGet();
                            firstError.compareAndSet(null, e);
                        } finally {
                            finished.countDown();
                        }
                    });
                }
                check(gate.queued() == perBatch, "FIFO was not bounded and queued");
                owner.execute(() -> {});
                check(finished.await(25, TimeUnit.SECONDS), "scheduler dispatch hung");
                check(failures.get() == 0, "scheduler failed: " + firstError.get());
                check(order.size() == perBatch, "missing critical turn");
                for (int i = 0; i < perBatch; i++)
                    check(order.get(i) == i, "FIFO order inversion at " + i);
                gate.retire();
                check(gate.quiescent(), "physical lease not released");
            }
        }
        check(increments.get() == batches * perBatch, "lost updates");
        check(maxSimultaneous.get() == 1, "overlapping protected body");
        System.out.println("PASS host critical pooled-scheduler turns=" + increments.get()
                + " maxSimultaneous=" + maxSimultaneous.get());
    }
}
