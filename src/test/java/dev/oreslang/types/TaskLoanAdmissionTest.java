package dev.oreslang.types;

import dev.oreslang.ast.Ast;
import dev.oreslang.runtime.AsyncRuntime;
import dev.oreslang.runtime.OresScheduler;
import dev.oreslang.runtime.OresTask;
import dev.oreslang.runtime.OresTaskScope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class TaskLoanAdmissionTest {
    private static final TaskAccessVerifier.Root ROOT =
            new TaskAccessVerifier.Root("PROCESS", 901, 1, true);

    private static final Ast.Program PROGRAM =
            new Ast.Program(List.of());
    private static final SendSyncTraits SOURCE_PROOF =
            new SendSyncTraits(PROGRAM, Set.of());
    private static final ThreadSafetyTraits.NominalProof RESOLVED_PROOF =
            (type, trait) -> false;

    private static TaskLoanAdmission admission(TaskAccessVerifier verifier) {
        var claim = new TaskAccessVerifier.Claim(
                TaskAccessVerifier.Place.whole(ROOT),
                TaskAccessVerifier.Mode.READ);
        var typed = new TaskAccessVerifier.CoherentTypedClaim(
                claim, new Ast.TypeRef("int", List.of(), false),
                Types.Primitive.INT);
        TaskAccessVerifier.CoherentAccessProof proof =
                TaskAccessVerifier.proveScopedReadBorrow(
                        List.of(typed), SOURCE_PROOF, RESOLVED_PROOF);
        return verifier.admitProvenScopedReadRuntimeBound(proof, PROGRAM);
    }

    private static OresTaskScope.CompilerScopeEpoch epochForTest() {
        try {
            var constructor = OresTaskScope.CompilerScopeEpoch.class
                    .getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static OresScheduler.CompilerTaskHandle<?> schedulerChildForTest() {
        try {
            var constructor = OresScheduler.CompilerTaskHandle.class
                    .getDeclaredConstructor(Object.class, Object.class);
            constructor.setAccessible(true);
            return constructor.newInstance(new Object(), new Object());
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> OresTask<T> startCompilerFrameForTest(
            OresTaskScope scope,
            OresScheduler scheduler,
            OresScheduler.Task<T> frame,
            TaskLoanAdmission admission) throws Exception {
        var method = OresTaskScope.class.getDeclaredMethod(
                "startCompilerReadBorrowFrame",
                OresScheduler.class,
                OresScheduler.Task.class,
                Runnable.class,
                OresTaskScope.CompilerReadLoanRetirement.class);
        method.setAccessible(true);
        try {
            return (OresTask<T>) method.invoke(
                    scope, scheduler, frame, (Runnable) () -> { }, admission);
        } catch (java.lang.reflect.InvocationTargetException wrapped) {
            Throwable cause = wrapped.getCause();
            if (cause instanceof Exception exception) throw exception;
            if (cause instanceof Error error) throw error;
            throw new AssertionError(cause);
        }
    }

    @Test
    void staticWriteProofCannotMintRuntimeLoanAuthority() {
        TaskAccessVerifier verifier = new TaskAccessVerifier();
        var write = new TaskAccessVerifier.CoherentTypedClaim(
                new TaskAccessVerifier.Claim(
                        TaskAccessVerifier.Place.whole(ROOT),
                        TaskAccessVerifier.Mode.WRITE),
                Ast.TypeRef.simple("int"),
                Types.Primitive.INT);
        TaskAccessVerifier.CoherentAccessProof staticProof =
                TaskAccessVerifier.proveCoherentTyped(
                        List.of(write), SOURCE_PROOF, RESOLVED_PROOF);

        assertThrows(IllegalArgumentException.class,
                () -> verifier.admitProvenRuntimeBound(
                        staticProof, PROGRAM),
                "analysis-only WRITE proof must never become a runtime loan");
        assertEquals(0, verifier.activeChildren());
    }

    @Test
    void physicalRetirementRequiresExactScopeOwnerAndSchedulerChild()
            throws Exception {
        TaskAccessVerifier verifier = new TaskAccessVerifier();
        TaskLoanAdmission admission = admission(verifier);
        OresTaskScope.CompilerScopeEpoch scopeEpoch = epochForTest();
        OresTaskScope.CompilerScopeEpoch foreignEpoch = epochForTest();
        OresScheduler.CompilerTaskHandle<?> exactSchedulerChild =
                schedulerChildForTest();
        OresScheduler.CompilerTaskHandle<?> lookalikeSchedulerChild =
                schedulerChildForTest();

        try (AsyncRuntime runtime = new AsyncRuntime();
             OresTaskScope scope = new OresTaskScope(runtime)) {
            OresTask<Integer> child = scope.submit(() -> 1);
            OresTask<Integer> lookalike = scope.submit(() -> 2);
            assertNull(AsyncRuntime.await(child.getDone()));
            assertNull(AsyncRuntime.await(lookalike.getDone()));

            admission.bindScope(scopeEpoch);
            admission.bindChild(scopeEpoch, child);
            admission.bindSchedulerChild(
                    scopeEpoch, child, exactSchedulerChild);

            assertThrows(IllegalArgumentException.class,
                    () -> admission.retireAfterPhysicalQuiescence(
                            foreignEpoch, child, exactSchedulerChild));
            assertThrows(IllegalArgumentException.class,
                    () -> admission.retireAfterPhysicalQuiescence(
                            scopeEpoch, lookalike, exactSchedulerChild));
            assertThrows(IllegalArgumentException.class,
                    () -> admission.retireAfterPhysicalQuiescence(
                            scopeEpoch, child, lookalikeSchedulerChild));
            assertEquals(1, verifier.activeChildren(),
                    "identity mismatch must not release the compiler loan");

            admission.retireAfterPhysicalQuiescence(
                    scopeEpoch, child, exactSchedulerChild);
            assertEquals(0, verifier.activeChildren());
            assertTrue(admission.retiredForTesting());
            assertThrows(IllegalStateException.class,
                    () -> admission.retireAfterPhysicalQuiescence(
                            scopeEpoch, child, exactSchedulerChild),
                    "one admission cannot retire twice");
        }
    }

    @Test
    void preChildRetirementIsASeparateOneShotLifecycleEdge() {
        TaskAccessVerifier verifier = new TaskAccessVerifier();
        TaskLoanAdmission admission = admission(verifier);
        OresTaskScope.CompilerScopeEpoch scopeEpoch = epochForTest();

        admission.bindScope(scopeEpoch);
        assertEquals(1, verifier.activeChildren());
        admission.retireBeforeChildBound(scopeEpoch);
        assertEquals(0, verifier.activeChildren());
        assertTrue(admission.retiredForTesting());
        assertThrows(IllegalStateException.class,
                () -> admission.retireBeforeChildBound(scopeEpoch));
    }

    @Test
    void ownerBoundPreSchedulerRetirementRequiresExactOwner()
            throws Exception {
        TaskAccessVerifier verifier = new TaskAccessVerifier();
        TaskLoanAdmission admission = admission(verifier);
        OresTaskScope.CompilerScopeEpoch scopeEpoch = epochForTest();

        try (AsyncRuntime runtime = new AsyncRuntime();
             OresTaskScope scope = new OresTaskScope(runtime)) {
            OresTask<Integer> exact = scope.submit(() -> 10);
            OresTask<Integer> lookalike = scope.submit(() -> 11);
            assertNull(AsyncRuntime.await(exact.getDone()));
            assertNull(AsyncRuntime.await(lookalike.getDone()));

            admission.bindScope(scopeEpoch);
            admission.bindChild(scopeEpoch, exact);

            assertThrows(IllegalStateException.class,
                    () -> admission.retireBeforeChildBound(scopeEpoch));
            assertThrows(IllegalArgumentException.class,
                    () -> admission.retireBeforeSchedulerChildBound(
                            scopeEpoch, lookalike));
            assertEquals(1, verifier.activeChildren());

            admission.retireBeforeSchedulerChildBound(scopeEpoch, exact);
            assertEquals(0, verifier.activeChildren());
            assertTrue(admission.retiredForTesting());
        }
    }

    @Test
    void schedulerBoundNeverStartedRetirementRequiresExactOwnerAndHandle()
            throws Exception {
        TaskAccessVerifier verifier = new TaskAccessVerifier();
        TaskLoanAdmission admission = admission(verifier);
        OresTaskScope.CompilerScopeEpoch scopeEpoch = epochForTest();
        OresScheduler.CompilerTaskHandle<?> exactSchedulerChild =
                schedulerChildForTest();
        OresScheduler.CompilerTaskHandle<?> lookalikeSchedulerChild =
                schedulerChildForTest();

        try (AsyncRuntime runtime = new AsyncRuntime();
             OresTaskScope scope = new OresTaskScope(runtime)) {
            OresTask<Integer> exact = scope.submit(() -> 12);
            OresTask<Integer> lookalike = scope.submit(() -> 13);
            assertNull(AsyncRuntime.await(exact.getDone()));
            assertNull(AsyncRuntime.await(lookalike.getDone()));

            admission.bindScope(scopeEpoch);
            admission.bindChild(scopeEpoch, exact);
            admission.bindSchedulerChild(
                    scopeEpoch, exact, exactSchedulerChild);

            assertThrows(IllegalArgumentException.class,
                    () -> admission.retireBeforeSchedulerChildBound(
                            scopeEpoch, exact));
            assertThrows(IllegalArgumentException.class,
                    () -> admission.retireNeverStarted(
                            scopeEpoch, lookalike, exactSchedulerChild));
            assertThrows(IllegalArgumentException.class,
                    () -> admission.retireNeverStarted(
                            scopeEpoch, exact, lookalikeSchedulerChild));
            assertEquals(1, verifier.activeChildren());

            admission.retireNeverStarted(
                    scopeEpoch, exact, exactSchedulerChild);
            assertEquals(0, verifier.activeChildren());
            assertTrue(admission.retiredForTesting());
            assertThrows(IllegalStateException.class,
                    () -> admission.retireNeverStarted(
                            scopeEpoch, exact, exactSchedulerChild));
        }
    }

    @Test
    void aChildCannotBeReboundOrMovedAcrossRuntimeScopeEpochs()
            throws Exception {
        TaskAccessVerifier verifier = new TaskAccessVerifier();
        TaskLoanAdmission admission = admission(verifier);
        OresTaskScope.CompilerScopeEpoch firstEpoch = epochForTest();
        OresTaskScope.CompilerScopeEpoch secondEpoch = epochForTest();

        try (AsyncRuntime runtime = new AsyncRuntime();
             OresTaskScope scope = new OresTaskScope(runtime)) {
            OresTask<Integer> child = scope.submit(() -> 3);
            assertNull(AsyncRuntime.await(child.getDone()));

            OresScheduler.CompilerTaskHandle<?> schedulerChild =
                    schedulerChildForTest();
            admission.bindScope(firstEpoch);
            assertThrows(IllegalArgumentException.class,
                    () -> admission.bindChild(secondEpoch, child));
            admission.bindChild(firstEpoch, child);
            assertThrows(IllegalStateException.class,
                    () -> admission.bindChild(firstEpoch, child));
            assertThrows(IllegalArgumentException.class,
                    () -> admission.bindSchedulerChild(
                            secondEpoch, child, schedulerChild));
            admission.bindSchedulerChild(
                    firstEpoch, child, schedulerChild);
            assertThrows(IllegalArgumentException.class,
                    () -> admission.bindSchedulerChild(
                            firstEpoch, child, schedulerChild));
            assertEquals(1, verifier.activeChildren());

            admission.retireAfterPhysicalQuiescence(
                    firstEpoch, child, schedulerChild);
        }
        assertEquals(0, verifier.activeChildren());
    }

    @Test
    void cancellationKeepsExactVerifierLoanUntilPhysicalQuiescence()
            throws Exception {
        TaskAccessVerifier verifier = new TaskAccessVerifier();
        TaskLoanAdmission admission = admission(verifier);
        var claim = new TaskAccessVerifier.Claim(
                TaskAccessVerifier.Place.whole(ROOT),
                TaskAccessVerifier.Mode.WRITE);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);

        try (OresScheduler scheduler = new OresScheduler(1);
             AsyncRuntime runtime = new AsyncRuntime();
             OresTaskScope scope = new OresTaskScope(runtime)) {
            OresTask<Integer> child = startCompilerFrameForTest(
                    scope,
                    scheduler,
                    resume -> {
                        entered.countDown();
                        boolean interrupted = false;
                        try {
                            while (!release.await(
                                    50, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                                // Keep the guest carrier physically live across
                                // logical cancellation/result settlement.
                            }
                        } catch (InterruptedException ignored) {
                            interrupted = true;
                            while (release.getCount() != 0) {
                                Thread.onSpinWait();
                            }
                        } finally {
                            if (interrupted) {
                                Thread.currentThread().interrupt();
                            }
                        }
                        return OresScheduler.done(41);
                    },
                    admission);

            assertTrue(entered.await(
                    5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1, verifier.activeChildren());
            assertTrue(admission.scopedReadBorrowForTesting());
            assertTrue(admission.schedulerBoundForTesting(),
                    "read authority must be bound through the exact scheduler child");
            assertFalse(verifier.parentMayAccess(claim),
                    "parent mutation must stay denied while child owns exact loan");

            assertTrue(scope.cancel());
            assertFalse(child.isQuiescent());
            assertFalse(admission.retiredForTesting());
            assertEquals(1, verifier.activeChildren());
            assertFalse(verifier.parentMayAccess(claim),
                    "cancellation/Future settlement must not release borrowed memory");

            release.countDown();
            assertNull(AsyncRuntime.await(child.getDone()));
            assertTrue(child.isQuiescent());
            assertTrue(admission.retiredForTesting());
            assertEquals(0, verifier.activeChildren());
            assertTrue(verifier.parentMayAccess(claim),
                    "parent authority returns only after physical scheduler quiescence");
        } finally {
            release.countDown();
        }
    }

}
