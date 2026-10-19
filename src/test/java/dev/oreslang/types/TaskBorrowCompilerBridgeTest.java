package dev.oreslang.types;

import static org.junit.jupiter.api.Assertions.*;

import dev.oreslang.ast.Ast;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Compiler preflight only; source Task execution and join are not enabled. */
final class TaskBorrowCompilerBridgeTest {
    private static final TaskAccessVerifier.Root ROOT =
            new TaskAccessVerifier.Root("PROCESS", 101, 1, true);

    private static TaskAccessVerifier.Place place() {
        return TaskAccessVerifier.Place.whole(ROOT);
    }

    private static TaskAccessVerifier.Claim read(TaskAccessVerifier.Place place) {
        return new TaskAccessVerifier.Claim(place, TaskAccessVerifier.Mode.READ);
    }

    private static TaskAccessVerifier.Claim write(TaskAccessVerifier.Place place) {
        return new TaskAccessVerifier.Claim(place, TaskAccessVerifier.Mode.WRITE);
    }

    @Test void verifiedScalarReadersCoexistButWritersConflict() {
        var ledger = new TaskAccessVerifier();
        var a = TaskBorrowCompilerBridge.admitResolvedScalars(
                ledger, List.of(read(place())), p -> p.root(), p -> Ast.TypeRef.simple("int"));
        var b = TaskBorrowCompilerBridge.admitResolvedScalars(
                ledger, List.of(read(place())), p -> p.root(), p -> Ast.TypeRef.simple("int"));
        assertEquals(2, ledger.activeClaims());
        assertThrows(IllegalArgumentException.class, () ->
                TaskBorrowCompilerBridge.admitResolvedScalars(
                        ledger, List.of(write(place())), p -> p.root(), p -> Ast.TypeRef.simple("int")));
        assertEquals(2, ledger.activeChildren());
        a.completion().releaseAfterPhysicalQuiescence();
        b.completion().releaseAfterPhysicalQuiescence();
        var writer = TaskBorrowCompilerBridge.admitResolvedScalars(
                ledger, List.of(write(place())), p -> p.root(), p -> Ast.TypeRef.simple("int"));
        ledger.requestCancel(writer.ticket());
        assertFalse(ledger.parentMayAccess(read(place())),
                "cancellation never returns the loan to the parent");
        writer.completion().releaseAfterPhysicalQuiescence();
        assertTrue(ledger.parentMayAccess(read(place())));
        ledger.seal();
        ledger.finishScope();
    }

    @Test void separatelyProvenRootsMayBeWrittenInSameScope() {
        var ledger = new TaskAccessVerifier();
        var other = TaskAccessVerifier.Place.whole(
                new TaskAccessVerifier.Root("PROCESS", 102, 1, true));
        var first = TaskBorrowCompilerBridge.admitResolvedScalars(
                ledger, List.of(write(place())), p -> p.root(), p -> Ast.TypeRef.simple("int"));
        var second = TaskBorrowCompilerBridge.admitResolvedScalars(
                ledger, List.of(write(other)), p -> p.root(), p -> Ast.TypeRef.simple("bool"));
        assertEquals(2, ledger.activeChildren());
        first.completion().releaseAfterPhysicalQuiescence();
        second.completion().releaseAfterPhysicalQuiescence();
        ledger.seal();
        ledger.finishScope();
    }

    @Test void borrowedUnresolvedAndOpaqueReferentsFailClosed() {
        var ledger = new TaskAccessVerifier();
        var place = place();
        List<Ast.TypeRef> unsupported = List.of(
                Ast.TypeRef.borrowed(Ast.TypeRef.simple("int"), true),
                Ast.TypeRef.inferred(),
                Ast.TypeRef.simple("OpaqueHostHandle"),
                new Ast.TypeRef("Array", List.of(Ast.TypeRef.simple("int")), false));
        for (Ast.TypeRef type : unsupported) {
            assertThrows(IllegalArgumentException.class, () ->
                    TaskBorrowCompilerBridge.admitResolvedScalars(
                            ledger, List.of(read(place)), p -> p.root(), p -> type));
            assertEquals(0, ledger.activeChildren());
        }
        assertThrows(IllegalArgumentException.class, () ->
                TaskBorrowCompilerBridge.admitResolvedScalars(
                        ledger, List.of(read(place)), p -> p.root(), p -> null));
    }

    @Test void unsupportedFieldRangeAndActorStorageDoNotForgeProofs() {
        var ledger = new TaskAccessVerifier();
        var projected = place().field("position", true);
        var privateActor = TaskAccessVerifier.Place.whole(
                new TaskAccessVerifier.Root(
                        "ACTOR_PRIVATE", "actor-1:epoch-1", 5, 1, true));
        assertThrows(IllegalArgumentException.class, () ->
                TaskBorrowCompilerBridge.admitResolvedScalars(
                        ledger, List.of(write(projected)), p -> p.root(), p -> Ast.TypeRef.simple("int")));
        assertThrows(IllegalArgumentException.class, () ->
                TaskBorrowCompilerBridge.admitResolvedScalars(
                        ledger, List.of(read(privateActor)), p -> p.root(), p -> Ast.TypeRef.simple("int")));
        assertEquals(0, ledger.activeClaims());
    }

    @Test void inconsistentCompilerPlaceLookupIsAtomic() {
        var ledger = new TaskAccessVerifier();
        var resolverCalls = new AtomicInteger();
        assertThrows(IllegalStateException.class, () ->
                TaskBorrowCompilerBridge.admitResolvedScalars(
                        ledger, List.of(read(place()), read(place())), p -> p.root(),
                        p -> Ast.TypeRef.simple(
                                resolverCalls.incrementAndGet() == 1 ? "int" : "bool")));
        assertEquals(0, ledger.activeChildren());
        assertEquals(0, ledger.activeClaims());
    }

    @Test void typeCannotChangeWithoutStructuralGenerationBarrier() {
        var ledger = new TaskAccessVerifier();
        var initial = TaskBorrowCompilerBridge.admitResolvedScalars(
                ledger, List.of(read(place())), p -> p.root(), p -> Ast.TypeRef.simple("int"));
        assertThrows(IllegalStateException.class, () ->
                TaskBorrowCompilerBridge.admitResolvedScalars(
                        ledger, List.of(read(place())), p -> p.root(), p -> Ast.TypeRef.simple("bool")));
        initial.completion().releaseAfterPhysicalQuiescence();
        // Even after a child leaves, the allocation is still generation 1.
        assertThrows(IllegalStateException.class, () ->
                TaskBorrowCompilerBridge.admitResolvedScalars(
                        ledger, List.of(read(place())), p -> p.root(), p -> Ast.TypeRef.simple("bool")));
        ledger.structuralBarrier(ROOT, 2);
        var updated = TaskAccessVerifier.Place.whole(
                new TaskAccessVerifier.Root("PROCESS", 101, 2, true));
        var ticket = TaskBorrowCompilerBridge.admitResolvedScalars(
                ledger, List.of(read(updated)), p -> p.root(), p -> Ast.TypeRef.simple("bool"));
        ticket.completion().releaseAfterPhysicalQuiescence();
        ledger.seal();
        ledger.finishScope();
    }

    @Test void conflictingTypesInOneSubmissionDoNotReserveClaims() {
        var ledger = new TaskAccessVerifier();
        var counter = new AtomicInteger();
        var identicalClaims = List.of(read(place()), read(place()));
        assertThrows(IllegalStateException.class, () ->
                ledger.admitScopedTyped(List.of(
                        new TaskAccessVerifier.TypedClaim(identicalClaims.get(0), Types.Primitive.INT),
                        new TaskAccessVerifier.TypedClaim(identicalClaims.get(1), Types.Primitive.BOOL)),
                        (type, trait) -> false,
                        p -> counter.getAndIncrement() == 0
                                ? Types.Primitive.INT : Types.Primitive.BOOL));
        assertEquals(0, ledger.activeChildren());
        assertEquals(0, ledger.activeClaims());
    }

    @Test void forgedAllocationIdentityCannotEnterScalarCompilerBridge() {
        var ledger = new TaskAccessVerifier();
        var forged = TaskAccessVerifier.Place.whole(
                new TaskAccessVerifier.Root("PROCESS", 210, 1, true));
        var trusted = new TaskAccessVerifier.Root("PROCESS", 211, 1, true);
        var typeLookups = new AtomicInteger();
        assertThrows(IllegalArgumentException.class, () ->
                TaskBorrowCompilerBridge.admitResolvedScalars(
                        ledger, List.of(write(forged)),
                        p -> trusted,
                        p -> {
                            typeLookups.incrementAndGet();
                            return Ast.TypeRef.simple("int");
                        }));
        assertEquals(0, typeLookups.get());
        assertEquals(0, ledger.activeChildren());
        assertEquals(0, ledger.activeClaims());
    }

    @Test void forgedUniquenessOrGenerationIsRejectedBeforeTypeLookup() {
        var ledger = new TaskAccessVerifier();
        var claimed = TaskAccessVerifier.Place.whole(
                new TaskAccessVerifier.Root("PROCESS", 212, 1, true));
        var notProvenUnique =
                new TaskAccessVerifier.Root("PROCESS", 212, 1, false);
        var staleGeneration =
                new TaskAccessVerifier.Root("PROCESS", 212, 2, true);
        for (var trustedRoot : List.of(notProvenUnique, staleGeneration)) {
            assertThrows(IllegalArgumentException.class, () ->
                    TaskBorrowCompilerBridge.admitResolvedScalars(
                            ledger, List.of(read(claimed)),
                            p -> trustedRoot,
                            p -> Ast.TypeRef.simple("int")));
            assertEquals(0, ledger.activeClaims());
        }
        assertThrows(IllegalArgumentException.class, () ->
                TaskBorrowCompilerBridge.admitResolvedScalars(
                        ledger, List.of(read(claimed)), p -> null,
                        p -> Ast.TypeRef.simple("int")));
    }

    @Test void failedAllocationLookupDoesNotReserveEarlierClaims() {
        var ledger = new TaskAccessVerifier();
        var second = TaskAccessVerifier.Place.whole(
                new TaskAccessVerifier.Root("PROCESS", 213, 1, true));
        assertThrows(IllegalArgumentException.class, () ->
                TaskBorrowCompilerBridge.admitResolvedScalars(
                        ledger, List.of(write(place()), write(second)),
                        p -> p.equals(second) ? null : p.root(),
                        p -> Ast.TypeRef.simple("int")));
        assertEquals(0, ledger.activeClaims());
        assertEquals(0, ledger.activeChildren());
    }

    @Test void failedLaterTypeLookupDoesNotCommitAnyEarlierClaim() {
        var ledger = new TaskAccessVerifier();
        var second = TaskAccessVerifier.Place.whole(
                new TaskAccessVerifier.Root("PROCESS", 103, 1, true));
        assertThrows(IllegalArgumentException.class, () ->
                TaskBorrowCompilerBridge.admitResolvedScalars(
                        ledger, List.of(write(place()), write(second)), p -> p.root(),
                        p -> p.equals(second) ? Ast.TypeRef.simple("Unsupported")
                                : Ast.TypeRef.simple("int")));
        assertEquals(0, ledger.activeClaims());
        assertEquals(0, ledger.activeChildren());
    }
}
