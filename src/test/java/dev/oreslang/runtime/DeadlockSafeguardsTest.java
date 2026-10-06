package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
final class DeadlockSafeguardsTest {
    private static <T> T get(OresFuture<T> future) throws Exception {
        return future.get(3, TimeUnit.SECONDS);
    }
    private static void timedOut(OresFuture<?> future) {
        var failure = assertThrows(ExecutionException.class, () -> get(future));
        assertInstanceOf(OresTimeoutError.class, failure.getCause());
    }

    @Test void stalledTaskTimesOutDetachesWaiterAndPoolRecovers() throws Exception {
        try (var scheduler = new OresScheduler(1)) {
            var external = new OresFuture<Integer>();
            var task = scheduler.start(resume -> OresScheduler.await(external), Duration.ofMillis(80));
            timedOut(task);
            assertFalse(external.isDone(), "shared producer must remain independent");
            assertEquals(0, external.pendingRuntimeWaiterCount());
            assertEquals(42, get(scheduler.startSync(() -> 42)));
        }
    }

    @Test void scopedReadWithdrawsRegistrationBeforeRecovery() throws Exception {
        try (var scheduler = new OresScheduler(1)) {
            var channel = new ChannelRuntime.Channel<Integer>(0);
            AtomicReference<OresFuture<Integer>> read = new AtomicReference<>();
            var task = scheduler.start(resume -> {
                read.set(channel.readAsync());
                return OresScheduler.await(read.get());
            }, Duration.ofMillis(80));
            timedOut(task);
            timedOut(read.get());
            assertFalse(channel.tryWrite(7), "expired read must not consume a later value");
            var healthyRead = channel.readAsync();
            get(channel.writeAsync(42));
            assertEquals(42, get(healthyRead));
        }
    }

    @Test void scopedBlockedWriteCannotDeliverAfterTimeout() throws Exception {
        try (var scheduler = new OresScheduler(1)) {
            var channel = new ChannelRuntime.Channel<Integer>(1);
            get(channel.writeAsync(1));
            AtomicReference<OresFuture<Void>> write = new AtomicReference<>();
            var task = scheduler.start(resume -> {
                write.set(channel.writeAsync(2));
                return OresScheduler.await(write.get());
            }, Duration.ofMillis(80));
            timedOut(task);
            timedOut(write.get());
            assertEquals(1, get(channel.readAsync()));
            assertTrue(channel.tryRead().isEmpty());
            get(channel.writeAsync(3));
            assertEquals(3, get(channel.readAsync()));
        }
    }

    @Test void explicitSelectTimeoutDetachesEveryArm() throws Exception {
        var left = new ChannelRuntime.Channel<Integer>(0);
        var right = new ChannelRuntime.Channel<Integer>(0);
        var selected = ChannelRuntime.SelectSet.of(ChannelRuntime.read(left), ChannelRuntime.read(right)).selectAsync();
        timedOut(OresFutures.withTimeout(selected, Duration.ofMillis(20)));
        assertFalse(left.tryWrite(1));
        assertFalse(right.tryWrite(2));
        var fresh = ChannelRuntime.SelectSet.of(ChannelRuntime.read(left)).selectAsync();
        get(left.writeAsync(42));
        assertEquals(42, get(fresh).value());
    }

    @Test void observationTimeoutLeavesSharedProducerAndOtherWaitersAlive() throws Exception {
        var source = new OresFuture<Integer>();
        var observation = OresFutures.waitTimeout(source, Duration.ofMillis(20));
        timedOut(observation);
        assertFalse(source.isDone());
        assertEquals(0, source.pendingRuntimeWaiterCount());
        source.completeFromRuntime(42);
        assertEquals(42, get(source));
    }

    @Test void cancellationOfObservationDetachesWithoutCancellingProducer() {
        var source = new OresFuture<Integer>();
        var observation = OresFutures.waitTimeout(source, Duration.ofDays(1));
        assertTrue(observation.cancel(false));
        assertFalse(source.isDone());
        assertEquals(0, source.pendingRuntimeWaiterCount());
    }

    @Test void childCannotExtendParentDeadlineAndCrossSchedulerChildrenAreCancelled() throws Exception {
        try (var parent = new OresScheduler(1); var child = new OresScheduler(1)) {
            var external = new OresFuture<Integer>();
            AtomicReference<OresFuture<Integer>> nested = new AtomicReference<>();
            var task = parent.<Integer>start(resume -> {
                nested.set(child.start(r -> OresScheduler.await(external), Duration.ofDays(1)));
                return OresScheduler.await(nested.get());
            }, Duration.ofMillis(80));
            timedOut(task);
            timedOut(nested.get());
            assertEquals(0, external.pendingRuntimeWaiterCount());
            assertEquals(42, get(child.startSync(() -> 42)));
        }
    }

    @Test void normalScopeCompletionCancelsAbandonedChildOperations() throws Exception {
        try (var scheduler = new OresScheduler(1)) {
            var channel = new ChannelRuntime.Channel<Integer>(0);
            AtomicReference<OresFuture<Integer>> read = new AtomicReference<>();
            var task = scheduler.start(resume -> {
                read.set(channel.readAsync());
                return OresScheduler.done(42);
            }, Duration.ofDays(1));
            assertEquals(42, get(task));
            assertThrows(CancellationException.class, () -> get(read.get()));
            assertFalse(channel.tryWrite(1));
        }
    }

    @Test void pendingHostBridgesFailFastOnSchedulerCarrier() throws Exception {
        try (var scheduler = new OresScheduler(1)) {
            var source = new OresFuture<Integer>();
            for (int bridge = 0; bridge < 3; bridge++) {
                final int operation = bridge;
                var task = scheduler.startSync(() -> switch (operation) {
                    case 0 -> source.join();
                    case 1 -> source.get();
                    default -> source.get(1, TimeUnit.SECONDS);
                });
                assertInstanceOf(OresDeadlockError.class,
                        assertThrows(ExecutionException.class, () -> get(task)).getCause());
            }
            assertEquals(42, get(scheduler.startSync(() -> OresFuture.completed(42).join())));
            assertEquals(0, source.pendingRuntimeWaiterCount());
        }
    }

    @Test void hostTimedWaitsAndInterruptionsDoNotRetainWaiters() throws Exception {
        var source = new OresFuture<Integer>();
        assertThrows(TimeoutException.class, () -> source.get(1, TimeUnit.MILLISECONDS));
        assertEquals(0, source.pendingRuntimeWaiterCount());
        CountDownLatch entered = new CountDownLatch(1);
        Thread host = Thread.ofPlatform().start(() -> {
            entered.countDown();
            assertThrows(InterruptedException.class, source::get);
        });
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        host.interrupt();
        host.join(1000);
        assertFalse(host.isAlive());
        assertEquals(0, source.pendingRuntimeWaiterCount());
    }

    @Test void selfAwaitAndTwoTaskCycleFailWithoutNeedingTimeout() throws Exception {
        try (var scheduler = new OresScheduler(1)) {
            var gate = new OresFuture<Void>();
            AtomicReference<OresFuture<Integer>> self = new AtomicReference<>();
            self.set(scheduler.start(resume -> resume.initial()
                    ? OresScheduler.await(gate) : OresScheduler.await(self.get())));
            gate.completeFromRuntime(null);
            var error = assertThrows(ExecutionException.class, () -> get(self.get())).getCause();
            assertInstanceOf(OresDeadlockError.class, error);
            assertTrue(error.getMessage().contains(scheduler.name()));

            var barrier = new OresFuture<Void>();
            AtomicReference<OresFuture<Integer>> a = new AtomicReference<>();
            AtomicReference<OresFuture<Integer>> b = new AtomicReference<>();
            a.set(scheduler.start(r -> {
                if (r.initial()) return OresScheduler.await(barrier);
                if (r.failure() != null) throw (RuntimeException) r.failure();
                return OresScheduler.await(b.get());
            }));
            b.set(scheduler.start(r -> {
                if (r.initial()) return OresScheduler.await(barrier);
                if (r.failure() != null) throw (RuntimeException) r.failure();
                return OresScheduler.await(a.get());
            }));
            barrier.completeFromRuntime(null);
            assertInstanceOf(OresDeadlockError.class, assertThrows(ExecutionException.class, () -> get(a.get())).getCause());
            assertInstanceOf(OresDeadlockError.class, assertThrows(ExecutionException.class, () -> get(b.get())).getCause());
            assertEquals(0, OresWaitGraph.size());
            assertEquals(42, get(scheduler.startSync(() -> 42)));
        }
    }

    @Test void waitingForExternalIoIsNotClassifiedAsDeadlock() throws Exception {
        try (var scheduler = new OresScheduler(1)) {
            var io = new OresFuture<Integer>();
            var task = scheduler.<Integer>start(r -> {
                if (r.initial()) return OresScheduler.await(io);
                return OresScheduler.done((Integer) r.value());
            }, Duration.ofSeconds(2));
            io.completeFromRuntime(42);
            assertEquals(42, get(task));
        }
    }

    @Test void runningGuestMustLeaveTurnBeforeDeadlineFutureIsPublished() throws Exception {
        try (var scheduler = new OresScheduler(1)) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            var task = scheduler.start(r -> {
                entered.countDown();
                assertTrue(release.await(2, TimeUnit.SECONDS));
                return OresScheduler.done(42);
            }, Duration.ofMillis(30));
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            // A watchdog cannot safely kill non-cooperative guest/native work.
            assertThrows(TimeoutException.class, () -> task.get(100, TimeUnit.MILLISECONDS));
            release.countDown();
            timedOut(task);
            assertEquals(42, get(scheduler.startSync(() -> 42)));
        }
    }

    @Test void cooperativeCheckpointUnwindsBusyTaskAndPoolRecovers() throws Exception {
        try (var scheduler = new OresScheduler(1)) {
            var task = scheduler.start(r -> {
                for (;;) OresScheduler.checkpoint();
            }, Duration.ofMillis(30));
            timedOut(task);
            assertEquals(42, get(scheduler.startSync(() -> 42)));
        }
    }

    @Test void cancelledAndCompletedScopesReleaseTimersAndMembers() throws Exception {
        int before = OresDeadlineScope.pendingTimerCount();
        for (int i = 0; i < 50; i++) {
            try (var scope = new OresDeadlineScope(Duration.ofDays(1), "test")) {
                var prior = OresDeadlineScope.enter(scope);
                var read = new ChannelRuntime.Channel<Integer>(0).readAsync();
                OresDeadlineScope.enter(prior);
                assertEquals(1, scope.memberCount());
                read.cancel(false);
                assertEquals(0, scope.memberCount());
            }
        }
        assertEquals(before, OresDeadlineScope.pendingTimerCount());
        assertThrows(IllegalArgumentException.class, () -> new OresDeadlineScope(Duration.ofMillis(-1), "bad"));
    }
    @Test void saturatedCarrierCannotPreventQueuedTaskDeadlineOrReenterAfterTimeout() throws Exception {
        AtomicInteger boundaries = new AtomicInteger();
        try (var scheduler = OresScheduler.managed(1, turn -> { boundaries.incrementAndGet(); turn.run(); })) {
            CountDownLatch busy = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            var blocker = scheduler.startSync(() -> { busy.countDown(); release.await(2, TimeUnit.SECONDS); return 1; });
            assertTrue(busy.await(1, TimeUnit.SECONDS));
            var queued = scheduler.start(r -> OresScheduler.done(2), Duration.ofMillis(30));
            timedOut(queued);
            assertEquals(1, boundaries.get());
            release.countDown();
            assertEquals(1, get(blocker));
            assertEquals(42, get(scheduler.startSync(() -> 42)));
            assertEquals(2, boundaries.get(), "expired queued task must not enter a guest boundary");
        }
    }

    @Test void parentDeadlineWaitsForRunningChildToLeaveGuestBoundary() throws Exception {
        try (var scheduler = new OresScheduler(2)) {
            CountDownLatch childEntered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            var parent = scheduler.start(r -> {
                if (r.initial()) {
                    var child = scheduler.startSync(() -> {
                        childEntered.countDown();
                        assertTrue(release.await(2, TimeUnit.SECONDS));
                        return 42;
                    });
                    return OresScheduler.await(child);
                }
                return OresScheduler.done(r.value());
            }, Duration.ofMillis(80));
            assertTrue(childEntered.await(1, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> parent.get(150, TimeUnit.MILLISECONDS));
            release.countDown();
            timedOut(parent);
            assertEquals(42, get(scheduler.startSync(() -> 42)));
        }
    }

    @Test void actorLifetimeInheritsScopeAndRecoversAfterDeadline() throws Exception {
        try (var actors = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 1, 16));
             var scheduler = new OresScheduler(1)) {
            AtomicReference<ActorRuntime.ActorRef<Integer>> actor = new AtomicReference<>();
            var parent = scheduler.start(r -> {
                actor.set(actors.<Integer>spawn(ActorRuntime.ActorKind.SHARED, IsolatePolicy.developer(), ctx -> (m, c) -> { }));
                return OresScheduler.await(new OresFuture<>());
            }, Duration.ofMillis(50));
            timedOut(parent);
            assertTrue(actor.get().awaitTermination(1, TimeUnit.SECONDS));
            assertEquals(0, actors.actorCount());
            assertInstanceOf(OresTimeoutError.class, actor.get().failure().orElseThrow());
            var fresh = actors.<Integer>spawn(ActorRuntime.ActorKind.SHARED, IsolatePolicy.developer(), ctx -> (m, c) -> c.self().stop());
            actors.send(fresh, 42);
            assertTrue(fresh.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

}
