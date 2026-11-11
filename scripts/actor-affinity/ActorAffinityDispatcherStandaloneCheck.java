package dev.oreslang.runtime;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Compiles and exercises the REAL ActorAffinityDispatcher with JVM executors.
 * Does not invoke JNI, bind a CPU, or claim guest runtime/AOT execution.
 */
public final class ActorAffinityDispatcherStandaloneCheck {
    private ActorAffinityDispatcherStandaloneCheck() { }

    private static void expect(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }

    public static void main(String[] args) throws Exception {
        var dispatcher = new ActorAffinityDispatcher(
                2, 4, new int[0], ignored -> Executors.newSingleThreadExecutor());
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var migrated = new CountDownLatch(1);
        var completed = new CountDownLatch(2);
        var originalRuns = new AtomicInteger();
        try {
            dispatcher.executeOnLane(0, () -> {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS))
                        throw new AssertionError("blocked actor turn timed out");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            expect(entered.await(5, TimeUnit.SECONDS), "first actor turn never started");
            for (int i = 0; i < 2; i++) {
                dispatcher.executeOnLane(0, () -> {
                    originalRuns.incrementAndGet();
                    completed.countDown();
                });
            }
            dispatcher.executeOnLane(0, migrated::countDown);
            expect(migrated.await(5, TimeUnit.SECONDS),
                    "bounded soft selector failed to spill onto idle lane");
            expect(dispatcher.softSpills() == 1, "spill count mismatch");
            expect(originalRuns.get() == 0, "queued actor turns bypassed blocked lane");
            release.countDown();
            expect(completed.await(5, TimeUnit.SECONDS), "preferred queue stalled");
            expect(originalRuns.get() == 2, "preferred queue lost work");
        } finally {
            release.countDown();
            dispatcher.shutdownNow();
            expect(dispatcher.awaitTermination(5, TimeUnit.SECONDS),
                    "actor lanes failed to terminate");
        }
        System.out.println("ActorAffinityDispatcherStandaloneCheck PASS");
    }
}
