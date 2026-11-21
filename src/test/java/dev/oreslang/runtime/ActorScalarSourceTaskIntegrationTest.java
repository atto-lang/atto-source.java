package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration of M1.7 scheduler-owned SourceTask frame leases and M2.1 typed
 * scalar invocation frames in the SAME actor incarnation arena.
 *
 * These are host runtime models, NOT canonical .atto source receive
 * non-reentrancy, compiler-lowered locals, or native stack isolation.
 */
final class ActorScalarSourceTaskIntegrationTest {
    private static void awaitCondition(BooleanSupplier ready) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!ready.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(2);
        }
        assertTrue(ready.getAsBoolean(), "bounded actor/task observation timed out");
    }

    @Test
    void typedScalarGuestCallAndActorSourceTaskShareQuotaButNotExecutionIdentity()
            throws Exception {
        var config = new ActorRuntime.DispatcherConfig(1, 1, 8);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            var gate = new OresFuture<Integer>();
            AtomicReference<ActorFrameArena> arena = new AtomicReference<>();
            AtomicReference<ExecutionContext> first = new AtomicReference<>();
            AtomicReference<ExecutionContext> resumed = new AtomicReference<>();
            AtomicReference<OresFuture<Integer>> task = new AtomicReference<>();
            AtomicInteger dispatches = new AtomicInteger();
            final List<Object> scalarArgs = List.of(9L, 3, true, (short) 7, 0.5d);
            final int scalarBytes = 16 + scalarArgs.size() * GuestScalarFrameCodec.SLOT_BYTES;
            var actor = runtime.<String>spawnShared(factory -> (message, turn) -> {
                if ("stop".equals(message)) {
                    turn.self().stop();
                    return;
                }
                ActorFrameArena store = ActorRuntime.currentActorFrameArena();
                arena.set(store);
                task.set(runtime.startActorTask(resume -> {
                    assertSame(store, ActorRuntime.currentActorFrameArena());
                    ExecutionContext taskContext = OresScheduler.currentExecutionContext();
                    assertNotNull(taskContext);
                    assertEquals(store.allocationDomain(), taskContext.allocationDomain());
                    assertEquals(ExecutionContext.AllocationKind.ACTOR,
                            taskContext.allocationDomain().kind());
                    assertEquals(ActorSourceTaskFrame.FRAME_CHARGE_BYTES,
                            store.usedBytes(),
                            "before each guest dispatch exactly one persistent task lease exists");
                    if (dispatches.getAndIncrement() == 0) first.set(taskContext);
                    else resumed.set(taskContext);

                    try (var invocation = GuestInvocationFrame.enterWithArguments(0, scalarArgs)) {
                        assertEquals(2, store.liveFrames(),
                                "task and synchronous invocation use distinct charged frames");
                        assertEquals(ActorSourceTaskFrame.FRAME_CHARGE_BYTES + scalarBytes,
                                store.usedBytes(), "no second descriptor frame is allocated");
                        assertEquals(scalarArgs, invocation.restoreScalarArguments(scalarArgs));
                    }
                    assertEquals(1, store.liveFrames());
                    assertEquals(ActorSourceTaskFrame.FRAME_CHARGE_BYTES, store.usedBytes());
                    return first.get() != null && resumed.get() == null
                            ? OresScheduler.await(gate)
                            : OresScheduler.done(42);
                }));
            });
            actor.send("start");
            awaitCondition(() -> first.get() != null && arena.get() != null
                    && arena.get().usedBytes() == ActorSourceTaskFrame.FRAME_CHARGE_BYTES);
            assertEquals(1, arena.get().liveFrames());
            assertEquals(ActorSourceTaskFrame.FRAME_CHARGE_BYTES + scalarBytes,
                    arena.get().peakBytes());
            gate.completeFromRuntime(1);
            awaitCondition(() -> task.get() != null && task.get().isDone());
            assertEquals(42, task.get().get(3, TimeUnit.SECONDS));
            assertSame(first.get(), resumed.get(),
                    "scheduler-owned task identity must survive await and physical handoff");
            assertEquals(0, arena.get().usedBytes(),
                    "source task and invocation frames must both retire after quiescence");
            assertEquals(0, arena.get().liveFrames());
            actor.send("stop");
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty(), () -> "actor failed: " + actor.failure());
        }
    }

    @Test
    void twoSuspendedTasksPreserveIndependentLeasesWhileScalarCallsUnwind()
            throws Exception {
        var config = new ActorRuntime.DispatcherConfig(1, 1, 8);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            var firstGate = new OresFuture<Integer>();
            var secondGate = new OresFuture<Integer>();
            AtomicReference<ActorFrameArena> arena = new AtomicReference<>();
            AtomicReference<ExecutionContext> firstIdentity = new AtomicReference<>();
            AtomicReference<ExecutionContext> secondIdentity = new AtomicReference<>();
            AtomicReference<OresFuture<Integer>> firstTask = new AtomicReference<>();
            AtomicReference<OresFuture<Integer>> secondTask = new AtomicReference<>();
            AtomicInteger firstDispatch = new AtomicInteger();
            AtomicInteger secondDispatch = new AtomicInteger();
            var actor = runtime.<String>spawnShared(factory -> (message, turn) -> {
                if ("stop".equals(message)) {
                    turn.self().stop();
                    return;
                }
                ActorFrameArena store = ActorRuntime.currentActorFrameArena();
                arena.set(store);
                firstTask.set(runtime.startActorTask(resume -> {
                    if (firstDispatch.getAndIncrement() == 0) {
                        firstIdentity.set(OresScheduler.currentExecutionContext());
                        try (var frame = GuestInvocationFrame.enterWithArguments(
                                1, List.of(11L, false))) {
                            assertEquals(List.of(11L, false),
                                    frame.restoreScalarArguments(List.of(11L, false)));
                        }
                        return OresScheduler.await(firstGate);
                    }
                    return OresScheduler.done(11);
                }));
                secondTask.set(runtime.startActorTask(resume -> {
                    if (secondDispatch.getAndIncrement() == 0) {
                        secondIdentity.set(OresScheduler.currentExecutionContext());
                        try (var frame = GuestInvocationFrame.enterWithArguments(
                                1, List.of(22L, true))) {
                            assertEquals(List.of(22L, true),
                                    frame.restoreScalarArguments(List.of(22L, true)));
                        }
                        return OresScheduler.await(secondGate);
                    }
                    return OresScheduler.done(22);
                }));
            });
            actor.send("start");
            awaitCondition(() -> firstIdentity.get() != null && secondIdentity.get() != null
                    && arena.get() != null && arena.get().liveFrames() == 2);
            assertNotEquals(firstIdentity.get().id(), secondIdentity.get().id());
            assertEquals(2 * ActorSourceTaskFrame.FRAME_CHARGE_BYTES,
                    arena.get().usedBytes(), "two suspended tasks both retain their charged frames");
            assertEquals(2 * ActorSourceTaskFrame.FRAME_CHARGE_BYTES
                            + 16 + 2 * GuestScalarFrameCodec.SLOT_BYTES,
                    arena.get().peakBytes(), "second call shares one aggregate actor quota");
            firstGate.completeFromRuntime(1);
            awaitCondition(() -> firstTask.get() != null && firstTask.get().isDone());
            assertEquals(11, firstTask.get().get(3, TimeUnit.SECONDS));
            assertEquals(ActorSourceTaskFrame.FRAME_CHARGE_BYTES, arena.get().usedBytes());
            secondGate.completeFromRuntime(2);
            awaitCondition(() -> secondTask.get() != null && secondTask.get().isDone());
            assertEquals(22, secondTask.get().get(3, TimeUnit.SECONDS));
            assertEquals(0, arena.get().usedBytes());
            actor.send("stop");
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty(), () -> "actor failed: " + actor.failure());
        }
    }

    @Test
    void cancelSuspendedSourceTaskAfterScalarCallFreesOnlyItsOwnedFrames()
            throws Exception {
        var config = new ActorRuntime.DispatcherConfig(1, 1, 8);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            var gate = new OresFuture<Integer>();
            AtomicReference<ActorFrameArena> owner = new AtomicReference<>();
            AtomicReference<ExecutionContext> cancelledIdentity = new AtomicReference<>();
            AtomicReference<OresFuture<Integer>> task = new AtomicReference<>();
            AtomicInteger taskDispatches = new AtomicInteger();
            var actor = runtime.<String>spawnShared(factory -> (message, turn) -> {
                if ("stop".equals(message)) {
                    turn.self().stop();
                    return;
                }
                ActorFrameArena arena = ActorRuntime.currentActorFrameArena();
                owner.set(arena);
                task.set(runtime.startActorTask(resume -> {
                    cancelledIdentity.set(OresScheduler.currentExecutionContext());
                    taskDispatches.incrementAndGet();
                    try (var invocation = GuestInvocationFrame.enterWithArguments(
                            1, List.of(Long.MIN_VALUE, true))) {
                        assertEquals(List.of(Long.MIN_VALUE, true),
                                invocation.restoreScalarArguments(List.of(Long.MIN_VALUE, true)));
                        assertEquals(2, arena.liveFrames());
                    }
                    return OresScheduler.await(gate);
                }));
            });
            actor.send("start");
            awaitCondition(() -> taskDispatches.get() == 1 && owner.get() != null
                    && owner.get().usedBytes() == ActorSourceTaskFrame.FRAME_CHARGE_BYTES);
            assertNotNull(cancelledIdentity.get());
            assertTrue(task.get().cancel(false));
            awaitCondition(() -> owner.get().usedBytes() == 0);
            assertEquals(0, owner.get().liveFrames());
            assertFalse(owner.get().isPoisoned(), "cancel is not a frame quota violation");
            gate.completeFromRuntime(1);
            assertEquals(1, taskDispatches.get(), "stale await continuation cannot execute");
            assertTrue(actor.isAlive(), "task cancellation must not kill its sibling actor");
            actor.send("stop");
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty(), () -> "actor failed: " + actor.failure());
        }
    }
    @Test
    void typedScalarContinuationSnapshotSurvivesAwaitInSameTaskFrame()
            throws Exception {
        var config = new ActorRuntime.DispatcherConfig(1, 1, 8);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            var gate = new OresFuture<Integer>();
            AtomicReference<ActorFrameArena> arena = new AtomicReference<>();
            AtomicReference<ActorSourceTaskFrame> savedLease = new AtomicReference<>();
            AtomicReference<OresFuture<Integer>> task = new AtomicReference<>();
            AtomicInteger resumes = new AtomicInteger();
            List<Object> scalars = List.of(123L, (short) -9, true,
                    Float.intBitsToFloat(0x7fc00055));
            var actor = runtime.<String>spawnShared(factory -> (message, turn) -> {
                if ("stop".equals(message)) {
                    turn.self().stop();
                    return;
                }
                ActorFrameArena store = ActorRuntime.currentActorFrameArena();
                arena.set(store);
                task.set(runtime.startActorTask(resume -> {
                    ActorSourceTaskFrame lease = OresScheduler.currentActorSourceTaskFrame();
                    assertNotNull(lease, "the source task has a physically bound frame");
                    assertSame(store, lease.arena());
                    if (resumes.getAndIncrement() == 0) {
                        savedLease.set(lease);
                        assertThrows(IllegalStateException.class, lease::restoreTypedScalars,
                                "restore without an explicit snapshot must reject");
                        lease.saveTypedScalars(scalars);
                        assertEquals(ActorSourceTaskFrame.FRAME_CHARGE_BYTES, store.usedBytes(),
                                "typed state stays within the precharged 64-byte lease");
                        return OresScheduler.await(gate);
                    }
                    assertSame(savedLease.get(), lease,
                            "await must restore the same task-frame authority");
                    List<Object> decoded = lease.restoreTypedScalars();
                    assertEquals(scalars.size(), decoded.size());
                    assertEquals(123L, decoded.get(0));
                    assertEquals((short) -9, decoded.get(1));
                    assertEquals(true, decoded.get(2));
                    assertEquals(0x7fc00055,
                            Float.floatToRawIntBits((Float) decoded.get(3)));
                    assertEquals(ActorSourceTaskFrame.FRAME_CHARGE_BYTES, store.usedBytes());
                    return OresScheduler.done(123);
                }));
            });
            actor.send("start");
            awaitCondition(() -> savedLease.get() != null && arena.get() != null
                    && arena.get().usedBytes() == ActorSourceTaskFrame.FRAME_CHARGE_BYTES);
            assertNull(OresScheduler.currentActorSourceTaskFrame(),
                    "non-carrier host may not borrow a task's frame");
            assertThrows(SecurityException.class, () -> savedLease.get().restoreTypedScalars());
            assertThrows(SecurityException.class,
                    () -> savedLease.get().saveTypedScalars(List.of(3L)));
            assertFalse(arena.get().isPoisoned(), "foreign caller must not poison the owner");
            gate.completeFromRuntime(1);
            awaitCondition(() -> task.get() != null && task.get().isDone());
            assertEquals(123, task.get().get(3, TimeUnit.SECONDS));
            assertEquals(2, resumes.get());
            assertEquals(ActorSourceTaskFrame.FRAME_CHARGE_BYTES, arena.get().peakBytes());
            assertEquals(0, arena.get().usedBytes());
            assertTrue(savedLease.get().retired());
            actor.send("stop");
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty(), () -> "actor failed: " + actor.failure());
        }
    }

    @Test
    void typedTaskSnapshotsRejectReferenceTransportAndOverflowWithoutExtraCharge()
            throws Exception {
        var config = new ActorRuntime.DispatcherConfig(1, 1, 8);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            AtomicReference<ActorFrameArena> arena = new AtomicReference<>();
            AtomicReference<OresFuture<Integer>> result = new AtomicReference<>();
            AtomicInteger dispatches = new AtomicInteger();
            var actor = runtime.<String>spawnShared(factory -> (message, turn) -> {
                if ("stop".equals(message)) { turn.self().stop(); return; }
                ActorFrameArena store = ActorRuntime.currentActorFrameArena();
                arena.set(store);
                result.set(runtime.startActorTask(resume -> {
                    ActorSourceTaskFrame lease = OresScheduler.currentActorSourceTaskFrame();
                    assertNotNull(lease);
                    if (dispatches.getAndIncrement() == 0) {
                        assertThrows(IllegalArgumentException.class,
                                () -> lease.saveTypedScalars(List.of(new Object())));
                        assertThrows(IllegalArgumentException.class,
                                () -> lease.saveTypedScalars(
                                        java.util.Collections.nCopies(
                                                ActorSourceTaskFrame.MAX_SCALAR_VALUES + 1,
                                                1L)));
                        assertEquals(ActorSourceTaskFrame.FRAME_CHARGE_BYTES, store.usedBytes());
                        assertFalse(store.isPoisoned(),
                                "unsupported state is rejected before touching the arena");
                        lease.saveTypedScalars(List.of(1L, false, (byte) -2));
                        return OresScheduler.cooperate();
                    }
                    assertEquals(List.of(1L, false, (byte) -2),
                            lease.restoreTypedScalars());
                    assertEquals(ActorSourceTaskFrame.FRAME_CHARGE_BYTES, store.usedBytes());
                    return OresScheduler.done(9);
                }));
            });
            actor.send("start");
            awaitCondition(() -> result.get() != null && result.get().isDone());
            assertEquals(9, result.get().get(3, TimeUnit.SECONDS));
            assertEquals(2, dispatches.get());
            assertEquals(ActorSourceTaskFrame.FRAME_CHARGE_BYTES, arena.get().peakBytes());
            assertEquals(0, arena.get().usedBytes());
            assertFalse(arena.get().isPoisoned());
            actor.send("stop");
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty(), () -> "actor failed: " + actor.failure());
        }
    }

}
