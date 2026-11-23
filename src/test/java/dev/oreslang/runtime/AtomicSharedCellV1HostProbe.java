package dev.oreslang.runtime;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/** JDK 21 host-only standalone test; never label it an Atto guest execution. */
public final class AtomicSharedCellV1HostProbe {
    private AtomicSharedCellV1HostProbe() {}

    private static void check(boolean value) {
        if (!value) throw new AssertionError("Arc/Weak host invariant");
    }
    private static void checkFails(Runnable action) {
        try { action.run(); }
        catch (IllegalStateException expected) { return; }
        throw new AssertionError("expected fail-closed ownership rejection");
    }

    private static void lifecycle() {
        AtomicInteger drops = new AtomicInteger();
        var root = AtomicSharedCellV1.new_owner(42, 5, 9, x -> drops.incrementAndGet());
        var sibling = root.clone_owner();
        var weak = root.downgrade();
        check(weak.upgrade(6, 9) == null);
        check(weak.upgrade(5, 8) == null);
        root.close();
        check(drops.get() == 0);
        try (var promoted = weak.upgrade(5, 9)) { check(promoted != null); }
        sibling.close();
        check(drops.get() == 1 && root.is_payload_detached());
        check(weak.upgrade(5, 9) == null);
        var secondWeak = weak.clone_owner();
        weak.close();
        check(!secondWeak.is_control_retired());
        secondWeak.close();
        check(secondWeak.is_control_retired());
        checkFails(root::close);
        checkFails(weak::close);
        checkFails(weak::clone_owner);
    }

    private static void destructorThrows() {
        AtomicInteger invocations = new AtomicInteger();
        var root = AtomicSharedCellV1.new_owner(1, 2, 3, x -> {
            invocations.incrementAndGet();
            throw new IllegalStateException("simulated destructor failure");
        });
        var weak = root.downgrade();
        checkFails(root::close);
        check(root.is_payload_detached() && invocations.get() == 1);
        check(weak.upgrade(2, 3) == null);
        weak.close();
        check(root.is_control_retired());
    }

    private static void race() throws Exception {
        for (int round = 0; round < 100; round++) {
            AtomicInteger drops = new AtomicInteger();
            var root = AtomicSharedCellV1.new_owner(99, 7, 20,
                x -> drops.incrementAndGet());
            int n = 8;
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch firstUpgrade = new CountDownLatch(1);
            java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                    new java.util.concurrent.atomic.AtomicReference<>();
            Thread[] threads = new Thread[n];
            for (int i = 0; i < n; i++) {
                var weak = root.downgrade();
                threads[i] = new Thread(() -> {
                    try {
                        start.await();
                        for (int j = 0; j < 1000; j++) {
                            var promoted = weak.upgrade(7, 20);
                            if (promoted != null) {
                                firstUpgrade.countDown();
                                promoted.close();
                            }
                        }
                        weak.close();
                    } catch (Throwable ex) { failure.compareAndSet(null, ex); }
                });
                threads[i].start();
            }
            start.countDown();
            // Prove at least one genuine upgrade before final strong release.
            // Otherwise scheduling could turn every run into a trivial failure test.
            check(firstUpgrade.await(5, java.util.concurrent.TimeUnit.SECONDS));
            root.close();
            for (Thread thread : threads) thread.join();
            if (failure.get() != null) {
                throw new AssertionError("Arc race worker failed", failure.get());
            }
            check(drops.get() == 1 && root.is_control_retired());
        }
    }

    public static void main(String[] args) throws Exception {
        lifecycle();
        destructorThrows();
        race();
        System.out.println("ATTO_ARC_ATOMIC_JAVA_HOST_PASS");
    }
}
