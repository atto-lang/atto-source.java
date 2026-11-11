package dev.oreslang.runtime;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Dependency-free executed safety harness. It exercises the exact production
 * CONTROL ledger with concurrent reservation, publish, failure fencing and
 * injected rollback, not a substitute for ActorRuntime JUnit or guest AOT.
 */
public final class SupervisorAtomicChildAdmissionStandalone {
    private static int checks;

    private SupervisorAtomicChildAdmissionStandalone() {}

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static void injectedRollback() {
        var ledger = new SupervisorFailureEscrowV1();
        ledger.registerRoot(1L, 1L);
        ledger.registerChild(2L, 1L, 2L);
        var registry = ConcurrentHashMap.<Long>newKeySet();
        try {
            ledger.registerChildAtomically(3L, 2L, 3L, () -> {
                registry.add(3L);
                try {
                    throw new IllegalStateException("injected registry failure");
                } finally {
                    registry.remove(3L);
                }
            });
            throw new AssertionError("expected publication rejection");
        } catch (IllegalStateException expected) {
            check(expected.getMessage().contains("injected"), "wrong rollback reason");
        }
        check(!ledger.snapshot().containsKey(3L), "CONTROL slot survived rollback");
        check(registry.isEmpty(), "runtime registry survived rollback");
        check(ledger.pendingCount() == 0, "rollback manufactured a terminal notice");
        ledger.fail(2L);
        check(!ledger.mayAdmit(2L, 2L), "failed parent still admitted");
        try {
            ledger.registerChildAtomically(4L, 2L, 4L,
                    () -> { throw new AssertionError("publisher called after fence"); });
            throw new AssertionError("fenced admission accepted");
        } catch (IllegalStateException expected) {
            check(!ledger.snapshot().containsKey(4L), "fenced child published");
        }
    }

    private static void race(int round) throws Exception {
        final int workers = 8;
        final int perWorker = 128;
        var ledger = new SupervisorFailureEscrowV1();
        ledger.registerRoot(1L, 1L);
        ledger.registerChild(2L, 1L, 2L);
        Set<Long> published = ConcurrentHashMap.newKeySet();
        var accepted = new AtomicInteger();
        var denied = new AtomicInteger();
        var start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(workers + 1);
        try {
            @SuppressWarnings("unchecked")
            Future<?>[] tasks = new Future<?>[workers + 1];
            for (int w = 0; w < workers; w++) {
                final int worker = w;
                tasks[w] = pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < perWorker; i++) {
                        final long id = 3L + worker * perWorker + i;
                        try {
                            ledger.registerChildAtomically(id, 2L, id, () -> {
                                if (!published.add(id)) {
                                    throw new AssertionError("duplicate publish " + id);
                                }
                            });
                            accepted.incrementAndGet();
                        } catch (IllegalStateException fenced) {
                            if (!fenced.getMessage().contains("cannot admit")) {
                                throw fenced;
                            }
                            denied.incrementAndGet();
                        }
                    }
                    return null;
                });
            }
            tasks[workers] = pool.submit(() -> {
                start.await();
                ledger.fail(2L);
                return null;
            });
            start.countDown();
            for (Future<?> task : tasks) task.get(20, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                throw new AssertionError("workers did not terminate");
            }
        }
        Map<Long, SupervisorFailureEscrowV1.NodeStatus> nodes = ledger.snapshot();
        check(accepted.get() + denied.get() == workers * perWorker,
                "lost reservation attempt in round " + round);
        check(accepted.get() == published.size(), "unbalanced registry publishes");
        check(nodes.size() == published.size() + 2, "orphan ledger child");
        for (long id : published) {
            var node = nodes.get(id);
            check(node != null && node.parentId() == 2L && node.fenced(),
                    "published child not fenced " + id);
        }
        check(ledger.pendingCount() == 1, "wrong parent failure count");
        check(!ledger.mayAdmit(2L, 2L), "parent reopened after failure");
    }

    public static void main(String[] args) throws Exception {
        injectedRollback();
        for (int round = 0; round < 16; round++) race(round);
        System.out.println(
                "PASS SupervisorAtomicChildAdmissionStandalone attempts=16384 checks="
                        + checks);
    }
}
