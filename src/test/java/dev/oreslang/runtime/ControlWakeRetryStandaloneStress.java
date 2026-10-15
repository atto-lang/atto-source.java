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

    private static void verifyCrossCarrierDeferredCancellation() throws Exception {
        ControlWakeRetryQueue queue = new ControlWakeRetryQueue(2);
        CountDownLatch deferred = new CountDownLatch(1);
        CountDownLatch unblock = new CountDownLatch(1);
        AtomicInteger invoked = new AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<Throwable> error =
                new java.util.concurrent.atomic.AtomicReference<>();
        Runnable victim = invoked::incrementAndGet;
        long epoch = queue.epoch();
        require(queue.register(() -> {
            queue.slotReleased(); // dequeue victim onto this carrier's trampoline
            deferred.countDown();
            try {
                if (!unblock.await(3, TimeUnit.SECONDS)) {
                    throw new AssertionError("deferred carrier timeout");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("deferred carrier interrupted", interrupted);
            }
        }, epoch), "outer carrier admission");
        require(queue.register(victim, epoch), "victim admission");
        Thread carrier = new Thread(() -> {
            try {
                queue.slotReleased();
            } catch (Throwable failure) {
                error.set(failure);
            }
        }, "standalone-deferred-control-carrier");
        carrier.setDaemon(true);
        carrier.start();
        try {
            require(deferred.await(3, TimeUnit.SECONDS), "carrier did not defer callback");
            queue.cancel(victim); // a different thread from the carrier
        } finally {
            unblock.countDown();
        }
        carrier.join(3000);
        require(!carrier.isAlive(), "carrier never completed");
        require(error.get() == null, "carrier errored: " + error.get());
        require(invoked.get() == 0, "foreign carrier cancellation did not revoke wake");
        require(queue.pending() == 0 && queue.callbacksFailed() == 0,
                "cross-carrier cancellation leaked or failed");
    }

    private static void verifyFatalDeferredCleanup() {
        // A fatal VM error must not leave another supervisor wake pinned in
        // a pooled carrier's trampoline, or block the next legitimate wake.
        for (int round = 0; round < 100; ++round) {
            ControlWakeRetryQueue queue = new ControlWakeRetryQueue(3);
            AtomicInteger invoked = new AtomicInteger();
            long epoch = queue.epoch();
            require(queue.register(() -> {
                queue.slotReleased();
                throw new InternalError("synthetic fatal host callback");
            }, epoch), "fatal test outer registration");
            require(queue.register(invoked::incrementAndGet, epoch),
                    "fatal test deferred registration");
            boolean fatal = false;
            try { queue.slotReleased(); }
            catch (InternalError expected) { fatal = true; }
            require(fatal, "fatal VM error was swallowed");
            require(invoked.get() == 0 && queue.pending() == 0
                    && queue.callbacksFailed() == 0,
                    "fatal VM effect replayed deferred wake or left retry state");
            require(queue.register(invoked::incrementAndGet, queue.epoch()),
                    "registration after fatal callback rejected");
            queue.slotReleased();
            require(invoked.get() == 1,
                    "fatal callback poisoned subsequent carrier admission");
        }
    }

    private static void verifyDequeueHandoffCancellation() throws Exception {
        // Instrument only the test instance's private ThreadLocal to pause
        // between a real slotReleased() dequeue and callback-start admission.
        // No hook or guest entrypoint is added to the production queue.
        for (boolean closing : new boolean[]{false, true}) {
            for (int round = 0; round < 64; ++round) {
                ControlWakeRetryQueue q = new ControlWakeRetryQueue(1);
                AtomicInteger delivered = new AtomicInteger();
                Runnable callback = delivered::incrementAndGet;
                require(q.register(callback, q.epoch()), "handoff registration");
                CountDownLatch dequeued = new CountDownLatch(1);
                CountDownLatch resume = new CountDownLatch(1);
                java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                        new java.util.concurrent.atomic.AtomicReference<>();
                java.lang.reflect.Field field =
                        ControlWakeRetryQueue.class.getDeclaredField("retryDispatch");
                field.setAccessible(true);
                Object original = field.get(q);
                field.set(q, new ThreadLocal<Object>() {
                    @Override public Object get() {
                        dequeued.countDown();
                        try {
                            if (!resume.await(3, TimeUnit.SECONDS)) {
                                throw new AssertionError("CONTROL handoff gate timed out");
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(interrupted);
                        }
                        return null;
                    }
                });
                Thread carrier = new Thread(() -> {
                    try { q.slotReleased(); }
                    catch (Throwable problem) { failure.set(problem); }
                }, "control-dequeued-before-cancel");
                carrier.setDaemon(true);
                carrier.start();
                try {
                    require(dequeued.await(3, TimeUnit.SECONDS),
                            "CONTROL callback was not dequeued");
                    require(q.pending() == 0 && q.retained() == 1,
                            "CONTROL ticket vanished before callback-start");
                    if (closing) q.close();
                    else q.cancel(callback);
                    require(q.retained() == 0,
                            "revoked in-transit CONTROL ticket retained quota");
                    java.lang.reflect.Field liveTickets =
                            ControlWakeRetryQueue.class.getDeclaredField("aboutToInvoke");
                    liveTickets.setAccessible(true);
                    synchronized (q) {
                        require(((java.util.Set<?>)liveTickets.get(q)).isEmpty(),
                                "cancel/close retained dequeued callback metadata");
                    }
                } finally {
                    resume.countDown();
                }
                carrier.join(3000);
                require(!carrier.isAlive() && failure.get() == null,
                        "CONTROL carrier failed: " + failure.get());
                require(delivered.get() == 0,
                        "cancelled dequeued CONTROL callback was delivered");
                field.set(q, original);
                if (!closing) {
                    require(q.register(callback, q.epoch()),
                            "cancel prevented later valid re-registration");
                    q.slotReleased();
                    require(delivered.get() == 1,
                            "next valid CONTROL callback was not delivered");
                } else {
                    require(!q.register(callback, q.epoch()),
                            "closed CONTROL queue accepted a registration");
                }
                require(q.retained() == 0, "CONTROL ticket leaked after handoff test");
            }
        }
    }

    private static void verifyRetainedAdmissionUnderReentry() {
        // A callback can fill the ordinary pending queue, immediately
        // dequeue itself onto the same carrier's deferred trampoline, then
        // repeat. The pending queue looks empty between iterations but the
        // total retained scheduler authority must remain bounded.
        for (boolean staleEpoch : new boolean[]{false, true}) {
            final int capacity = 4;
            final int attempts = 100_000;
            ControlWakeRetryQueue queue = new ControlWakeRetryQueue(capacity);
            AtomicInteger admitted = new AtomicInteger();
            AtomicInteger delivered = new AtomicInteger();
            require(queue.register(() -> {
                for (int i = 0; i < attempts; ++i) {
                    long observed = queue.epoch();
                    if (staleEpoch) --observed;
                    if (queue.register(delivered::incrementAndGet, observed)) {
                        admitted.incrementAndGet();
                        if (!staleEpoch) queue.slotReleased();
                    }
                    require(queue.retained() >= 0 && queue.retained() <= capacity,
                            "CONTROL retained callbacks exceeded admission budget");
                }
                require(queue.retained() == capacity && queue.pending() == 0,
                        "deferred callbacks did not retain their admission slots");
            }, queue.epoch()), "initial retry trampoline registration");
            queue.slotReleased();
            require(admitted.get() == capacity && delivered.get() == capacity,
                    "fresh/stale nested CONTROL registration bypassed budget");
            require(queue.pending() == 0 && queue.retained() == 0,
                    "deferred CONTROL retry capacity was not recovered");
        }
    }

    private static void verifyDeepReentrantWakes() {
        final int chained = 20_000;
        ControlWakeRetryQueue queue = new ControlWakeRetryQueue(chained);
        AtomicInteger delivered = new AtomicInteger();
        long epoch = queue.epoch();
        for (int i = 0; i < chained; ++i) {
            require(queue.register(() -> {
                delivered.incrementAndGet();
                // The old recursive callback implementation exhausted a
                // 512-KiB carrier stack before completing this chain.
                queue.slotReleased();
            }, epoch), "reentrant registration");
        }
        queue.slotReleased();
        require(delivered.get() == chained && queue.pending() == 0,
                "deep nested wake lost a callback or overflowed the carrier stack");

        final int each = 10_000;
        ControlWakeRetryQueue first = new ControlWakeRetryQueue(each);
        ControlWakeRetryQueue second = new ControlWakeRetryQueue(each);
        AtomicInteger firstDelivered = new AtomicInteger();
        AtomicInteger secondDelivered = new AtomicInteger();
        long firstEpoch = first.epoch(), secondEpoch = second.epoch();
        for (int i = 0; i < each; ++i) {
            require(first.register(() -> {
                firstDelivered.incrementAndGet();
                second.slotReleased();
            }, firstEpoch), "first chained registration");
            require(second.register(() -> {
                secondDelivered.incrementAndGet();
                first.slotReleased();
            }, secondEpoch), "second chained registration");
        }
        first.slotReleased();
        require(firstDelivered.get() == each && secondDelivered.get() == each
                && first.pending() == 0 && second.pending() == 0,
                "interleaved CONTROL retries stranded a notice");
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

    private static void verifyOtherThreadDeferredCancellation() throws Exception {
        // Exercise the production trampoline without JUnit/Maven. Before
        // cross-thread registration, cancel() could only inspect its own
        // ThreadLocal; the deferred wake on another carrier still ran.
        for (int round = 0; round < 64; ++round) {
            ControlWakeRetryQueue queue = new ControlWakeRetryQueue(2);
            CountDownLatch deferred = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger unwanted = new AtomicInteger();
            Runnable cancelled = unwanted::incrementAndGet;
            long epoch = queue.epoch();
            require(queue.register(() -> {
                queue.slotReleased();
                deferred.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("carrier gate timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            }, epoch), "outer retry admission");
            require(queue.register(cancelled, epoch), "deferred retry admission");
            Thread carrier = new Thread(queue::slotReleased, "retry-cancel-carrier");
            carrier.setDaemon(true);
            carrier.start();
            try {
                require(deferred.await(5, TimeUnit.SECONDS),
                        "carrier did not defer second callback");
                // The main thread differs from the callback's carrier.
                queue.cancel(cancelled);
            } finally {
                release.countDown();
            }
            carrier.join(5000);
            require(!carrier.isAlive(), "deferred callback carrier hung");
            require(unwanted.get() == 0 && queue.pending() == 0,
                    "cross-thread cancelled retry escaped its queue");
        }
    }

    public static void main(String[] args) throws Exception {
        verifyFairnessAndCapacity();
        verifyLifecycleAndEpoch();
        verifyCrossCarrierDeferredCancellation();
        verifyDequeueHandoffCancellation();
        verifyRetainedAdmissionUnderReentry();
        verifyDeepReentrantWakes();
        verifyFatalDeferredCleanup();
        verifyOtherThreadDeferredCancellation();
        int delivered = verifyConcurrentRegistrations();
        System.out.println("PASS: 100,000 concurrent registrations, " + delivered
                + " delivered; no duplicates, missing noncancelled callbacks, "
                + "or pending leaks; fairness, epoch, cancellation and fatality checked");
    }
}
