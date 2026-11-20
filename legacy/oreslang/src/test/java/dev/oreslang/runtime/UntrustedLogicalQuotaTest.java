package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** No new actor quota may be minted by await, cooperate or carrier migration. */
@Timeout(10)
final class UntrustedLogicalQuotaTest {
    // Host-side white-box test probes. Never capture these in an UNTRUSTED
    // BehaviorFactory/Behavior instance: factories must remain stateless.
    // This fixture instrumentation is not a guest-accessible capability API.
    private record SiblingProbe(
            AtomicReference<OresFuture<Integer>> retained,
            AtomicReference<Throwable> secondRejected,
            AtomicReference<Throwable> physicalBudgetDenied,
            java.util.concurrent.CountDownLatch attempted,
            OresFuture<Integer> waitFuture) { }
    private record NestedProbe(AtomicReference<OresFuture<Integer>> outerRef) { }
    private record FuelProbe(
            AtomicReference<OresFuture<Integer>> taskRef,
            AtomicInteger dispatches) { }
    private record DeadlineProbe(
            AtomicReference<OresFuture<Integer>> taskRef,
            AtomicInteger resumes,
            OresFuture<Integer> awaited) { }

    private static volatile SiblingProbe siblingProbe;
    private static volatile NestedProbe nestedProbe;
    private static volatile FuelProbe fuelProbe;
    private static volatile DeadlineProbe deadlineProbe;

    private record TimedAdmissionProbe(
            AtomicReference<OresFuture<Integer>> root,
            java.util.List<OresFuture<Integer>> children,
            AtomicReference<Throwable> rejected,
            java.util.concurrent.CountDownLatch admitted,
            OresFuture<Integer> never) { }

    private static volatile TimedAdmissionProbe timedAdmissionProbe;

    private record BlockedCarrierProbe(
            AtomicReference<OresFuture<Integer>> task,
            java.util.concurrent.CountDownLatch entered,
            java.util.concurrent.CountDownLatch release,
            AtomicReference<Throwable> siblingRejected,
            java.util.concurrent.CountDownLatch siblingAttempted) { }
    private static volatile BlockedCarrierProbe blockedCarrierProbe;

    private record ActorSingleWriterProbe(
            AtomicInteger executing,
            AtomicInteger maxConcurrent,
            java.util.List<OresFuture<Integer>> children,
            AtomicReference<OresFuture<Integer>> root,
            java.util.concurrent.CountDownLatch spawned,
            java.util.concurrent.CountDownLatch messages) { }
    private static volatile ActorSingleWriterProbe actorSingleWriterProbe;

    /** Test only: assert that nested source work never overlaps actor messages. */
    private static void enterSingleWriterProbe(ActorSingleWriterProbe probe) {
        int entered = probe.executing().incrementAndGet();
        probe.maxConcurrent().accumulateAndGet(entered, Math::max);
        try {
            Thread.sleep(2);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        } finally {
            probe.executing().decrementAndGet();
        }
    }

    /** White-box count read under the runtime's lifecycle lock. */
    private static int actorTimedAdmissions(
            ActorRuntime runtime, ActorRuntime.ActorRef<?> actor) throws Exception {
        java.lang.reflect.Field actorsField =
                ActorRuntime.class.getDeclaredField("actors");
        actorsField.setAccessible(true);
        Object cell = ((java.util.Map<?, ?>) actorsField.get(runtime)).get(actor.id());
        if (cell == null) throw new IllegalStateException("actor cell not registered");
        java.lang.reflect.Field lockField = cell.getClass().getDeclaredField("lifecycleLock");
        java.lang.reflect.Field countField = cell.getClass()
                .getDeclaredField("activeUntrustedTimedAdmissions");
        lockField.setAccessible(true);
        countField.setAccessible(true);
        synchronized (lockField.get(cell)) {
            return countField.getInt(cell);
        }
    }

    /** No capture of the enclosing JUnit instance inside an untrusted actor. */
    private static final class TimedAdmissionTask implements OresScheduler.Task<Integer> {
        private final ActorRuntime runtime;

        private TimedAdmissionTask(ActorRuntime runtime) {
            this.runtime = runtime;
        }

        @Override
        public OresScheduler.Step<Integer> resume(OresScheduler.Resume resume) {
            if (!resume.initial()) return OresScheduler.done(1);
            TimedAdmissionProbe probe = timedAdmissionProbe;
            for (int i = 0; i < 31; i++) {
                probe.children().add(runtime.startActorTask(
                        next -> OresScheduler.await(timedAdmissionProbe.never())));
            }
            try {
                runtime.startActorTask(
                        next -> OresScheduler.await(timedAdmissionProbe.never()));
            } catch (java.util.concurrent.RejectedExecutionException expected) {
                probe.rejected().set(expected);
            } finally {
                probe.admitted().countDown();
            }
            return OresScheduler.await(probe.never());
        }
    }

    /** Static nested task avoids capturing the enclosing test instance. */
    private static final class NestedSourceQuotaTask implements OresScheduler.Task<Integer> {
        private final ActorRuntime runtime;
        private OresFuture<Integer> child;

        private NestedSourceQuotaTask(ActorRuntime runtime) {
            this.runtime = runtime;
        }

        @Override
        public OresScheduler.Step<Integer> resume(OresScheduler.Resume resume) {
            runtime.schedulerSafepoint();
            if (resume.initial()) {
                child = runtime.startActorTask(next -> {
                    runtime.schedulerSafepoint();
                    return OresScheduler.done(7);
                });
                return OresScheduler.await(child);
            }
            return OresScheduler.done((Integer) resume.value());
        }
    }

    @Test
    void fuelDebtSurvivesAnyNumberOfCooperateHandoffs() {
        AtomicLong wall = new AtomicLong();
        AtomicLong cpu = new AtomicLong(100);
        ActorRuntime.UntrustedLogicalQuota quota = new ActorRuntime.UntrustedLogicalQuota(
                1_000_000L, 3, 0, 0, wall::get, cpu::get);

        for (int turn = 0; turn < 3; turn++) {
            quota.enter();
            quota.chargeSafepoint();
            quota.leave();
            // Every re-entry represents a fresh carrier dispatch with a new
            // per-thread CPU counter; fuel must NOT be reinitialized.
            cpu.addAndGet(100);
        }
        quota.enter();
        try {
            var exceeded = assertThrows(
                    ActorRuntime.UntrustedActorQuotaExceededException.class,
                    quota::chargeSafepoint);
            assertEquals(
                    ActorRuntime.UntrustedActorQuotaExceededException.Resource.FUEL,
                    exceeded.resource());
        } finally {
            quota.leave();
        }
    }

    @Test
    void wallDeadlineIncludesTimeSuspendedOnFuture() {
        AtomicLong wall = new AtomicLong(10);
        AtomicLong cpu = new AtomicLong(100);
        ActorRuntime.UntrustedLogicalQuota quota = new ActorRuntime.UntrustedLogicalQuota(
                10, 10, 10, 0, wall::get, cpu::get);
        quota.enter();
        quota.check();
        quota.leave();
        wall.set(21);
        cpu.set(1000); // fake a different carrier on resumption
        quota.enter();
        try {
            var exceeded = assertThrows(
                    ActorRuntime.UntrustedActorQuotaExceededException.class,
                    quota::check);
            assertEquals(
                    ActorRuntime.UntrustedActorQuotaExceededException.Resource.WALL_TIME,
                    exceeded.resource());
        } finally {
            quota.leave();
        }
    }

    @Test
    void cpuDebtAccumulatesAcrossUnrelatedCarrierThreadCpuClocks() {
        AtomicLong wall = new AtomicLong();
        AtomicLong cpu = new AtomicLong(100);
        ActorRuntime.UntrustedLogicalQuota quota = new ActorRuntime.UntrustedLogicalQuota(
                10, 10, 0, 0, wall::get, cpu::get);
        quota.enter();
        cpu.set(106);
        quota.check(); // 6 CPU ns consumed on first carrier
        quota.leave();
        cpu.set(900); // new carrier with unrelated absolute CPU counter
        quota.enter();
        try {
            cpu.set(905); // total logical CPU usage = 11 ns
            var exceeded = assertThrows(
                    ActorRuntime.UntrustedActorQuotaExceededException.class,
                    quota::check);
            assertEquals(
                    ActorRuntime.UntrustedActorQuotaExceededException.Resource.CPU_TIME,
                    exceeded.resource());
        } finally {
            quota.leave();
        }
    }

    @Test
    void nestedOrConcurrentCarrierAdmissionMustNotDoubleCharge() {
        AtomicLong now = new AtomicLong();
        ActorRuntime.UntrustedLogicalQuota quota = new ActorRuntime.UntrustedLogicalQuota(
                100, 10, 0, 0, now::get, now::get);
        quota.enter();
        assertThrows(IllegalStateException.class, quota::enter);
        quota.leave();
        quota.enter();
        quota.leave();
        assertThrows(IllegalStateException.class, quota::leave);
    }

    @Test
    void failingCpuSamplerDoesNotStrandTheOldCarrierLease() {
        AtomicInteger samples = new AtomicInteger();
        ActorRuntime.UntrustedLogicalQuota quota = new ActorRuntime.UntrustedLogicalQuota(
                1000, 3, 0, 0, () -> 0, () -> {
                    if (samples.incrementAndGet() == 2) {
                        throw new IllegalStateException("mock CPU sampler failure");
                    }
                    return 100;
                });
        quota.enter();
        assertThrows(IllegalStateException.class, quota::leave);
        // The failing leave MUST clear segmentActive so future requests do not
        // inherit an active physical carrier identity.
        quota.enter();
        quota.chargeSafepoint();
        quota.leave();
    }

    @Test
    void exhaustedFuelNeverUnderflowsOrWrapsIntoASecondBudget() {
        AtomicLong clock = new AtomicLong();
        ActorRuntime.UntrustedLogicalQuota quota = new ActorRuntime.UntrustedLogicalQuota(
                10_000L, 0, 0, 0, clock::get, clock::get);
        quota.enter();
        try {
            for (int i = 0; i < 3; i++) {
                var failure = assertThrows(
                        ActorRuntime.UntrustedActorQuotaExceededException.class,
                        quota::chargeSafepoint);
                assertEquals(
                        ActorRuntime.UntrustedActorQuotaExceededException.Resource.FUEL,
                        failure.resource());
            }
        } finally {
            quota.leave();
        }
    }

    @Test
    void concurrentCarrierAdmissionsCannotBothEnterSameLogicalQuota() throws Exception {
        AtomicLong clock = new AtomicLong();
        ActorRuntime.UntrustedLogicalQuota quota = new ActorRuntime.UntrustedLogicalQuota(
                1_000_000L, 20, 0, 0, clock::get, clock::get);
        java.util.concurrent.CountDownLatch entered =
                new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release =
                new java.util.concurrent.CountDownLatch(1);
        AtomicReference<Throwable> overlapping = new AtomicReference<>();
        Thread first = Thread.ofPlatform().start(() -> {
            quota.enter();
            entered.countDown();
            try {
                release.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                quota.leave();
            }
        });
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            Thread second = Thread.ofPlatform().start(() -> {
                try {
                    quota.enter();
                } catch (Throwable failure) {
                    overlapping.set(failure);
                }
            });
            second.join(3_000);
            assertTrue(!second.isAlive(), "second admission may not block forever");
            assertTrue(overlapping.get() instanceof IllegalStateException,
                    "a quota cannot be entered by two carrier threads simultaneously");
        } finally {
            release.countDown();
            first.join(3_000);
        }
        quota.enter();
        quota.chargeSafepoint();
        quota.leave();
    }

    @Test
    void untrustedActorCannotMintSiblingRootQuotasWhileTaskIsSuspended()
            throws Exception {
        AtomicReference<OresFuture<Integer>> retained = new AtomicReference<>();
        AtomicReference<Throwable> secondRejected = new AtomicReference<>();
        AtomicReference<Throwable> physicalBudgetDenied = new AtomicReference<>();
        java.util.concurrent.CountDownLatch attempted =
                new java.util.concurrent.CountDownLatch(1);
        OresFuture<Integer> wait = new OresFuture<>();
        siblingProbe = new SiblingProbe(retained, secondRejected, physicalBudgetDenied, attempted, wait);
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnUntrusted(context -> (message, turn) -> {
                SiblingProbe probe = siblingProbe;
                if (message.equals("first")) {
                    probe.retained().set(turn.runtime().startActorTask(resume ->
                            resume.initial() ? OresScheduler.<Integer>await(probe.waitFuture())
                                    : OresScheduler.done(7)));
                } else if (message.equals("sibling")) {
                    try {
                        turn.runtime().startActorTask(
                                resume -> OresScheduler.done(999));
                    } catch (SecurityException expected) {
                        probe.secondRejected().set(expected);
                    } finally {
                        try {
                            turn.runtime().schedulerSafepoint();
                        } catch (SecurityException expected) {
                            probe.physicalBudgetDenied().set(expected);
                        }
                        probe.attempted().countDown();
                    }
                }
            });
            actor.send("first");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (retained.get() == null && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertTrue(retained.get() != null, "first source task must be admitted");
            actor.send("sibling");
            assertTrue(attempted.await(3, TimeUnit.SECONDS));
            assertTrue(secondRejected.get() instanceof SecurityException,
                    "a second top-level source task cannot mint another quota");
            assertTrue(physicalBudgetDenied.get() instanceof SecurityException,
                    "a physical user turn cannot spend another quota while suspended");
            // First source Future is still registered while the second message
            // runs; completing it lets the quota cohort retire.
            wait.completeFromRuntime(1);
            assertEquals(7, retained.get().get(3, TimeUnit.SECONDS));
            actor.stop();
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
        } finally {
            siblingProbe = null;
        }
    }

    @Test
    void nestedUntrustedTasksShareOneQuotaAndRetireWithoutUnderflow()
            throws Exception {
        AtomicReference<OresFuture<Integer>> outerRef = new AtomicReference<>();
        nestedProbe = new NestedProbe(outerRef);
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnUntrusted(context -> (message, turn) ->
                    nestedProbe.outerRef().set(turn.runtime().startActorTask(
                            new NestedSourceQuotaTask(turn.runtime()))));
            actor.send("nested");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (outerRef.get() == null && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertTrue(outerRef.get() != null, "parent task was not admitted");
            assertEquals(7, outerRef.get().get(3, TimeUnit.SECONDS));
            actor.stop();
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
        } finally {
            nestedProbe = null;
        }
    }

    @Test
    void actualUntrustedActorCannotRefuelThroughRepeatedCooperate() throws Exception {
        AtomicReference<OresFuture<Integer>> taskRef = new AtomicReference<>();
        AtomicInteger dispatches = new AtomicInteger();
        fuelProbe = new FuelProbe(taskRef, dispatches);
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnUntrusted(context -> (message, turn) ->
                    fuelProbe.taskRef().set(turn.runtime().startActorTask(resume -> {
                        fuelProbe.dispatches().incrementAndGet();
                        // The UNTRUSTED default budget is 250ms x 256 = 64,000
                        // fuel units. Six 13,000-checkpoint physical dispatches
                        // exceed that cumulative budget despite each individual
                        // dispatch remaining under it.
                        for (int i = 0; i < 13_000; i++) {
                            turn.runtime().schedulerSafepoint();
                        }
                        return OresScheduler.cooperate();
                    })));
            actor.send("start");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (taskRef.get() == null && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            OresFuture<Integer> result = taskRef.get();
            assertTrue(result != null, "source task was not admitted");
            var failed = assertThrows(ExecutionException.class,
                    () -> result.get(5, TimeUnit.SECONDS));
            assertTrue(
                    failed.getCause() instanceof ActorRuntime.UntrustedActorQuotaExceededException,
                    () -> "unexpected failed task: " + failed.getCause());
            assertEquals(
                    ActorRuntime.UntrustedActorQuotaExceededException.Resource.FUEL,
                    ((ActorRuntime.UntrustedActorQuotaExceededException) failed.getCause()).resource());
            assertTrue(dispatches.get() >= 5, "fuel must be charged across physical resumes");
            actor.stop();
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
        } finally {
            fuelProbe = null;
        }
    }


    @Test
    void neverSettlingUntrustedAwaitExpiresAndAllowsNewCohort() throws Exception {
        AtomicReference<OresFuture<Integer>> resultRef = new AtomicReference<>();
        AtomicInteger resumes = new AtomicInteger();
        OresFuture<Integer> unresolved = new OresFuture<>();
        deadlineProbe = new DeadlineProbe(resultRef, resumes, unresolved);
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnUntrusted(context -> (message, turn) -> {
                if (message.equals("await")) {
                    deadlineProbe.taskRef().set(turn.runtime().startActorTask(resume -> {
                        deadlineProbe.resumes().incrementAndGet();
                        return OresScheduler.await(deadlineProbe.awaited());
                    }));
                } else if (message.equals("restart")) {
                    deadlineProbe.taskRef().set(turn.runtime().startActorTask(
                            resume -> OresScheduler.done(23)));
                }
            });
            actor.send("await");
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (resultRef.get() == null && System.nanoTime() < until) {
                Thread.onSpinWait();
            }
            OresFuture<Integer> first = resultRef.get();
            assertTrue(first != null, "untrusted await must be admitted");
            var failed = assertThrows(ExecutionException.class,
                    () -> first.get(3, TimeUnit.SECONDS));
            assertTrue(failed.getCause()
                            instanceof ActorRuntime.UntrustedActorQuotaExceededException,
                    () -> "unexpected suspended failure: " + failed.getCause());
            assertEquals(ActorRuntime.UntrustedActorQuotaExceededException.Resource.WALL_TIME,
                    ((ActorRuntime.UntrustedActorQuotaExceededException)
                            failed.getCause()).resource());
            assertEquals(1, resumes.get(),
                    "timer must not resume the guest continuation to expire it");
            assertEquals(0, unresolved.pendingRuntimeWaiterCount(),
                    "deadline must detach the unresolved producer waiter");

            actor.send("restart");
            until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (resultRef.get() == first && System.nanoTime() < until) {
                Thread.onSpinWait();
            }
            assertTrue(resultRef.get() != first,
                    "expired logical cohort must release admission for a new message");
            assertEquals(23, resultRef.get().get(3, TimeUnit.SECONDS));
            actor.stop();
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
        } finally {
            deadlineProbe = null;
        }
    }


    @Test
    void oneUntrustedActorCannotMonopolizeGlobalTimedAdmissionCapacity()
            throws Exception {
        AtomicReference<OresFuture<Integer>> root = new AtomicReference<>();
        java.util.List<OresFuture<Integer>> children =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        AtomicReference<Throwable> rejected = new AtomicReference<>();
        java.util.concurrent.CountDownLatch admitted =
                new java.util.concurrent.CountDownLatch(1);
        OresFuture<Integer> never = new OresFuture<>();
        timedAdmissionProbe = new TimedAdmissionProbe(
                root, children, rejected, admitted, never);
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnUntrusted(context -> (message, turn) ->
                    timedAdmissionProbe.root().set(
                            turn.runtime().startActorTask(
                                    new TimedAdmissionTask(turn.runtime()))));
            actor.send("start");
            assertTrue(admitted.await(3, TimeUnit.SECONDS),
                    "nested tasks should attempt their bounded admission");
            assertEquals(31, children.size(),
                    "root plus 31 nested source tasks exhaust the per-actor cap");
            assertTrue(rejected.get() instanceof java.util.concurrent.RejectedExecutionException,
                    "the 33rd task must fail closed before consuming global capacity");
            assertTrue(rejected.get().getMessage().contains("timed source-task admission"),
                    rejected.get()::getMessage);
            actor.stop();
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
        } finally {
            timedAdmissionProbe = null;
        }
    }

    @Test
    void timedActorSlotCannotBeReusedBeforeCancelledGuestCarrierExits()
            throws Exception {
        AtomicReference<OresFuture<Integer>> sourceTask = new AtomicReference<>();
        java.util.concurrent.CountDownLatch entered =
                new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release =
                new java.util.concurrent.CountDownLatch(1);
        AtomicReference<Throwable> siblingRejected = new AtomicReference<>();
        java.util.concurrent.CountDownLatch siblingAttempted =
                new java.util.concurrent.CountDownLatch(1);
        blockedCarrierProbe = new BlockedCarrierProbe(
                sourceTask, entered, release, siblingRejected, siblingAttempted);
        int originalGlobal = OresScheduler.availableWallDeadlineAdmissions();
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnUntrusted(context -> (message, turn) -> {
                BlockedCarrierProbe probe = blockedCarrierProbe;
                if (message.equals("start")) {
                    probe.task().set(turn.runtime().startActorTask(resume -> {
                        probe.entered().countDown();
                        try {
                            if (!probe.release().await(3, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("test carrier not released");
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(interrupted);
                        }
                        return OresScheduler.done(42);
                    }));
                } else if (message.equals("sibling")) {
                    try {
                        turn.runtime().startActorTask(
                                resume -> OresScheduler.done(99));
                    } catch (Throwable failure) {
                        probe.siblingRejected().set(failure);
                    } finally {
                        probe.siblingAttempted().countDown();
                    }
                }
            });
            try {
                actor.send("start");
                assertTrue(entered.await(3, TimeUnit.SECONDS),
                        "test must enter a physical source actor carrier");
                OresFuture<Integer> task = sourceTask.get();
                assertTrue(task != null);
                assertEquals(1, actorTimedAdmissions(runtime, actor));
                assertTrue(task.cancel(false),
                        "host cancellation should settle the public Future");
                assertTrue(task.isCancelled());
                assertEquals(1, actorTimedAdmissions(runtime, actor),
                        "guest carrier is still executing: actor timed slot must stay reserved");
                assertEquals(originalGlobal - 1,
                        OresScheduler.availableWallDeadlineAdmissions(),
                        "global admission must likewise wait for physical quiescence");
                actor.send("sibling");
                assertTrue(siblingAttempted.await(3, TimeUnit.SECONDS),
                        "mailbox must process the competing source-task admission");
                assertTrue(siblingRejected.get() instanceof SecurityException,
                        () -> "cancellation must not retire the logical quota while its carrier runs: "
                                + siblingRejected.get());
                assertEquals(1, actorTimedAdmissions(runtime, actor),
                        "rejected sibling must not consume another timed admission");
            } finally {
                release.countDown();
            }
            long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (actorTimedAdmissions(runtime, actor) != 0
                    && System.nanoTime() < limit) Thread.sleep(1);
            assertEquals(0, actorTimedAdmissions(runtime, actor),
                    "actor timed slot must retire after the physical carrier exits");
            actor.stop();
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            blockedCarrierProbe = null;
        }
        assertEquals(originalGlobal, OresScheduler.availableWallDeadlineAdmissions());
    }

    @Test
    void realActorMailboxSerializesNestedTasksCooperationAndOrdinaryMessages()
            throws Exception {
        ActorSingleWriterProbe probe = new ActorSingleWriterProbe(
                new AtomicInteger(), new AtomicInteger(),
                new java.util.concurrent.CopyOnWriteArrayList<>(),
                new AtomicReference<>(),
                new java.util.concurrent.CountDownLatch(1),
                new java.util.concurrent.CountDownLatch(8));
        actorSingleWriterProbe = probe;
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnUntrusted(context -> (message, turn) -> {
                ActorSingleWriterProbe active = actorSingleWriterProbe;
                enterSingleWriterProbe(active);
                if (message.equals("start")) {
                    active.root().set(turn.runtime().startActorTask(resume -> {
                        enterSingleWriterProbe(active);
                        for (int i = 0; i < 6; i++) {
                            AtomicInteger turns = new AtomicInteger();
                            active.children().add(turn.runtime().startActorTask(childResume -> {
                                enterSingleWriterProbe(active);
                                return turns.incrementAndGet() < 3
                                        ? OresScheduler.<Integer>cooperate()
                                        : OresScheduler.done(100);
                            }));
                        }
                        active.spawned().countDown();
                        return OresScheduler.done(1);
                    }));
                } else if (message.equals("ping")) {
                    active.messages().countDown();
                }
            });
            actor.send("start");
            assertTrue(probe.spawned().await(3, TimeUnit.SECONDS),
                    "root actor task must enqueue its nested source continuations");
            for (int i = 0; i < 8; i++) actor.send("ping");
            assertTrue(probe.messages().await(3, TimeUnit.SECONDS),
                    "ordinary actor messages must run alongside source continuations");
            assertEquals(1, probe.root().get().get(3, TimeUnit.SECONDS));
            assertEquals(6, probe.children().size());
            for (OresFuture<Integer> child : probe.children()) {
                assertEquals(100, child.get(3, TimeUnit.SECONDS));
            }
            assertEquals(1, probe.maxConcurrent().get(),
                    "actual actor dispatcher must never run two guest turns concurrently");
            assertEquals(0, probe.executing().get());
            actor.stop();
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
        } finally {
            actorSingleWriterProbe = null;
        }
    }

    @Test
    void actualUntrustedActorTaskCannotResetDeadlineAfterAwait() throws Exception {
        AtomicReference<OresFuture<Integer>> taskRef = new AtomicReference<>();
        AtomicInteger resumes = new AtomicInteger();
        OresFuture<Integer> awaited = new OresFuture<>();
        deadlineProbe = new DeadlineProbe(taskRef, resumes, awaited);
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnUntrusted(context -> (message, turn) ->
                    deadlineProbe.taskRef().set(turn.runtime().startActorTask(resume -> {
                        int step = deadlineProbe.resumes().getAndIncrement();
                        turn.runtime().schedulerSafepoint();
                        if (step == 0) return OresScheduler.await(deadlineProbe.awaited());
                        return OresScheduler.done(42);
                    })));
            actor.send("start");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (taskRef.get() == null && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            OresFuture<Integer> result = taskRef.get();
            assertTrue(result != null, "source task was not admitted");
            // Greater than the fixed 250ms untrusted logical wall deadline.
            Thread.sleep(300);
            awaited.completeFromRuntime(7);
            var failed = assertThrows(ExecutionException.class,
                    () -> result.get(5, TimeUnit.SECONDS));
            assertTrue(
                    failed.getCause() instanceof ActorRuntime.UntrustedActorQuotaExceededException,
                    () -> "unexpected failed task: " + failed.getCause());
            assertEquals(
                    ActorRuntime.UntrustedActorQuotaExceededException.Resource.WALL_TIME,
                    ((ActorRuntime.UntrustedActorQuotaExceededException) failed.getCause()).resource());
            actor.stop();
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
        } finally {
            deadlineProbe = null;
        }
    }
}
