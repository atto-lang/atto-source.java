package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
final class ActorAsyncTeardownGateTest {
    @Test
    void completionOnlySignalsControlOnceWithoutWaitingOnCarrier() throws Exception {
        try (var timer = Executors.newSingleThreadScheduledExecutor()) {
            var pending = new CompletableFuture<Void>();
            var wakes = new AtomicInteger();
            var observed = new CountDownLatch(1);
            var gate = new ActorAsyncTeardownGate(
                    pending, Duration.ofSeconds(2), timer, () -> {
                        wakes.incrementAndGet();
                        observed.countDown();
                    });
            assertTrue(gate.outcome().isEmpty());
            var completionThread = new Thread(() -> pending.complete(null));
            completionThread.start();
            completionThread.join();
            assertTrue(observed.await(1, TimeUnit.SECONDS));
            assertEquals(ActorAsyncTeardownGate.State.SUCCESS,
                    gate.outcome().orElseThrow().state());
            assertEquals(1, wakes.get(), "one CONTROL wake per actor teardown");
        }
    }

    @Test
    void deadlineWinsOverLateCompletionAndRetainsTerminalState() throws Exception {
        try (var timer = Executors.newSingleThreadScheduledExecutor()) {
            var pending = new CompletableFuture<Void>();
            var signaled = new CountDownLatch(1);
            var gate = new ActorAsyncTeardownGate(
                    pending, Duration.ofMillis(20), timer, signaled::countDown);
            assertTrue(signaled.await(2, TimeUnit.SECONDS));
            pending.complete(null);
            assertEquals(ActorAsyncTeardownGate.State.TIMED_OUT,
                    gate.outcome().orElseThrow().state());
        }
    }

    @Test
    void immediateTimerCallbackCannotRaceAnUninitializedFutureHandle() {
        try (var timer = new ScheduledThreadPoolExecutor(1) {
            @Override
            public ScheduledFuture<?> schedule(
                    Runnable command, long delay, TimeUnit unit) {
                // Adversarial timer: completion happens before schedule()
                // returns its handle to the gate constructor.
                command.run();
                return super.schedule(() -> { }, 1, TimeUnit.HOURS);
            }
        }) {
            var count = new AtomicInteger();
            var gate = new ActorAsyncTeardownGate(
                    new CompletableFuture<Void>(), Duration.ofNanos(1),
                    timer, count::incrementAndGet);
            assertEquals(ActorAsyncTeardownGate.State.TIMED_OUT,
                    gate.outcome().orElseThrow().state());
            assertEquals(1, count.get());
        }
    }

    @Test
    void rejectedControlWakeRemainsObservableAfterCompletedOperation() {
        try (var timer = Executors.newSingleThreadScheduledExecutor()) {
            var completed = CompletableFuture.completedFuture(null);
            var gate = new ActorAsyncTeardownGate(
                    completed, Duration.ofSeconds(1), timer,
                    () -> { throw new RejectedExecutionException("CONTROL rejected"); });
            assertEquals(ActorAsyncTeardownGate.State.SUCCESS,
                    gate.outcome().orElseThrow().state());
            assertInstanceOf(RejectedExecutionException.class,
                    gate.notificationFailure().orElseThrow());
        }
    }

    @Test
    void failureIsPreservedAndDeadlineAdmissionFailsClosed() {
        try (var timer = Executors.newSingleThreadScheduledExecutor()) {
            var pending = new CompletableFuture<Void>();
            var gate = new ActorAsyncTeardownGate(
                    pending, Duration.ofSeconds(1), timer, () -> { });
            pending.completeExceptionally(new IllegalStateException("close failed"));
            assertEquals(ActorAsyncTeardownGate.State.FAILURE,
                    gate.outcome().orElseThrow().state());
            assertEquals("close failed", gate.outcome().orElseThrow().error().getMessage());
            assertThrows(IllegalArgumentException.class, () ->
                    new ActorAsyncTeardownGate(
                            new CompletableFuture<Void>(), Duration.ZERO, timer, () -> { }));
        }
    }
}
