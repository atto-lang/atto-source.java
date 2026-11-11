package dev.oreslang.runtime;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Runs the real application-domain snapshot registry against cross-thread cycles. */
public final class SingletonCrossThreadCycleSmoke {
    private static int checks;

    private static void require(boolean yes, String message) {
        checks++;
        if (!yes) throw new AssertionError(message);
    }

    private static void ready(CountDownLatch latch) {
        latch.countDown();
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("initializer rendezvous timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted initializer rendezvous", e);
        }
    }

    private static boolean hasCycle(Throwable problem) {
        for (Throwable cause = problem; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null
                    && cause.getMessage().contains("singleton initializer cycle")) {
                return true;
            }
        }
        return false;
    }

    private static void crossThreadCycle(int size) throws Exception {
        var domain = new SingletonSharedDomain();
        var names = new SingletonSharedDomain.Key[size];
        for (int i = 0; i < size; i++) {
            names[i] = new SingletonSharedDomain.Key("pkg", "M", "S" + i);
        }
        var pool = Executors.newFixedThreadPool(size);
        var rendezvous = new CountDownLatch(size);
        @SuppressWarnings("unchecked")
        CompletableFuture<Object>[] tasks = new CompletableFuture[size];
        try {
            for (int i = 0; i < size; i++) {
                final int index = i;
                tasks[i] = CompletableFuture.supplyAsync(() ->
                    domain.snapshotOnce(names[index], () -> {
                        ready(rendezvous);
                        return domain.snapshotOnce(names[(index + 1) % size],
                                () -> 17L).join();
                    }).join(), pool);
            }
            // Every root of a cycle must terminate exceptionally, never hang.
            for (int i = 0; i < size; i++) {
                try {
                    tasks[i].orTimeout(5, TimeUnit.SECONDS).join();
                    throw new AssertionError("cyclic initializer unexpectedly succeeded");
                } catch (CompletionException e) {
                    require(hasCycle(e), "cycle must be diagnosed, not timeout");
                }
                final int at = i;
                var sticky = domain.snapshotOnce(names[at], () -> {
                    throw new AssertionError("failed singleton was initialized twice");
                });
                try {
                    sticky.join();
                    throw new AssertionError("failed singleton was not sticky");
                } catch (CompletionException e) {
                    require(hasCycle(e), "sticky failure retains cycle diagnostic");
                }
            }
        } finally {
            pool.shutdownNow();
            require(pool.awaitTermination(5, TimeUnit.SECONDS),
                    "cycle test leaves no initializer threads blocked");
        }
    }

    private static void acyclicIdentity() {
        var domain = new SingletonSharedDomain();
        var a = new SingletonSharedDomain.Key("pkg", "M", "A");
        var b = new SingletonSharedDomain.Key("pkg", "M", "B");
        var c = new SingletonSharedDomain.Key("pkg", "M", "C");
        Object first = domain.snapshotOnce(a, () -> domain.snapshotOnce(b,
                () -> domain.snapshotOnce(c, () -> 42L).join()).join()).join();
        require(first.equals(42L), "nested acyclic initialization returns value");
        require(domain.snapshotOnce(a, () -> 0L).join() == first,
                "nested canonical identity reused after completion");
    }

    public static void main(String[] args) throws Exception {
        checks = 0;
        crossThreadCycle(2);
        crossThreadCycle(3);
        acyclicIdentity();
        System.out.println("SINGLETON_CROSS_THREAD_CYCLE_PASS=true");
        System.out.println("SINGLETON_CROSS_THREAD_CYCLE_ASSERTIONS=" + checks);
    }

    private SingletonCrossThreadCycleSmoke() { }
}
