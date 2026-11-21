package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Host scheduler and actor-runtime tests for actor-bound SourceTask leases.
 * They do not demonstrate compiler-lowered .atto local/return frames.
 */
final class ActorSourceTaskFrameTest {
    private static void awaitCondition(BooleanSupplier ready) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (!ready.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(2);
        }
        assertTrue(ready.getAsBoolean(), "bounded source-task observation timed out");
    }

    @Test
    void actorTaskContextAndFrameSurviveAwaitUntilPhysicalCompletion() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(), new ActorRuntime.DispatcherConfig(1, 1, 8))) {
            OresFuture<Integer> gate = new OresFuture<>();
            AtomicReference<ActorFrameArena> arena = new AtomicReference<>();
            AtomicReference<ExecutionContext> before = new AtomicReference<>();
            AtomicReference<ExecutionContext> after = new AtomicReference<>();
            AtomicReference<OresFuture<Integer>> result = new AtomicReference<>();
            AtomicInteger runs = new AtomicInteger();
            var actor = runtime.<String>spawnShared(factory -> (message, turn) -> {
                if ("stop".equals(message)) {
                    turn.self().stop();
                    return;
                }
                ActorFrameArena owner = ActorRuntime.currentActorFrameArena();
                arena.set(owner);
                result.set(runtime.startActorTask(resume -> {
                    assertSame(owner, ActorRuntime.currentActorFrameArena());
                    ExecutionContext context = OresScheduler.currentExecutionContext();
                    assertNotNull(context);
                    assertEquals(ExecutionContext.AllocationKind.ACTOR,
                            context.allocationDomain().kind());
                    assertEquals(owner.allocationDomain(), context.allocationDomain());
                    if (runs.getAndIncrement() == 0) {
                        before.set(context);
                        return OresScheduler.await(gate);
                    }
                    after.set(context);
                    return OresScheduler.done(17);
                }));
            });
            actor.send("start");
            awaitCondition(() -> before.get() != null
                    && arena.get() != null
                    && arena.get().usedBytes() == ActorSourceTaskFrame.FRAME_CHARGE_BYTES);
            assertEquals(1, arena.get().liveFrames());
            assertTrue(actor.isAlive(), "await must not terminate the actor");
            gate.completeFromRuntime(1);
            awaitCondition(() -> result.get() != null && result.get().isDone());
            assertEquals(17, result.get().get(3, TimeUnit.SECONDS));
            assertSame(before.get(), after.get(),
                    "source task must preserve logical execution identity across await");
            assertEquals(0, arena.get().usedBytes(),
                    "terminal publication must follow physical frame retirement");
            assertEquals(0, arena.get().liveFrames());
            actor.send("stop");
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty());
        }
    }

    @Test
    void cooperateRetainsAnActorFrameButReleasesItsCarrier() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(), new ActorRuntime.DispatcherConfig(1, 1, 8))) {
            AtomicReference<ActorFrameArena> arena = new AtomicReference<>();
            AtomicReference<OresFuture<Integer>> result = new AtomicReference<>();
            AtomicReference<ExecutionContext> first = new AtomicReference<>();
            AtomicReference<ExecutionContext> second = new AtomicReference<>();
            AtomicInteger calls = new AtomicInteger();
            var actor = runtime.<String>spawnShared(factory -> (message, turn) -> {
                if ("stop".equals(message)) { turn.self().stop(); return; }
                arena.set(ActorRuntime.currentActorFrameArena());
                result.set(runtime.startActorTask(resume -> {
                    ExecutionContext identity = OresScheduler.currentExecutionContext();
                    if (calls.getAndIncrement() == 0) {
                        first.set(identity);
                        return OresScheduler.cooperate();
                    }
                    second.set(identity);
                    return OresScheduler.done(5);
                }));
            });
            actor.send("start");
            awaitCondition(() -> result.get() != null && result.get().isDone());
            assertEquals(5, result.get().get(3, TimeUnit.SECONDS));
            assertSame(first.get(), second.get());
            assertEquals(ActorSourceTaskFrame.FRAME_CHARGE_BYTES, arena.get().peakBytes());
            assertEquals(0, arena.get().usedBytes());
            actor.send("stop");
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty());
        }
    }

    @Test
    void twoSuspendedSourceTasksRetainIndependentIdentityAndAggregateQuota()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(), new ActorRuntime.DispatcherConfig(1, 1, 8))) {
            OresFuture<Integer> firstGate = new OresFuture<>();
            OresFuture<Integer> secondGate = new OresFuture<>();
            AtomicReference<ActorFrameArena> arena = new AtomicReference<>();
            AtomicReference<ExecutionContext> first = new AtomicReference<>();
            AtomicReference<ExecutionContext> second = new AtomicReference<>();
            AtomicReference<OresFuture<Integer>> firstResult = new AtomicReference<>();
            AtomicReference<OresFuture<Integer>> secondResult = new AtomicReference<>();
            AtomicInteger firstCount = new AtomicInteger();
            AtomicInteger secondCount = new AtomicInteger();
            var actor = runtime.<String>spawnShared(factory -> (message, turn) -> {
                if ("stop".equals(message)) { turn.self().stop(); return; }
                arena.set(ActorRuntime.currentActorFrameArena());
                firstResult.set(runtime.startActorTask(resume -> {
                    if (firstCount.getAndIncrement() == 0) {
                        first.set(OresScheduler.currentExecutionContext());
                        return OresScheduler.await(firstGate);
                    }
                    return OresScheduler.done(11);
                }));
                secondResult.set(runtime.startActorTask(resume -> {
                    if (secondCount.getAndIncrement() == 0) {
                        second.set(OresScheduler.currentExecutionContext());
                        return OresScheduler.await(secondGate);
                    }
                    return OresScheduler.done(22);
                }));
            });
            actor.send("start");
            awaitCondition(() -> first.get() != null && second.get() != null
                    && arena.get().liveFrames() == 2);
            assertNotEquals(first.get().id(), second.get().id());
            assertEquals(2 * ActorSourceTaskFrame.FRAME_CHARGE_BYTES, arena.get().usedBytes());
            firstGate.completeFromRuntime(1);
            awaitCondition(() -> firstResult.get() != null && firstResult.get().isDone());
            assertEquals(11, firstResult.get().get(3, TimeUnit.SECONDS));
            assertEquals(ActorSourceTaskFrame.FRAME_CHARGE_BYTES, arena.get().usedBytes(),
                    "unsettled sibling must keep its own frame");
            secondGate.completeFromRuntime(2);
            awaitCondition(() -> secondResult.get() != null && secondResult.get().isDone());
            assertEquals(22, secondResult.get().get(3, TimeUnit.SECONDS));
            assertEquals(0, arena.get().usedBytes());
            actor.send("stop");
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty());
        }
    }

    @Test
    void cancelledAwaitReleasesActorFrameWithoutRetiringSibling() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(), new ActorRuntime.DispatcherConfig(1, 1, 8))) {
            OresFuture<Integer> gate = new OresFuture<>();
            AtomicReference<ActorFrameArena> arena = new AtomicReference<>();
            AtomicReference<OresFuture<Integer>> running = new AtomicReference<>();
            AtomicInteger entered = new AtomicInteger();
            var actor = runtime.<String>spawnShared(factory -> (message, turn) -> {
                if ("stop".equals(message)) { turn.self().stop(); return; }
                arena.set(ActorRuntime.currentActorFrameArena());
                running.set(runtime.startActorTask(resume -> {
                    entered.incrementAndGet();
                    return OresScheduler.await(gate);
                }));
            });
            actor.send("start");
            awaitCondition(() -> entered.get() == 1
                    && arena.get() != null
                    && arena.get().usedBytes() == ActorSourceTaskFrame.FRAME_CHARGE_BYTES);
            assertTrue(running.get().cancel(false));
            awaitCondition(() -> arena.get().usedBytes() == 0);
            assertTrue(actor.isAlive());
            assertFalse(arena.get().isPoisoned());
            actor.send("stop");
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty());
        }
    }

    @Test
    void sourceTaskFrameRejectsExternalExecutionAndOldActorGeneration() {
        ExecutionContext original = ExecutionContext.actor(20, 1, 30, 7, 1, 0, 0);
        ExecutionContext restarted = ExecutionContext.actor(20, 2, 30, 7, 2, 0, 0);
        ActorFrameArena frames = new ActorFrameArena(
                original.allocationDomain(), 2, 128);
        ActorSourceTaskFrame task = new ActorSourceTaskFrame(frames, original);
        assertThrows(SecurityException.class, task::beforeResume,
                "host thread cannot forge the actor carrier and task execution identity");
        assertThrows(SecurityException.class, task::suspend);
        assertThrows(SecurityException.class,
                () -> new ActorSourceTaskFrame(frames, restarted));
        assertFalse(frames.isPoisoned(), "foreign generation denial must not poison owner");
        assertEquals(0, frames.usedBytes());
    }

    @Test
    void sourceSchedulerRefusesExternalCarrierEvenWhenItHasArenaHandle()
            throws Exception {
        ExecutionContext actor = ExecutionContext.actor(90, 1, 90, 7, 1, 0, 0);
        ActorFrameArena frames = new ActorFrameArena(actor.allocationDomain(), 4, 256);
        try (OresScheduler scheduler = OresScheduler.runtimeOwned(
                "unauthenticated-actor-source", 1, Runnable::run, Runnable::run, frames)) {
            OresFuture<Integer> result =
                    scheduler.start(resume -> OresScheduler.done(44));
            var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> result.get(3, TimeUnit.SECONDS));
            assertInstanceOf(SecurityException.class, failure.getCause(),
                    "a scheduler handle alone grants no actor-turn execution");
            assertEquals(0, frames.usedBytes());
            assertFalse(frames.isPoisoned(),
                    "failed foreign dispatch cannot damage the owner");
        }
    }

    @Test
    void boundSourceSchedulerRejectsParallelCarrierConfiguration() {
        ExecutionContext actor = ExecutionContext.actor(90, 1, 90, 7, 1, 0, 0);
        ActorFrameArena frames = new ActorFrameArena(actor.allocationDomain(), 4, 256);
        assertThrows(IllegalArgumentException.class,
                () -> OresScheduler.runtimeOwned(
                        "invalid-parallelism", 2, Runnable::run, Runnable::run, frames));
        assertFalse(frames.isPoisoned());
    }

    @Test
    void actorTaskAndSynchronousCallIdentifiersNeverAliasWithinOneArena() {
        ExecutionContext actor = ExecutionContext.actor(90, 1, 90, 7, 1, 0, 0);
        ActorFrameArena frames = new ActorFrameArena(actor.allocationDomain(), 4, 256);
        ExecutionContext call = frames.newSynchronousExecution();
        ExecutionContext task = frames.newActorTaskExecution(actor.id().runtimeDomain());
        ExecutionContext nextCall = frames.newSynchronousExecution();
        assertNotEquals(call.id(), task.id());
        assertNotEquals(task.id(), nextCall.id());
        assertNotEquals(call.id(), nextCall.id());
        assertEquals(call.allocationDomain(), task.allocationDomain());
        assertEquals(0, frames.usedBytes(), "identity minting cannot allocate frames");
    }
}
