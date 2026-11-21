package dev.oreslang.types;

import dev.oreslang.ast.Ast;
import dev.oreslang.runtime.AsyncRuntime;
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

    @Test
    void physicalRetirementRequiresExactRuntimeScopeEpochAndChildIdentity()
            throws Exception {
        TaskAccessVerifier verifier = new TaskAccessVerifier();
        TaskLoanAdmission admission = admission(verifier);
        OresTaskScope.CompilerScopeEpoch scopeEpoch = epochForTest();
        OresTaskScope.CompilerScopeEpoch foreignEpoch = epochForTest();

        try (AsyncRuntime runtime = new AsyncRuntime();
             OresTaskScope scope = new OresTaskScope(runtime)) {
            OresTask<Integer> child = scope.submit(() -> 1);
            OresTask<Integer> lookalike = scope.submit(() -> 2);
            assertNull(AsyncRuntime.await(child.getDone()));
            assertNull(AsyncRuntime.await(lookalike.getDone()));

            admission.bindScope(scopeEpoch);
            admission.bindChild(scopeEpoch, child);

            assertThrows(IllegalArgumentException.class,
                    () -> admission.retireAfterPhysicalQuiescence(
                            foreignEpoch, child));
            assertThrows(IllegalArgumentException.class,
                    () -> admission.retireAfterPhysicalQuiescence(
                            scopeEpoch, lookalike));
            assertEquals(1, verifier.activeChildren(),
                    "identity mismatch must not release the compiler loan");

            admission.retireAfterPhysicalQuiescence(scopeEpoch, child);
            assertEquals(0, verifier.activeChildren());
            assertTrue(admission.retiredForTesting());
            assertThrows(IllegalStateException.class,
                    () -> admission.retireAfterPhysicalQuiescence(
                            scopeEpoch, child),
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
    void childBoundNeverStartedRetirementRequiresExactRuntimeChild()
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
                    () -> admission.retireBeforeChildBound(scopeEpoch),
                    "scope-only retirement must be invalid after child binding");
            assertThrows(IllegalArgumentException.class,
                    () -> admission.retireNeverStarted(
                            scopeEpoch, lookalike),
                    "lookalike child must not retire a never-started loan");
            assertEquals(1, verifier.activeChildren());

            admission.retireNeverStarted(scopeEpoch, exact);
            assertEquals(0, verifier.activeChildren());
            assertTrue(admission.retiredForTesting());
            assertThrows(IllegalStateException.class,
                    () -> admission.retireNeverStarted(scopeEpoch, exact));
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

            admission.bindScope(firstEpoch);
            assertThrows(IllegalArgumentException.class,
                    () -> admission.bindChild(secondEpoch, child));
            admission.bindChild(firstEpoch, child);
            assertThrows(IllegalStateException.class,
                    () -> admission.bindChild(firstEpoch, child));
            assertEquals(1, verifier.activeChildren());

            admission.retireAfterPhysicalQuiescence(firstEpoch, child);
        }
        assertEquals(0, verifier.activeChildren());
    }
}
