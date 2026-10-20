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
                TaskAccessVerifier.Mode.WRITE);
        var typed = new TaskAccessVerifier.CoherentTypedClaim(
                claim, new Ast.TypeRef("int", List.of(), false),
                Types.Primitive.INT);
        TaskAccessVerifier.CoherentAccessProof proof =
                TaskAccessVerifier.proveCoherentTyped(
                        List.of(typed), SOURCE_PROOF, RESOLVED_PROOF);
        return verifier.admitProvenRuntimeBound(proof, PROGRAM);
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
    void failedLedgerRetirementPoisonsAuthorityInsteadOfAllowingRetry()
            throws Exception {
        TaskAccessVerifier verifier = new TaskAccessVerifier();
        TaskLoanAdmission admission = admission(verifier);
        OresTaskScope.CompilerScopeEpoch epoch = epochForTest();
        admission.bindScope(epoch);

        // Fault injection: the checker ledger has already discarded this
        // ticket. Its runtime admission still looks live, as may happen
        // after a bug in physical cleanup sequencing.
        var field = TaskLoanAdmission.class.getDeclaredField("ticket");
        field.setAccessible(true);
        var ticket = (TaskAccessVerifier.Ticket) field.get(admission);
        verifier.retireNeverStarted(ticket);

        assertThrows(IllegalStateException.class,
                () -> admission.retireBeforeChildBound(epoch));
        assertTrue(admission.poisonedForTesting());
        assertFalse(admission.retiredForTesting());
        assertThrows(IllegalStateException.class,
                () -> admission.retireBeforeChildBound(epoch),
                "a failed callback may not mint a second retirement attempt");
    }

}
