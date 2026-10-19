package dev.oreslang.types;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class TaskAccessVerifierQuiescenceTest {
    private static final ThreadSafetyTraits.NominalProof CORE =
            new ThreadSafetyTraits.NominalProof() {
                @Override
                public boolean has(
                        Types.Named type,
                        ThreadSafetyTraits.Trait trait) {
                    return false;
                }

                @Override
                public boolean trustedIntrinsic(Types.Named type) {
                    return java.util.Set.of(
                            "Option", "Result", "Channel",
                            "IteratorResult", "OptionUnwrapError")
                            .contains(type.name());
                }
            };

    private static final TaskAccessVerifier.Root ROOT =
            new TaskAccessVerifier.Root(
                    "PROCESS", "process-main", 31, 1, true);

    private static TaskAccessVerifier.TypedClaim read(
            TaskAccessVerifier.Place place, Types.Type type) {
        return new TaskAccessVerifier.TypedClaim(
                new TaskAccessVerifier.Claim(
                        place, TaskAccessVerifier.Mode.READ),
                type);
    }

    private static TaskAccessVerifier.TypedClaim write(
            TaskAccessVerifier.Place place, Types.Type type) {
        return new TaskAccessVerifier.TypedClaim(
                new TaskAccessVerifier.Claim(
                        place, TaskAccessVerifier.Mode.WRITE),
                type);
    }

    @Test
    void emptyLoanSetCannotCreateMeaninglessAdmission() {
        var verifier = new TaskAccessVerifier();
        assertThrows(
                IllegalArgumentException.class,
                () -> verifier.admitScopedTyped(List.of(), CORE));
        assertEquals(0, verifier.activeChildren());
    }

    @Test
    void oversizedClaimSetFailsBeforeAdmission() {
        var verifier = new TaskAccessVerifier();
        var item = read(
                TaskAccessVerifier.Place.whole(ROOT),
                Types.Primitive.INT);
        var oversized = java.util.Collections.nCopies(1025, item);
        assertThrows(
                IllegalStateException.class,
                () -> verifier.admitScopedTyped(oversized, CORE));
        assertEquals(0, verifier.activeChildren());
    }

    @Test
    void cancellationDoesNotReleaseScopedLoan() {
        var verifier = new TaskAccessVerifier();
        var place = TaskAccessVerifier.Place.whole(ROOT);
        var admission = verifier.admitScopedTyped(
                List.of(read(place, Types.Primitive.INT)), CORE);

        assertEquals(1, verifier.activeChildren());
        verifier.requestCancel(admission.ticket());
        assertTrue(verifier.cancellationRequested(admission.ticket()));
        assertEquals(1, verifier.activeChildren(),
                "cancellation is intent, never physical completion");
        assertFalse(verifier.parentMayAccess(
                new TaskAccessVerifier.Claim(
                        place, TaskAccessVerifier.Mode.WRITE)));

        verifier.seal();
        assertThrows(IllegalStateException.class, verifier::finishScope);

        admission.completion().releaseAfterPhysicalQuiescence();
        assertEquals(0, verifier.activeChildren());
        verifier.finishScope();
    }

    @Test
    void physicalCompletionCapabilityIsOneShot() {
        var verifier = new TaskAccessVerifier();
        var admission = verifier.admitScopedTyped(
                List.of(write(
                        TaskAccessVerifier.Place.whole(ROOT),
                        Types.Primitive.INT)),
                CORE);

        admission.completion().releaseAfterPhysicalQuiescence();
        assertThrows(
                IllegalStateException.class,
                admission.completion()::releaseAfterPhysicalQuiescence);
    }

    @Test
    void sealedScopeRejectsNewAdmissionButAllowsRetirement() {
        var verifier = new TaskAccessVerifier();
        var first = verifier.admitScopedTyped(
                List.of(read(
                        TaskAccessVerifier.Place.whole(ROOT),
                        Types.Primitive.INT)),
                CORE);

        verifier.seal();
        assertThrows(
                IllegalStateException.class,
                () -> verifier.admitScopedTyped(
                        List.of(read(
                                TaskAccessVerifier.Place.whole(ROOT),
                                Types.Primitive.INT)),
                        CORE));
        first.completion().releaseAfterPhysicalQuiescence();
        verifier.finishScope();
    }

    @Test
    void scopedReadRequiresSyncAndScopedWriteRequiresSend() {
        var verifier = new TaskAccessVerifier();
        var place = TaskAccessVerifier.Place.whole(ROOT);

        assertThrows(
                IllegalArgumentException.class,
                () -> verifier.admitScopedTyped(
                        List.of(read(
                                place,
                                new Types.ListType(
                                        Types.Primitive.INT))),
                        CORE),
                "List is movable but not Sync for a shared scoped loan");

        var write = verifier.admitScopedTyped(
                List.of(write(
                        place,
                        new Types.ListType(
                                Types.Primitive.INT))),
                CORE);
        write.completion().releaseAfterPhysicalQuiescence();
    }

    @Test
    void dynCannotEnterScopedReadOrWriteEvenWithPermissiveNominal() {
        var permissive = new ThreadSafetyTraits.NominalProof() {
            @Override
            public boolean has(
                    Types.Named type,
                    ThreadSafetyTraits.Trait trait) {
                return true;
            }

            @Override
            public boolean trustedIntrinsic(Types.Named type) {
                return true;
            }
        };
        var verifier = new TaskAccessVerifier();
        var place = TaskAccessVerifier.Place.whole(ROOT);
        Types.Type dyn = new Types.Dyn(
                new Types.Named("Worker", List.of()));

        assertThrows(
                IllegalArgumentException.class,
                () -> verifier.admitScopedTyped(
                        List.of(read(place, dyn)), permissive));
        assertThrows(
                IllegalArgumentException.class,
                () -> verifier.admitScopedTyped(
                        List.of(write(place, dyn)), permissive));
        assertEquals(0, verifier.activeChildren());
    }

    @Test
    void unsupportedCapabilityCannotEnterScopedLoanViaPermissiveNominal() {
        var permissive = new ThreadSafetyTraits.NominalProof() {
            @Override
            public boolean has(
                    Types.Named type,
                    ThreadSafetyTraits.Trait trait) {
                return true;
            }
        };
        var verifier = new TaskAccessVerifier();
        var place = TaskAccessVerifier.Place.whole(ROOT);

        assertThrows(
                IllegalArgumentException.class,
                () -> verifier.admitScopedTyped(
                        List.of(write(
                                place,
                                new Types.Named(
                                        "Future",
                                        List.of(Types.Primitive.INT)))),
                        permissive));
    }

    @Test
    void readonlyCapabilityCannotAcquireScopedWriteAuthority() {
        var verifier = new TaskAccessVerifier();
        var readonly = new Types.Record(
                Map.of("value", Types.Primitive.INT), true);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> verifier.admitScopedTyped(
                        List.of(write(
                                TaskAccessVerifier.Place.whole(ROOT),
                                readonly)),
                        CORE));
        assertTrue(
                failure.getMessage().contains(
                        "mutable referent authority"),
                failure::getMessage);
    }

    @Test
    void nestedBorrowReferentNeedsSeparateOutlivesProof() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> read(
                        TaskAccessVerifier.Place.whole(ROOT),
                        new Types.Named(
                                "Option",
                                List.of(new Types.Borrow(
                                        Types.Primitive.INT,
                                        false)))));
        assertTrue(
                failure.getMessage().contains(
                        "outlives proof"),
                failure::getMessage);
    }

    @Test
    void arbitraryTrustDomainCannotMintCompilerProvenance() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new TaskAccessVerifier.Root(
                        "USER_SUPPLIED",
                        "owner",
                        1,
                        1,
                        true));
    }

    @Test
    void differentActorOwnersCannotShareOneLoanLedger() {
        var verifier = new TaskAccessVerifier();
        var actorA = TaskAccessVerifier.Place.whole(
                new TaskAccessVerifier.Root(
                        "ACTOR_PRIVATE",
                        "actor-A:epoch-1",
                        1,
                        1,
                        true));
        var actorB = TaskAccessVerifier.Place.whole(
                new TaskAccessVerifier.Root(
                        "ACTOR_PRIVATE",
                        "actor-B:epoch-1",
                        1,
                        1,
                        true));
        var first = verifier.admitScopedTyped(
                List.of(read(actorA, Types.Primitive.INT)), CORE);

        assertThrows(
                IllegalArgumentException.class,
                () -> verifier.admitScopedTyped(
                        List.of(read(
                                actorB,
                                Types.Primitive.INT)),
                        CORE));
        first.completion().releaseAfterPhysicalQuiescence();
    }

    @Test
    void provenDistinctAllocationsAllowConcurrentScopedWrites() {
        var verifier = new TaskAccessVerifier();
        var firstPlace = TaskAccessVerifier.Place.whole(ROOT);
        var secondPlace = TaskAccessVerifier.Place.whole(
                new TaskAccessVerifier.Root(
                        "PROCESS",
                        "process-main",
                        32,
                        1,
                        true));

        var first = verifier.admitScopedTyped(
                List.of(write(firstPlace, Types.Primitive.INT)), CORE);
        var second = verifier.admitScopedTyped(
                List.of(write(secondPlace, Types.Primitive.INT)), CORE);

        assertEquals(2, verifier.activeChildren());
        first.completion().releaseAfterPhysicalQuiescence();
        second.completion().releaseAfterPhysicalQuiescence();
    }
}
