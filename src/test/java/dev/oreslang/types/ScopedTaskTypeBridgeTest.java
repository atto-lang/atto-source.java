package dev.oreslang.types;

import dev.oreslang.ast.Ast;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Static compiler admission tests, NOT proof of runnable guest Task syntax. */
final class ScopedTaskTypeBridgeTest {
    private static Ast.TypeRef src(String name, Ast.TypeRef... args) {
        return new Ast.TypeRef(name, List.of(args), false);
    }
    private static final TaskAccessVerifier.Root PROCESS =
            new TaskAccessVerifier.Root("PROCESS", 83, 1, true);
    private static final TaskAccessVerifier.Place POSITIONS =
            TaskAccessVerifier.Place.whole(PROCESS).field("positions", true);

    private static TaskAccessVerifier.CoherentTypedClaim claim(
            TaskAccessVerifier.Mode access, long start, long end,
            Ast.TypeRef surface, Types.Type resolved) {
        return new TaskAccessVerifier.CoherentTypedClaim(
                new TaskAccessVerifier.Claim(POSITIONS.range(start, end, true), access),
                surface, resolved);
    }
    private static final SendSyncTraits SOURCE = new SendSyncTraits(
            new Ast.Program(List.of()), Set.of("Array", "List", "Option", "Result"));
    private static final ThreadSafetyTraits.NominalProof NO_GUEST_UNSAFE =
            (type, trait) -> false;

    private static TaskAccessVerifier.Admission admitCoherent(
            TaskAccessVerifier verifier,
            List<TaskAccessVerifier.CoherentTypedClaim> claims,
            SendSyncTraits sourceProof,
            ThreadSafetyTraits.NominalProof nominalProof) {
        java.util.Map<TaskAccessVerifier.Place, Types.Type> resolved =
                new java.util.HashMap<>();
        for (TaskAccessVerifier.CoherentTypedClaim claim : claims) {
            resolved.putIfAbsent(
                    claim.claim().place(), claim.resolvedType());
        }
        return verifier.admitCoherentTyped(
                claims, sourceProof, nominalProof, resolved::get);
    }

    @Test
    void twoDisjointArrayWriterTasksCanBeAdmittedAndRejoined() {
        var ledger = new TaskAccessVerifier();
        var array = src("Array", src("int"));
        var resolved = new Types.ListType(Types.Primitive.INT);
        var left = admitCoherent(ledger, List.of(claim(
                TaskAccessVerifier.Mode.WRITE, 0, 4096, array, resolved)),
                SOURCE, NO_GUEST_UNSAFE);
        var right = admitCoherent(ledger, List.of(claim(
                TaskAccessVerifier.Mode.WRITE, 4096, 8192, array, resolved)),
                SOURCE, NO_GUEST_UNSAFE);
        assertEquals(2, ledger.activeChildren());
        assertFalse(ledger.parentMayAccess(new TaskAccessVerifier.Claim(
                POSITIONS.range(0, 8192, true), TaskAccessVerifier.Mode.WRITE)));
        assertThrows(IllegalArgumentException.class, () ->
                admitCoherent(ledger, List.of(claim(
                        TaskAccessVerifier.Mode.WRITE, 4095, 4097, array, resolved)),
                        SOURCE, NO_GUEST_UNSAFE));
        ledger.requestCancel(left.ticket());
        assertFalse(ledger.parentMayAccess(new TaskAccessVerifier.Claim(
                POSITIONS.range(0, 4096, true), TaskAccessVerifier.Mode.READ)));
        assertThrows(IllegalStateException.class, ledger::finishScope);
        // Runtime must call quiescent only AFTER actual physical Task exit.
        left.completion().releaseAfterPhysicalQuiescence();
        right.completion().releaseAfterPhysicalQuiescence();
        ledger.seal();
        ledger.finishScope();
    }

    @Test
    void claimedSafeTypeCannotOverrideTrustedPlaceType() {
        var ledger = new TaskAccessVerifier();
        var entry = claim(
                TaskAccessVerifier.Mode.WRITE, 0, 16,
                src("int"), Types.Primitive.INT);
        assertThrows(IllegalArgumentException.class, () ->
                ledger.admitCoherentTyped(
                        List.of(entry),
                        SOURCE,
                        NO_GUEST_UNSAFE,
                        place -> new Types.Named(
                                "NativeHandle", List.of())));
        assertEquals(0, ledger.activeChildren());
    }

    @Test
    void sourceAndLoweredTypeMismatchCannotLaunderSend() {
        var ledger = new TaskAccessVerifier();
        assertThrows(IllegalArgumentException.class, () ->
                admitCoherent(ledger, List.of(claim(
                        TaskAccessVerifier.Mode.WRITE, 0, 32, src("int"),
                        Types.Primitive.STRING)), SOURCE, NO_GUEST_UNSAFE));
        assertEquals(0, ledger.activeChildren());
        assertThrows(IllegalArgumentException.class, () ->
                admitCoherent(ledger, List.of(claim(
                        TaskAccessVerifier.Mode.WRITE, 0, 32, src("Array", src("int")),
                        new Types.ListType(Types.Primitive.STRING))),
                        SOURCE, NO_GUEST_UNSAFE));
        assertEquals(0, ledger.activeChildren());
    }

    @Test
    void readonlyClaimsRequireSyncInBothTypeRepresentations() {
        var ledger = new TaskAccessVerifier();
        var source = src("Array", src("int"));
        var lowered = new Types.ListType(Types.Primitive.INT);
        assertThrows(IllegalArgumentException.class, () ->
                admitCoherent(ledger, List.of(claim(
                        TaskAccessVerifier.Mode.READ, 0, 16, source, lowered)),
                        SOURCE, NO_GUEST_UNSAFE));
        assertEquals(0, ledger.activeChildren());
        // A primitive can be Sync when both representations prove it.
        assertNotNull(admitCoherent(ledger, List.of(claim(
                TaskAccessVerifier.Mode.READ, 0, 16, src("int"),
                Types.Primitive.INT)), SOURCE, NO_GUEST_UNSAFE));
    }

    @Test
    void sameNamedImportedOptionAndResultCannotSpoofIntrinsics() {
        var ledger = new TaskAccessVerifier();
        for (String name : List.of("Option", "Result")) {
            Ast.TypeRef source = name.equals("Option")
                    ? src(name, src("int"))
                    : src(name, src("int"), src("int"));
            Types.Type internal = name.equals("Option")
                    ? new Types.Named(name, List.of(Types.Primitive.INT))
                    : new Types.Named(name, List.of(
                            Types.Primitive.INT, Types.Primitive.INT));
            // Both independent trait engines might otherwise accept matching
            // type *names*. Without resolved intrinsic identity the bridge
            // must deny, even when both element types are Send+Sync.
            assertThrows(IllegalArgumentException.class,
                    () -> admitCoherent(ledger, List.of(claim(
                            TaskAccessVerifier.Mode.READ, 0, 16, source, internal)),
                            SOURCE, NO_GUEST_UNSAFE));
            assertThrows(IllegalArgumentException.class,
                    () -> admitCoherent(ledger, List.of(claim(
                            TaskAccessVerifier.Mode.WRITE, 0, 16, source, internal)),
                            SOURCE, NO_GUEST_UNSAFE));
        }
        assertEquals(0, ledger.activeChildren());
    }

    @Test
    void structurallyMatchingBorrowCannotSkipLifetimeAndQuiescenceProof() {
        var ledger = new TaskAccessVerifier();
        for (boolean mutable : new boolean[]{false, true}) {
            Ast.TypeRef source = Ast.TypeRef.borrowed(src("int"), mutable);
            Types.Type resolved = new Types.Borrow(Types.Primitive.INT, mutable);
            assertThrows(IllegalArgumentException.class,
                    () -> admitCoherent(ledger, List.of(claim(
                            TaskAccessVerifier.Mode.WRITE, 0, 16, source, resolved)),
                            SOURCE, NO_GUEST_UNSAFE));
        }
        assertEquals(0, ledger.activeChildren());
    }

    @Test
    void semanticDynCannotEnterCoherentScopedLoanEvenWithPermissiveNominalProof() {
        var ledger = new TaskAccessVerifier();
        Types.Type dyn = new Types.Dyn(
                new Types.Named("Worker", List.of()));
        // No source dyn syntax exists on this stack. A forged surface type and
        // permissive nominal callback must not bypass the independent dyn gate.
        assertThrows(IllegalArgumentException.class, () ->
                admitCoherent(ledger, List.of(claim(
                        TaskAccessVerifier.Mode.WRITE, 0, 16,
                        src("Worker"), dyn)),
                        SOURCE, new ThreadSafetyTraits.NominalProof() {
                            @Override public boolean has(
                                    Types.Named type,
                                    ThreadSafetyTraits.Trait trait) {
                                return true;
                            }
                            @Override public boolean trustedIntrinsic(
                                    Types.Named type) {
                                return true;
                            }
                        }));
        assertEquals(0, ledger.activeChildren());
    }

    @Test
    void runtimeCapabilitiesAndUnresolvedNominalsFailClosed() {
        var ledger = new TaskAccessVerifier();
        for (String name : List.of("ActorRef", "Task", "Channel", "ThreadBound",
                "ExecutionPermit", "NativeHandle", "RawPointer", "MutexGuard")) {
            assertThrows(IllegalArgumentException.class, () ->
                    admitCoherent(ledger, List.of(claim(
                            TaskAccessVerifier.Mode.WRITE, 0, 16,
                            src(name, src("int")),
                            new Types.Named(name, List.of(Types.Primitive.INT)))),
                            SOURCE, (nominal, kind) -> true), name);
        }
        assertEquals(0, ledger.activeChildren());
    }

    @Test
    void aFailedMultiClaimDoesNotReserveAnyLoan() {
        var ledger = new TaskAccessVerifier();
        assertThrows(IllegalArgumentException.class, () ->
                admitCoherent(ledger, List.of(
                        claim(TaskAccessVerifier.Mode.WRITE, 0, 10,
                                src("int"), Types.Primitive.INT),
                        claim(TaskAccessVerifier.Mode.WRITE, 10, 20,
                                src("int"), Types.Primitive.STRING)),
                        SOURCE, NO_GUEST_UNSAFE));
        assertEquals(0, ledger.activeChildren());
        assertNotNull(admitCoherent(ledger, List.of(claim(
                TaskAccessVerifier.Mode.WRITE, 0, 20, src("int"), Types.Primitive.INT)),
                SOURCE, NO_GUEST_UNSAFE));
    }

    @Test
    void emptyFootprintsCannotPretendUnknownEffectsArePure() {
        var ledger = new TaskAccessVerifier();
        assertThrows(IllegalArgumentException.class, () ->
                admitCoherent(ledger, List.of(), SOURCE, NO_GUEST_UNSAFE));
        assertEquals(0, ledger.activeChildren());
    }

    @Test
    void deeplyNestedTypeBombIsRejectedWithinBoundedProofDepth() {
        Ast.TypeRef deep = src("int");
        Types.Type lowered = Types.Primitive.INT;
        for (int i = 0; i < 96; i++) {
            deep = src("Option", deep);
            lowered = new Types.Named("Option", List.of(lowered));
        }
        var ledger = new TaskAccessVerifier();
        Ast.TypeRef input = deep;
        Types.Type resolved = lowered;
        assertThrows(IllegalArgumentException.class, () ->
                admitCoherent(ledger, List.of(claim(
                        TaskAccessVerifier.Mode.WRITE, 0, 32, input, resolved)),
                        SOURCE, NO_GUEST_UNSAFE));
    }
}
