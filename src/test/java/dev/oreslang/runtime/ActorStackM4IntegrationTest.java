package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Host-level integration of M3 synchronous guest invocation frame slots with
 * #2631 actor-bound SourceTask frame retention. No claim of compiler-lowered
 * locals/return-PC preservation across await, or native process isolation.
 */
final class ActorStackM4IntegrationTest {
    private static final long TIMEOUT_SECONDS = 4;

    private static void until(BooleanSupplier ready) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (!ready.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(2);
        }
        assertTrue(ready.getAsBoolean(), "source task integration observation timed out");
    }

    @Test
    void actorSourceTaskRetainsExactlyOneTaskFrameAcrossAwaitWhileInvocationUnwinds()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8))) {
            OresFuture<Integer> gate = new OresFuture<>();
            AtomicReference<ActorFrameArena> frames = new AtomicReference<>();
            AtomicReference<ExecutionContext> stableTask = new AtomicReference<>();
            AtomicReference<OresFuture<Integer>> result = new AtomicReference<>();
            AtomicLong firstDispatch = new AtomicLong();
            AtomicLong secondDispatch = new AtomicLong();
            AtomicInteger steps = new AtomicInteger();

            var actor = runtime.<String>spawnShared(factory -> (message, turn) -> {
                if ("stop".equals(message)) { turn.self().stop(); return; }
                ActorFrameArena arena = ActorRuntime.currentActorFrameArena();
                frames.set(arena);
                result.set(runtime.startActorTask(resume -> {
                    assertSame(arena, ActorRuntime.currentActorFrameArena());
                    ExecutionContext task = OresScheduler.currentExecutionContext();
                    assertNotNull(task);
                    assertEquals(arena.allocationDomain(), task.allocationDomain());
                    if (steps.getAndIncrement() == 0) {
                        stableTask.set(task);
                        firstDispatch.set(OresScheduler.currentDispatchId());
                        assertTrue(firstDispatch.get() > 0);
                        try (GuestInvocationFrame scalar = GuestInvocationFrame.enter(0, 2)) {
                            scalar.writeScalarArguments(new long[]{7, 5});
                            assertTrue(scalar.hasActorOwnedScalarSlots());
                            assertEquals(7, scalar.readScalarArgument(0));
                            assertEquals(5, scalar.readScalarArgument(1));
                            assertEquals(ActorSourceTaskFrame.FRAME_CHARGE_BYTES
                                    + GuestCallDepthGuard.MANAGED_FRAME_CHARGE_BYTES,
                                    arena.usedBytes());
                            assertEquals(2, arena.liveFrames());
                        }
                        assertEquals(ActorSourceTaskFrame.FRAME_CHARGE_BYTES,
                                arena.usedBytes());
                        return OresScheduler.await(gate);
                    }
                    secondDispatch.set(OresScheduler.currentDispatchId());
                    assertSame(stableTask.get(), task,
                            "logical SourceTask identity must survive await");
                    assertNotEquals(firstDispatch.get(), secondDispatch.get(),
                            "physical dispatch ticket must change after resume");
                    assertEquals(ActorSourceTaskFrame.FRAME_CHARGE_BYTES,
                            arena.usedBytes(), "only task frame persisted across await");
                    try (GuestInvocationFrame scalar = GuestInvocationFrame.enter(0, 2)) {
                        scalar.writeScalarArguments(new long[]{8, 4});
                        assertEquals(12L,
                                scalar.readScalarArgument(0) + scalar.readScalarArgument(1));
                        assertEquals(ActorSourceTaskFrame.FRAME_CHARGE_BYTES
                                + GuestCallDepthGuard.MANAGED_FRAME_CHARGE_BYTES,
                                arena.usedBytes());
                    }
                    return OresScheduler.done(12);
                }));
            });

            actor.send("start");
            until(() -> frames.get() != null && stableTask.get() != null
                    && frames.get().usedBytes() == ActorSourceTaskFrame.FRAME_CHARGE_BYTES);
            assertEquals(1, frames.get().liveFrames());
            assertEquals(ActorSourceTaskFrame.FRAME_CHARGE_BYTES
                    + GuestCallDepthGuard.MANAGED_FRAME_CHARGE_BYTES,
                    frames.get().peakBytes());
            assertTrue(actor.isAlive());
            gate.completeFromRuntime(1);
            until(() -> result.get() != null && result.get().isDone());
            assertEquals(12, result.get().get(3, TimeUnit.SECONDS));
            assertEquals(2, steps.get());
            assertEquals(0, frames.get().usedBytes());
            assertEquals(0, frames.get().liveFrames());
            assertFalse(frames.get().isPoisoned());

            actor.send("stop");
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty());
        }
    }

    @Test
    void distinctActorSourceTasksKeepDistinctFrameOwnershipWhenOneAwaits()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8))) {
            OresFuture<Integer> gate = new OresFuture<>();
            AtomicReference<ActorFrameArena> arena = new AtomicReference<>();
            AtomicReference<OresFuture<Integer>> waiting = new AtomicReference<>();
            AtomicReference<OresFuture<Integer>> immediate = new AtomicReference<>();
            AtomicReference<ExecutionContext> waitingId = new AtomicReference<>();
            AtomicReference<ExecutionContext> otherId = new AtomicReference<>();
            AtomicInteger calls = new AtomicInteger();
            var actor = runtime.<String>spawnShared(factory -> (message, turn) -> {
                if ("stop".equals(message)) { turn.self().stop(); return; }
                arena.set(ActorRuntime.currentActorFrameArena());
                waiting.set(runtime.startActorTask(resume -> {
                    if (calls.getAndIncrement() == 0) {
                        waitingId.set(OresScheduler.currentExecutionContext());
                        return OresScheduler.await(gate);
                    }
                    assertSame(waitingId.get(), OresScheduler.currentExecutionContext());
                    return OresScheduler.done(7);
                }));
                immediate.set(runtime.startActorTask(resume -> {
                    otherId.set(OresScheduler.currentExecutionContext());
                    try (GuestInvocationFrame current = GuestInvocationFrame.enter(0, 1)) {
                        current.writeScalarArguments(new long[]{9L});
                        assertEquals(9, current.readScalarArgument(0));
                    }
                    return OresScheduler.done(9);
                }));
            });
            actor.send("start");
            until(() -> waitingId.get() != null && otherId.get() != null
                    && immediate.get() != null && immediate.get().isDone());
            assertNotEquals(waitingId.get().id(), otherId.get().id());
            assertEquals(waitingId.get().allocationDomain(),
                    otherId.get().allocationDomain());
            assertEquals(9, immediate.get().get(3, TimeUnit.SECONDS));
            assertEquals(ActorSourceTaskFrame.FRAME_CHARGE_BYTES,
                    arena.get().usedBytes(), "completed sibling must retire independently");
            gate.completeFromRuntime(1);
            until(() -> waiting.get().isDone());
            assertEquals(7, waiting.get().get(3, TimeUnit.SECONDS));
            assertEquals(0, arena.get().usedBytes());
            actor.send("stop");
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty());
        }
    }

    @Test
    void actorSourceTaskCooperateRequiresClosedSynchronousCallLeases() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8))) {
            AtomicReference<ActorFrameArena> arena = new AtomicReference<>();
            AtomicReference<OresFuture<Integer>> result = new AtomicReference<>();
            AtomicInteger dispatched = new AtomicInteger();
            var actor = runtime.<String>spawnShared(factory -> (message, turn) -> {
                arena.set(ActorRuntime.currentActorFrameArena());
                result.set(runtime.startActorTask(resume -> {
                    dispatched.incrementAndGet();
                    // Trusted-host-only negative mutation: deliberately leak
                    // the synchronous lease across a SourceTask suspension.
                    GuestInvocationFrame leaked = GuestInvocationFrame.enter(0, 0);
                    assertNotNull(leaked);
                    return OresScheduler.cooperate();
                }));
            });
            actor.send("fault");
            until(() -> result.get() != null && result.get().isDone());
            assertThrows(ExecutionException.class,
                    () -> result.get().get(3, TimeUnit.SECONDS));
            assertEquals(1, dispatched.get(),
                    "task may not be allowed to resume the leaked carrier frame");
            assertTrue(arena.get().isPoisoned(),
                    "leaked lease fails the actor incarnation closed");
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS),
                    "poisoned actor must not run another mailbox handler");
            assertEquals(0, arena.get().usedBytes(),
                    "retired actor generation must zeroize leaked frame bytes");
        }
    }

    @Test
    void actorAwaitRejectsOpenCallFrameWithoutResumingTheLeakedLease() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8))) {
            OresFuture<Integer> gate = new OresFuture<>();
            AtomicReference<ActorFrameArena> frames = new AtomicReference<>();
            AtomicReference<OresFuture<Integer>> future = new AtomicReference<>();
            AtomicInteger dispatches = new AtomicInteger();
            var actor = runtime.<String>spawnShared(factory -> (message, turn) -> {
                frames.set(ActorRuntime.currentActorFrameArena());
                future.set(runtime.startActorTask(resume -> {
                    dispatches.incrementAndGet();
                    GuestInvocationFrame leaked = GuestInvocationFrame.enter(0, 0);
                    assertNotNull(leaked);
                    return OresScheduler.await(gate);
                }));
            });
            actor.send("fault");
            until(() -> future.get() != null && future.get().isDone());
            assertThrows(ExecutionException.class,
                    () -> future.get().get(3, TimeUnit.SECONDS));
            gate.completeFromRuntime(1);
            assertEquals(1, dispatches.get(),
                    "scheduler must not arm or resume the rejected await");
            assertTrue(frames.get().isPoisoned());
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            assertEquals(0, frames.get().usedBytes());
        }
    }

    @Test
    void cancellationAfterValidScalarCallRetiresOnlyTheSuspendedTaskLease()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8))) {
            OresFuture<Integer> gate = new OresFuture<>();
            AtomicReference<ActorFrameArena> frames = new AtomicReference<>();
            AtomicReference<OresFuture<Integer>> pending = new AtomicReference<>();
            AtomicInteger steps = new AtomicInteger();
            var actor = runtime.<String>spawnShared(factory -> (message, turn) -> {
                if ("stop".equals(message)) { turn.self().stop(); return; }
                frames.set(ActorRuntime.currentActorFrameArena());
                pending.set(runtime.startActorTask(resume -> {
                    steps.incrementAndGet();
                    try (GuestInvocationFrame scalar = GuestInvocationFrame.enter(0, 2)) {
                        scalar.writeScalarArguments(new long[]{20, 22});
                        assertEquals(42L,
                                scalar.readScalarArgument(0) + scalar.readScalarArgument(1));
                    }
                    return OresScheduler.await(gate);
                }));
            });
            actor.send("start");
            until(() -> steps.get() == 1 && frames.get() != null
                    && frames.get().usedBytes() == ActorSourceTaskFrame.FRAME_CHARGE_BYTES);
            assertEquals(1, frames.get().liveFrames());
            assertTrue(pending.get().cancel(false));
            until(() -> frames.get().usedBytes() == 0);
            assertEquals(0, frames.get().liveFrames());
            assertFalse(frames.get().isPoisoned());
            assertTrue(actor.isAlive());
            actor.send("stop");
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty());
        }
    }

    @Test
    void scalarFrameFromRootCannotFakeActorSourceTaskOwnership() {
        assertNull(OresScheduler.currentExecutionContext());
        try (GuestInvocationFrame scope = GuestInvocationFrame.enter(0, 2)) {
            assertFalse(scope.hasActorOwnedScalarSlots());
            scope.writeScalarArguments(new long[]{1, 2});
            assertThrows(IllegalStateException.class, () -> scope.readScalarArgument(0));
        }
        assertNull(OresScheduler.currentExecutionContext());
    }
}
