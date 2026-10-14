package dev.oreslang.runtime;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Dependency-free executable smoke test of the *actual* production queue.
 * Run scripts/verify-adt-queue.sh with JDK 21 or newer. A pass is not a
 * substitute for the JUnit/GraalVM/LLVM conformance release gates.
 */
public final class MatchContinuationQueueSmoke {
    private MatchContinuationQueueSmoke() { }

    public static void main(String[] args) throws Exception {
        smokeImmediateCompletionChain();
        smokeExactlyOnceCompletion();
        smokeTerminalCallbackFailure();
        smokeFatalError();
        smokeAbortSuppressesQueuedWork();
        smokeRaceBetweenCompletionAndWork();
        System.out.println("MatchContinuationQueue smoke: 6 checks passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void smokeImmediateCompletionChain() {
        MatchContinuationQueue queue = new MatchContinuationQueue();
        AtomicInteger seen = new AtomicInteger();
        Runnable[] next = new Runnable[1];
        next[0] = () -> {
            if (seen.incrementAndGet() == 4096) {
                queue.complete(() -> { });
            } else {
                queue.enqueue(next[0]);
            }
        };
        queue.enqueue(next[0]);
        require(seen.get() == 4096 && queue.terminated(), "4096 callback chain");
    }

    private static void smokeExactlyOnceCompletion() {
        MatchContinuationQueue queue = new MatchContinuationQueue();
        AtomicInteger delivered = new AtomicInteger();
        queue.enqueue(() -> {
            queue.enqueue(() -> delivered.addAndGet(100));
            queue.complete(delivered::incrementAndGet);
            queue.complete(delivered::incrementAndGet);
            queue.enqueue(delivered::incrementAndGet);
        });
        require(delivered.get() == 1, "terminal callback exactly once");
    }

    private static void smokeTerminalCallbackFailure() {
        MatchContinuationQueue queue = new MatchContinuationQueue();
        AtomicInteger postFailure = new AtomicInteger();
        try {
            queue.enqueue(() -> {
                queue.enqueue(postFailure::incrementAndGet);
                queue.complete(() -> { throw new IllegalStateException("terminal"); });
            });
            throw new AssertionError("terminal error did not propagate");
        } catch (IllegalStateException expected) {
            require("terminal".equals(expected.getMessage()), "unexpected terminal failure");
        }
        require(queue.terminated() && postFailure.get() == 0, "failed terminal abort");
    }

    private static void smokeFatalError() {
        MatchContinuationQueue queue = new MatchContinuationQueue();
        AtomicInteger stale = new AtomicInteger();
        try {
            queue.enqueue(() -> {
                queue.enqueue(stale::incrementAndGet);
                throw new AssertionError("fatal");
            });
            throw new AssertionError("fatal error did not propagate");
        } catch (AssertionError expected) {
            require("fatal".equals(expected.getMessage()), "fatal was replaced");
        }
        require(queue.terminated() && stale.get() == 0, "fatal abort");
    }

    private static void smokeAbortSuppressesQueuedWork() {
        MatchContinuationQueue queue = new MatchContinuationQueue();
        AtomicInteger stale = new AtomicInteger();
        queue.enqueue(() -> {
            queue.enqueue(stale::incrementAndGet);
            queue.abort();
        });
        require(queue.terminated() && stale.get() == 0, "cancelled queued work");
    }

    private static void smokeRaceBetweenCompletionAndWork() throws Exception {
        MatchContinuationQueue queue = new MatchContinuationQueue();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger stale = new AtomicInteger();
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            var task = executor.submit(() -> queue.enqueue(() -> {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("drain timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            }));
            require(entered.await(5, TimeUnit.SECONDS), "drain did not start");
            queue.enqueue(stale::incrementAndGet);
            queue.complete(() -> { });
            release.countDown();
            task.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
        require(stale.get() == 0, "late work ran after completion");
    }
}
