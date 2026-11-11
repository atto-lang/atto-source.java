package dev.oreslang.runtime;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

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

    private static CompletableFuture<Object> lookup(
            SingletonSharedDomain domain, SingletonSharedDomain.Key key,
            Supplier<?> initializer, boolean checked) {
        return checked
                ? domain.snapshotOnceChecked(key, "a".repeat(64), initializer)
                : domain.snapshotOnce(key, initializer);
    }

    private static void crossThreadCycle(int size, boolean checked) throws Exception {
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
                    lookup(domain, names[index], () -> {
                        ready(rendezvous);
                        return lookup(domain, names[(index + 1) % size],
                                () -> 17L, checked).join();
                    }, checked).join(), pool);
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
                var sticky = lookup(domain, names[at], () -> {
                    throw new AssertionError("failed singleton was initialized twice");
                }, checked);
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

    private static void ignoredDirectCycleCannotPublish(boolean checked) {
        var domain = new SingletonSharedDomain();
        var key = new SingletonSharedDomain.Key("pkg", "M", "Sticky");
        var result = lookup(domain, key, () -> {
            // An initializer must not be able to mask a detected cycle by
            // discarding the failed nested receipt and returning a constant.
            var ignored = lookup(domain, key, () -> 91L, checked);
            require(ignored.isCompletedExceptionally(),
                    "direct recursive nested receipt must fail");
            return 42L;
        }, checked);
        try {
            result.join();
            throw new AssertionError("ignored recursive lookup published a successful singleton");
        } catch (CompletionException failure) {
            require(hasCycle(failure), "ignored nested cycle still poisons publication");
        }
        try {
            lookup(domain, key, () -> 91L, checked).join();
            throw new AssertionError("cycle failure was not sticky");
        } catch (CompletionException failure) {
            require(hasCycle(failure), "cycle failure remains sticky on subsequent lookup");
        }
    }

    private static void poisonDuringFrozenSnapshotMustWin(boolean checked)
            throws Exception {
        var domain = new SingletonSharedDomain();
        var a = new SingletonSharedDomain.Key("pkg", "M", "FreezeA");
        var b = new SingletonSharedDomain.Key("pkg", "M", "FreezeB");
        var bStarted = new CountDownLatch(1);
        var aEnteredFreeze = new CountDownLatch(1);
        var releaseFreeze = new CountDownLatch(1);
        var letBObserveA = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            // A mutable host map is only a test hook: freeze must copy it
            // before publication; guests cannot publish arbitrary source maps.
            Map<String, Object> freezeBarrier = new LinkedHashMap<>() {
                @Override public Set<Map.Entry<String, Object>> entrySet() {
                    aEnteredFreeze.countDown();
                    try {
                        if (!releaseFreeze.await(5, TimeUnit.SECONDS)) {
                            throw new AssertionError("freeze barrier timed out");
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError("freeze barrier interrupted", ex);
                    }
                    return super.entrySet();
                }
            };
            freezeBarrier.put("answer", 42L);
            var taskB = CompletableFuture.supplyAsync(() ->
                lookup(domain, b, () -> {
                    bStarted.countDown();
                    try {
                        if (!letBObserveA.await(5, TimeUnit.SECONDS)) {
                            throw new AssertionError("B cycle barrier timed out");
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(ex);
                    }
                    var cyclicReceipt = lookup(domain, a, () -> 99L, checked);
                    require(cyclicReceipt.isCompletedExceptionally(),
                            "B closes A->B->A while A is freezing");
                    return 7L; // Deliberately ignore B's rejected cycle receipt.
                }, checked).join(), pool);
            require(bStarted.await(5, TimeUnit.SECONDS), "B initialized");
            var taskA = CompletableFuture.supplyAsync(() ->
                lookup(domain, a, () -> {
                    // Establish A -> B before returning the host snapshot map.
                    lookup(domain, b, () -> 11L, checked);
                    return freezeBarrier;
                }, checked).join(), pool);
            require(aEnteredFreeze.await(5, TimeUnit.SECONDS),
                    "A began freezing before B closes the dependency cycle");
            letBObserveA.countDown();
            require(taskB.orTimeout(5, TimeUnit.SECONDS).join().equals(7L),
                    "B may recover after explicit failed nested lookup");
            releaseFreeze.countDown();
            try {
                taskA.orTimeout(5, TimeUnit.SECONDS).join();
                throw new AssertionError("poisoned freeze published a successful snapshot");
            } catch (CompletionException failure) {
                require(hasCycle(failure), "cycle poison defeats in-flight frozen candidate");
            }
            try {
                lookup(domain, a, () -> 8L, checked).join();
                throw new AssertionError("poisoned freeze lost sticky error");
            } catch (CompletionException failure) {
                require(hasCycle(failure), "poisoned freeze remains sticky");
            }
        } finally {
            releaseFreeze.countDown();
            letBObserveA.countDown();
            pool.shutdownNow();
            require(pool.awaitTermination(5, TimeUnit.SECONDS),
                    "freeze/poison race leaves no host carrier blocked");
        }
    }

    private static Object nestedChain(SingletonSharedDomain domain, int depth,
                                      int maxDepth, boolean checked) {
        var key = new SingletonSharedDomain.Key("pkg", "M", "Nested" + depth);
        return lookup(domain, key, () -> depth == maxDepth ? 42L
                : nestedChain(domain, depth + 1, maxDepth, checked), checked).join();
    }

    private static void depthBudgetIsStickyAndRecoverable(boolean checked) {
        var domain = new SingletonSharedDomain();
        try {
            nestedChain(domain, 0, 64, checked);
            throw new AssertionError("65 nested initializers bypassed depth budget");
        } catch (CompletionException e) {
            require(e.getMessage() != null && e.getMessage().contains(
                    "singleton initializer depth exceeded"),
                    "depth violation must report bounded admission, not overflow");
        }
        var first = new SingletonSharedDomain.Key("pkg", "M", "Nested0");
        try {
            lookup(domain, first, () -> {
                throw new AssertionError("failed nested singleton retried");
            }, checked).join();
            throw new AssertionError("depth failure was not sticky");
        } catch (CompletionException e) {
            require(e.getMessage() != null && e.getMessage().contains(
                    "singleton initializer depth exceeded"),
                    "depth rejection remains sticky for existing singleton");
        }
        Object safe = lookup(domain, new SingletonSharedDomain.Key(
                "pkg", "M", "AfterDepthFailure"), () -> 37L, checked).join();
        require(safe.equals(37L), "same domain can initialize a fresh key after depth failure");
    }

    public static void main(String[] args) throws Exception {
        checks = 0;
        crossThreadCycle(2, false);
        crossThreadCycle(3, false);
        crossThreadCycle(2, true);
        crossThreadCycle(3, true);
        ignoredDirectCycleCannotPublish(false);
        ignoredDirectCycleCannotPublish(true);
        depthBudgetIsStickyAndRecoverable(false);
        depthBudgetIsStickyAndRecoverable(true);
        poisonDuringFrozenSnapshotMustWin(false);
        poisonDuringFrozenSnapshotMustWin(true);
        acyclicIdentity();
        System.out.println("SINGLETON_CROSS_THREAD_CYCLE_PASS=true");
        System.out.println("SINGLETON_CROSS_THREAD_CYCLE_ASSERTIONS=" + checks);
    }

    private SingletonCrossThreadCycleSmoke() { }
}
