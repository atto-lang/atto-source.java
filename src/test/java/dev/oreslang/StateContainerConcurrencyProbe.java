package dev.oreslang;

import dev.oreslang.runtime.ScalarStateContainer;
import dev.oreslang.runtime.StateContainerAccessGate;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static dev.oreslang.runtime.StateContainerAccessGate.Mode.*;

/** Bounded host-thread regression, not guest scheduler or fairness evidence. */
final class StateContainerConcurrencyProbe {
    private static void check(boolean ok) {
        if (!ok) throw new AssertionError("state concurrency invariant");
    }
    private static void await(CountDownLatch latch) throws InterruptedException {
        check(latch.await(10, TimeUnit.SECONDS));
    }
    private static void denied(Runnable operation) {
        try { operation.run(); } catch (IllegalStateException expected) { return; }
        throw new AssertionError("revoked lease accepted");
    }
    static void run() throws Exception {
        var pool = Executors.newFixedThreadPool(4, r -> {
            var t = new Thread(r); t.setDaemon(true); return t;
        });
        try {
            for (int round = 0; round < 128; round++) {
                var gate = new StateContainerAccessGate(2);
                var acquired = new CountDownLatch(2);
                var release = new CountDownLatch(1);
                Callable<Void> reader = () -> {
                    try (var lease = gate.tryRead()) {
                        check(lease != null);
                        gate.requireOwnedActive(lease, READ);
                        acquired.countDown();
                        await(release);
                        denied(() -> gate.requireOwnedActive(lease, READ));
                    }
                    return null;
                };
                var a = pool.submit(reader); var b = pool.submit(reader);
                try {
                    await(acquired);
                    check(gate.readerCount() == 2 && gate.tryWrite() == null);
                    gate.close();
                    check(!gate.leasesQuiescentAfterClose());
                    check(gate.tryRead() == null && gate.tryWrite() == null);
                } finally { release.countDown(); }
                a.get(10, TimeUnit.SECONDS); b.get(10, TimeUnit.SECONDS);
                check(gate.leasesQuiescentAfterClose());

                var writerGate = new StateContainerAccessGate(2);
                var held = new CountDownLatch(1);
                var exited = new CountDownLatch(1);
                var never = new CountDownLatch(1);
                var interrupted = new AtomicInteger();
                var task = pool.submit(() -> {
                    try (var lease = writerGate.tryWrite()) {
                        check(lease != null);
                        held.countDown();
                        await(never);
                    } catch (InterruptedException expected) {
                        interrupted.incrementAndGet();
                        Thread.currentThread().interrupt();
                    } finally { exited.countDown(); }
                });
                await(held);
                writerGate.close();
                check(!writerGate.leasesQuiescentAfterClose());
                check(task.cancel(true));
                await(exited); // cancellation of Future is not physical exit
                check(interrupted.get() == 1 && writerGate.leasesQuiescentAfterClose());
            }
            try (var state = new ScalarStateContainer(0, 4)) {
                var successes = new AtomicInteger();
                var values = ConcurrentHashMap.<Long>newKeySet();
                var start = new CountDownLatch(1);
                var futures = new java.util.ArrayList<Future<?>>();
                for (int k = 0; k < 4; k++) futures.add(pool.submit(() -> {
                    await(start);
                    for (int n = 0; n < 2048; n++) {
                        var result = state.tryAddChecked(1);
                        if (result.isPresent()) {
                            check(values.add(result.getAsLong()));
                            successes.incrementAndGet();
                        }
                    }
                    return null;
                }));
                start.countDown();
                for (var f : futures) f.get(10, TimeUnit.SECONDS);
                check(successes.get() > 0 && state.tryRead().orElseThrow() == successes.get());
                for (long n = 1; n <= successes.get(); n++) check(values.contains(n));
            }
            for (int round = 0; round < 128; round++) {
                try (var state = new ScalarStateContainer(0, 4)) {
                    var start = new CountDownLatch(1);
                    var futures = new java.util.ArrayList<Future<?>>();
                    for (int k = 0; k < 3; k++) futures.add(pool.submit(() -> {
                        await(start);
                        for (int n = 0; n < 64; n++) state.tryAddChecked(1);
                        return null;
                    }));
                    start.countDown();
                    state.close();
                    for (var f : futures) f.get(10, TimeUnit.SECONDS);
                    check(state.readyForReclamation());
                    check(state.tryAddChecked(1).isEmpty() && state.tryRead().isEmpty() && state.trySet(1).isEmpty());
                }
            }
        } finally {
            pool.shutdownNow();
            check(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }
    public static void main(String[] args) throws Exception {
        run();
        System.out.println("128 reader shutdowns, 128 interrupted writer releases, 8192 concurrent add attempts, 128 close races passed");
    }
}
