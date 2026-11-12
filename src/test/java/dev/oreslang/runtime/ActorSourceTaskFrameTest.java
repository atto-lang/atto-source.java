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

    @Test
    void nestedCallFrameChainRestoresAsOneUnitAcrossAwait() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(), new ActorRuntime.DispatcherConfig(1, 1, 8))) {
            OresFuture<Integer> gate = new OresFuture<>();
            AtomicReference<ActorFrameArena> arena = new AtomicReference<>();
            AtomicReference<ActorCallContinuation> chainRef = new AtomicReference<>();
            AtomicReference<ExecutionContext> identityBefore = new AtomicReference<>();
            AtomicReference<ExecutionContext> identityAfter = new AtomicReference<>();
            AtomicReference<Long> dispatchBefore = new AtomicReference<>();
            AtomicReference<Long> dispatchAfter = new AtomicReference<>();
            AtomicReference<OresFuture<Integer>> result = new AtomicReference<>();
            AtomicInteger turns = new AtomicInteger();

            var actor = runtime.<String>spawnShared(factory -> (message, turn) -> {
                if ("stop".equals(message)) { turn.self().stop(); return; }
                ActorFrameArena owner = ActorRuntime.currentActorFrameArena();
                arena.set(owner);
                result.set(runtime.startActorTask(resume -> {
                    ActorSourceTaskFrame taskFrame =
                            OresScheduler.currentActorSourceTaskFrame();
                    assertNotNull(taskFrame, "scheduler must bind the exact SourceTask frame");
                    ActorCallContinuation chain = taskFrame.callContinuation();
                    if (turns.getAndIncrement() == 0) {
                        chainRef.set(chain);
                        identityBefore.set(OresScheduler.currentExecutionContext());
                        dispatchBefore.set(OresScheduler.currentDispatchId());
                        chain.pushFrame(100, 7, 1, 2);
                        chain.writeLocal(0, 41L);
                        chain.setProgramCounter(11);
                        chain.pushOperand(2L);
                        chain.pushFrame(200, 5, 1, 2);
                        chain.writeLocal(0, 9L);
                        chain.setProgramCounter(3);
                        chain.pushOperand(3L);
                        assertEquals(2, chain.depth());
                        assertEquals(110, chain.chargedBytes());
                        assertThrows(IllegalStateException.class,
                                () -> taskFrame.bindLexicalScalar(77, "shadow", 12L),
                                "task-root scalar slots cannot alias an active nested call chain");
                        assertFalse(owner.isPoisoned(),
                                "rejected root/call-frame alias must not poison the actor");
                        return OresScheduler.await(gate);
                    }

                    identityAfter.set(OresScheduler.currentExecutionContext());
                    dispatchAfter.set(OresScheduler.currentDispatchId());
                    assertSame(chainRef.get(), chain);
                    assertEquals(2, chain.depth());
                    assertEquals(3, chain.programCounter());
                    assertEquals(5, chain.returnProgramCounter());
                    assertEquals(9L, chain.readLocal(0));
                    assertEquals(3L, chain.popOperand());
                    chain.popFrame();
                    assertEquals(1, chain.depth());
                    assertEquals(11, chain.programCounter());
                    assertEquals(7, chain.returnProgramCounter());
                    assertEquals(41L, chain.readLocal(0));
                    assertEquals(2L, chain.popOperand());
                    chain.popFrame();
                    assertEquals(0, chain.depth());
                    assertThrows(IllegalStateException.class, chain::resumeFromScheduler,
                            "a consumed scheduler resume ticket cannot be reused");
                    return OresScheduler.done(73);
                }));
            });

            actor.send("start");
            awaitCondition(() -> chainRef.get() != null
                    && arena.get() != null && arena.get().liveFrames() == 3);
            assertEquals(64 + 110, arena.get().usedBytes(),
                    "task root and both call frames remain charged while suspended");
            assertThrows(SecurityException.class, () -> chainRef.get().depth(),
                    "host thread cannot inspect a suspended actor-owned call chain");
            assertFalse(arena.get().isPoisoned(),
                    "foreign read denial must not poison the actor owner");

            gate.completeFromRuntime(1);
            awaitCondition(() -> result.get() != null && result.get().isDone());
            assertEquals(73, result.get().get(3, TimeUnit.SECONDS));
            assertSame(identityBefore.get(), identityAfter.get(),
                    "logical ExecutionContext identity survives await");
            assertNotEquals(dispatchBefore.get(), dispatchAfter.get(),
                    "resumed segment receives a fresh scheduler dispatch");
            assertEquals(0, arena.get().usedBytes());
            assertEquals(0, arena.get().liveFrames());
            assertTrue(actor.isAlive());
            actor.send("stop");
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty());
        }
    }

    @Test
    void twoSuccessiveAwaitsAtDifferentCallDepthsRetainOneActorFrameChain()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(), new ActorRuntime.DispatcherConfig(1, 1, 8))) {
            OresFuture<Integer> firstGate = new OresFuture<>();
            OresFuture<Integer> secondGate = new OresFuture<>();
            AtomicReference<ActorFrameArena> arena = new AtomicReference<>();
            AtomicReference<ActorCallContinuation> chainRef = new AtomicReference<>();
            AtomicReference<ExecutionContext> executionRef = new AtomicReference<>();
            AtomicReference<OresFuture<Integer>> taskRef = new AtomicReference<>();
            AtomicInteger turns = new AtomicInteger();
            AtomicReference<Long> firstDispatch = new AtomicReference<>();
            AtomicReference<Long> secondDispatch = new AtomicReference<>();

            var actor = runtime.<String>spawnShared(factory -> (message, turn) -> {
                if ("stop".equals(message)) { turn.self().stop(); return; }
                ActorFrameArena owner = ActorRuntime.currentActorFrameArena();
                arena.set(owner);
                taskRef.set(runtime.startActorTask(resume -> {
                    ActorSourceTaskFrame frame =
                            OresScheduler.currentActorSourceTaskFrame();
                    assertNotNull(frame);
                    ActorCallContinuation chain = frame.callContinuation();
                    int phase = turns.getAndIncrement();
                    if (phase == 0) {
                        chainRef.set(chain);
                        executionRef.set(OresScheduler.currentExecutionContext());
                        firstDispatch.set(OresScheduler.currentDispatchId());
                        chain.pushFrame(1, 5, 1, 1);
                        chain.writeLocal(0, 40L);
                        chain.setProgramCounter(7);
                        chain.pushOperand(2L);
                        return OresScheduler.await(firstGate);
                    }
                    assertSame(chainRef.get(), chain);
                    assertSame(executionRef.get(), OresScheduler.currentExecutionContext());
                    if (phase == 1) {
                        secondDispatch.set(OresScheduler.currentDispatchId());
                        assertNotEquals(firstDispatch.get(), secondDispatch.get());
                        assertEquals(1, chain.depth());
                        assertEquals(7, chain.programCounter());
                        assertEquals(40L, chain.readLocal(0));
                        chain.pushFrame(2, 8, 1, 1);
                        chain.writeLocal(0, 9L);
                        chain.setProgramCounter(11);
                        chain.pushOperand(3L);
                        return OresScheduler.await(secondGate);
                    }
                    assertEquals(2, phase, "late/duplicate continuation must not run");
                    assertNotEquals(secondDispatch.get(), OresScheduler.currentDispatchId());
                    assertEquals(2, chain.depth());
                    assertEquals(11, chain.programCounter());
                    assertEquals(8, chain.returnProgramCounter());
                    assertEquals(9L, chain.readLocal(0));
                    assertEquals(3L, chain.popOperand());
                    chain.popFrame();
                    assertEquals(1, chain.depth());
                    assertEquals(7, chain.programCounter());
                    assertEquals(5, chain.returnProgramCounter());
                    assertEquals(40L, chain.readLocal(0));
                    assertEquals(2L, chain.popOperand());
                    chain.popFrame();
                    return OresScheduler.done(42);
                }));
            });

            actor.send("start");
            awaitCondition(() -> chainRef.get() != null
                    && arena.get() != null && arena.get().liveFrames() == 2);
            assertEquals(64 + 46, arena.get().usedBytes());
            assertThrows(SecurityException.class, () -> chainRef.get().depth(),
                    "foreign thread must not read the suspended actor chain");
            firstGate.completeFromRuntime(1);
            awaitCondition(() -> turns.get() >= 2 && arena.get().liveFrames() == 3);
            assertEquals(64 + 46 + 46, arena.get().usedBytes(),
                    "both activations must remain owned across the second await");
            assertThrows(SecurityException.class, () -> chainRef.get().depth());
            assertTrue(secondGate.completeFromRuntime(2));
            awaitCondition(() -> taskRef.get() != null && taskRef.get().isDone());
            assertEquals(42, taskRef.get().get(3, TimeUnit.SECONDS));
            assertEquals(3, turns.get());
            awaitCondition(() -> arena.get().liveFrames() == 0
                    && arena.get().usedBytes() == 0);
            assertFalse(arena.get().isPoisoned());
            assertTrue(actor.isAlive(), "sibling actor/CONTROL must survive frame unwind");
            actor.send("stop");
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty());
        }
    }

    @Test
    void cancellationRetiresWholeSuspendedCallChainAfterSchedulerQuiescence()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(), new ActorRuntime.DispatcherConfig(1, 1, 8))) {
            OresFuture<Integer> gate = new OresFuture<>();
            AtomicReference<ActorFrameArena> arena = new AtomicReference<>();
            AtomicReference<ActorCallContinuation> chainRef = new AtomicReference<>();
            AtomicReference<OresFuture<Integer>> result = new AtomicReference<>();
            AtomicInteger turns = new AtomicInteger();

            var actor = runtime.<String>spawnShared(factory -> (message, turn) -> {
                if ("stop".equals(message)) { turn.self().stop(); return; }
                arena.set(ActorRuntime.currentActorFrameArena());
                result.set(runtime.startActorTask(resume -> {
                    if (turns.getAndIncrement() == 0) {
                        ActorSourceTaskFrame taskFrame =
                                OresScheduler.currentActorSourceTaskFrame();
                        ActorCallContinuation chain = taskFrame.callContinuation();
                        chainRef.set(chain);
                        chain.pushFrame(10, 2, 1, 1);
                        chain.writeLocal(0, 99L);
                        chain.setProgramCounter(4);
                        return OresScheduler.await(gate);
                    }
                    fail("cancelled suspended source task must not resume guest code");
                    return OresScheduler.done(0);
                }));
            });

            actor.send("start");
            awaitCondition(() -> chainRef.get() != null && arena.get() != null
                    && arena.get().liveFrames() == 2);
            assertEquals(64 + 28 + 18, arena.get().usedBytes());
            assertTrue(result.get().cancel(false));
            awaitCondition(() -> arena.get().usedBytes() == 0);
            assertTrue(chainRef.get().retired(),
                    "physical retirement invalidates the entire continuation owner");
            assertEquals(0, arena.get().liveFrames());
            assertTrue(arena.get().isPoisoned() == false);
            gate.completeFromRuntime(1);
            Thread.sleep(20);
            assertEquals(0, arena.get().liveFrames(),
                    "late producer completion cannot resurrect retired call frames");
            assertTrue(actor.isAlive());
            actor.send("stop");
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty());
        }
    }

    @Test
    void scalarCallMachineUsesSourceTaskContinuationInsteadOfSecondFrameStack()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(), new ActorRuntime.DispatcherConfig(1, 1, 8))) {
            OresFuture<Integer> gate = new OresFuture<>();
            AtomicReference<ActorFrameArena> arena = new AtomicReference<>();
            AtomicReference<ExecutionContext> firstIdentity = new AtomicReference<>();
            AtomicReference<ExecutionContext> secondIdentity = new AtomicReference<>();
            AtomicReference<ActorCallContinuation> owner = new AtomicReference<>();
            AtomicReference<OresFuture<Integer>> result = new AtomicReference<>();
            AtomicInteger runs = new AtomicInteger();

            ActorScalarCallMachine.Program nested = new ActorScalarCallMachine.Program(List.of(
                    new ActorScalarCallMachine.Function("parent", 0, 0, 2, List.of(
                            ActorScalarCallMachine.Instruction.constant(10),
                            ActorScalarCallMachine.Instruction.at(
                                    ActorScalarCallMachine.Op.CALL, 1),
                            ActorScalarCallMachine.Instruction.constant(2),
                            ActorScalarCallMachine.Instruction.of(ActorScalarCallMachine.Op.ADD),
                            ActorScalarCallMachine.Instruction.of(ActorScalarCallMachine.Op.RET),
                            ActorScalarCallMachine.Instruction.of(ActorScalarCallMachine.Op.TRAP))),
                    new ActorScalarCallMachine.Function("child", 1, 1, 2, List.of(
                            ActorScalarCallMachine.Instruction.at(
                                    ActorScalarCallMachine.Op.LOAD, 0),
                            ActorScalarCallMachine.Instruction.constant(3),
                            ActorScalarCallMachine.Instruction.of(ActorScalarCallMachine.Op.MUL),
                            ActorScalarCallMachine.Instruction.of(ActorScalarCallMachine.Op.RET),
                            ActorScalarCallMachine.Instruction.of(ActorScalarCallMachine.Op.TRAP)))
            ));
            ActorScalarCallMachine.Program constant = new ActorScalarCallMachine.Program(List.of(
                    new ActorScalarCallMachine.Function("again", 0, 0, 1, List.of(
                            ActorScalarCallMachine.Instruction.constant(77),
                            ActorScalarCallMachine.Instruction.of(ActorScalarCallMachine.Op.RET),
                            ActorScalarCallMachine.Instruction.of(ActorScalarCallMachine.Op.TRAP)))
            ));

            var actor = runtime.<String>spawnShared(factory -> (message, turn) -> {
                if ("stop".equals(message)) { turn.self().stop(); return; }
                arena.set(ActorRuntime.currentActorFrameArena());
                result.set(runtime.startActorTask(resume -> {
                    ActorSourceTaskFrame taskFrame =
                            OresScheduler.currentActorSourceTaskFrame();
                    assertNotNull(taskFrame);
                    ExecutionContext current = OresScheduler.currentExecutionContext();
                    if (runs.getAndIncrement() == 0) {
                        firstIdentity.set(current);
                        assertEquals(32L, ActorScalarCallMachine.execute(
                                nested, 0, List.of(), () -> { }));
                        owner.set(taskFrame.callContinuation());
                        assertEquals(0, owner.get().depth(),
                                "synchronous CALL/RET must leave the SourceTask chain empty");
                        return OresScheduler.await(gate);
                    }
                    secondIdentity.set(current);
                    assertSame(owner.get(), taskFrame.callContinuation());
                    assertEquals(77L, ActorScalarCallMachine.execute(
                            constant, 0, List.of(), () -> { }));
                    assertEquals(0, owner.get().depth());
                    return OresScheduler.done(109);
                }));
            });

            actor.send("start");
            awaitCondition(() -> result.get() != null && arena.get() != null
                    && firstIdentity.get() != null
                    && arena.get().usedBytes() == ActorSourceTaskFrame.FRAME_CHARGE_BYTES);
            assertEquals(1, arena.get().liveFrames(),
                    "machine activations must be popped; only the SourceTask root remains");
            gate.completeFromRuntime(1);
            awaitCondition(() -> result.get() != null && result.get().isDone());
            assertEquals(109, result.get().get(3, TimeUnit.SECONDS));
            assertSame(firstIdentity.get(), secondIdentity.get(),
                    "the dispatcher reuses the SourceTask logical identity");
            assertEquals(0, arena.get().usedBytes());
            assertEquals(0, arena.get().liveFrames());
            assertTrue(actor.isAlive());
            actor.send("stop");
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty());
        }
    }
}
