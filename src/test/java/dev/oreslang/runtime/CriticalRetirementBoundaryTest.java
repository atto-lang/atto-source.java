package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Deterministic safe-entry and retirement boundaries; no sleep-based races. */
final class CriticalRetirementBoundaryTest {
    @Test void throwingRetirementSettlesFailureAndRetainsUnsafePermit() throws Exception {
        var queue = new ArrayDeque<Runnable>();
        var gate = new CriticalGate();
        var owner = gate.newTicket();
        var cleanupFailure = new IllegalStateException("cleanup failed before release");
        class Task implements OresScheduler.Task<Integer>, OresScheduler.PhysicalRetirementListener {
            @Override public OresScheduler.Step<Integer> resume(OresScheduler.Resume resume) {
                owner.enterAsync();
                return OresScheduler.done(1);
            }
            @Override public void onPhysicallyRetired() { throw cleanupFailure; }
        }
        try (var scheduler = OresScheduler.runtimeOwned("cleanup-test", 1, queue::add)) {
            var result = scheduler.start(new Task());
            queue.remove().run();
            try {
                var failure = assertThrows(ExecutionException.class,
                        () -> result.get(5, TimeUnit.SECONDS));
                assertSame(cleanupFailure, failure.getCause());
                assertTrue(owner.isHeld(), "failed cleanup cannot certify safe handoff");
            } finally {
                owner.close(); // Host fixture cleanup; not a runtime recovery policy.
            }
        }
    }

    @Test void resultWaitsUntilRetirementCallbackReturns() throws Exception {
        var cleanupEntered = new CountDownLatch(1);
        var allowCleanup = new CountDownLatch(1);
        var callbacks = new AtomicInteger();
        class Task implements OresScheduler.Task<Integer>, OresScheduler.PhysicalRetirementListener {
            @Override public OresScheduler.Step<Integer> resume(OresScheduler.Resume resume) {
                return OresScheduler.done(42);
            }
            @Override public void onPhysicallyRetired() {
                callbacks.incrementAndGet();
                cleanupEntered.countDown();
                try {
                    assertTrue(allowCleanup.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            }
        }
        try (var scheduler = new OresScheduler(1)) {
            var result = scheduler.start(new Task());
            try {
                assertTrue(cleanupEntered.await(5, TimeUnit.SECONDS));
                assertFalse(result.isDone());
            } finally {
                allowCleanup.countDown();
            }
            assertEquals(42, result.get(5, TimeUnit.SECONDS));
            assertEquals(1, callbacks.get());
        }
    }

    @Test void inheritedDeadlineIsCheckedWhenQueuedChildActuallyStarts() throws Exception {
        var queue = new ArrayDeque<Runnable>();
        var clock = new AtomicLong(100);
        var child = new AtomicReference<OresFuture<Integer>>();
        var bodyEntries = new AtomicInteger();
        long budget = TimeUnit.HOURS.toNanos(1);
        // The real timer will not fire: expiration is driven only by the test clock.
        try (var timer = Executors.newSingleThreadScheduledExecutor();
             var scheduler = OresScheduler.runtimeOwned(
                     "queued-child", 1, queue::add, Runnable::run, clock::get)) {
            var parent = scheduler.start(resume -> {
                OresScheduler.currentDeadlineController().publishChildDeadline(budget, timer);
                child.set(scheduler.start(childResume -> {
                    bodyEntries.incrementAndGet();
                    return OresScheduler.done(7);
                }));
                return OresScheduler.done(1);
            });
            queue.remove().run();
            assertEquals(1, parent.get(5, TimeUnit.SECONDS));
            clock.addAndGet(budget);
            queue.remove().run();
            var failure = assertThrows(ExecutionException.class,
                    () -> child.get().get(5, TimeUnit.SECONDS));
            assertInstanceOf(TimeoutException.class, failure.getCause());
            assertEquals(0, bodyEntries.get());
        }
    }

    @Test void expiredAdmissionResumeDoesNotEnterBodyEvenWithoutTimerDelivery() throws Exception {
        var queue = new ArrayDeque<Runnable>();
        var clock = new AtomicLong(100);
        var gate = new CriticalGate();
        var blocker = gate.newTicket();
        blocker.enterAsync();
        var owner = gate.newTicket();
        var bodyEntries = new AtomicInteger();
        long budget = TimeUnit.HOURS.toNanos(1);
        try (var timer = Executors.newSingleThreadScheduledExecutor();
             var scheduler = OresScheduler.runtimeOwned(
                     "expired-admission", 1, queue::add, Runnable::run, clock::get)) {
            class Task implements OresScheduler.Task<Integer>, OresScheduler.PhysicalRetirementListener {
                @Override public OresScheduler.Step<Integer> resume(OresScheduler.Resume resume) {
                    if (resume.initial()) {
                        OresScheduler.currentDeadlineController().publishChildDeadline(budget, timer);
                        return OresScheduler.await(owner.enterAsync());
                    }
                    bodyEntries.incrementAndGet();
                    return OresScheduler.done(7);
                }
                @Override public void onPhysicallyRetired() { owner.close(); }
            }
            var result = scheduler.start(new Task());
            queue.remove().run();
            clock.addAndGet(budget);
            blocker.close();
            queue.remove().run();
            var failure = assertThrows(ExecutionException.class,
                    () -> result.get(5, TimeUnit.SECONDS));
            assertInstanceOf(TimeoutException.class, failure.getCause());
            assertEquals(0, bodyEntries.get());
            assertFalse(gate.held());
        } finally {
            blocker.close();
            owner.close();
        }
    }
}
