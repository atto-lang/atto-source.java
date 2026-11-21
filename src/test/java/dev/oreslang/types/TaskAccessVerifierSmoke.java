package dev.oreslang.types;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Dependency-free compiler-analysis smoke for scoped Task loan retirement. */
public final class TaskAccessVerifierSmoke {
    private static int checked;

    private TaskAccessVerifierSmoke() { }

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
                    return Set.of(
                            "Option", "Result", "Channel",
                            "IteratorResult", "OptionUnwrapError")
                            .contains(type.name());
                }
            };

    private static void expect(boolean condition, String label) {
        checked++;
        if (!condition) throw new AssertionError(label);
    }

    private static void expectFailure(
            Class<? extends Throwable> expected,
            Runnable action,
            String label) {
        checked++;
        try {
            action.run();
        } catch (Throwable failure) {
            if (expected.isInstance(failure)) return;
            throw new AssertionError(
                    label + ": wrong failure " + failure, failure);
        }
        throw new AssertionError(label + ": expected failure");
    }

    private static TaskAccessVerifier.TypedClaim claim(
            TaskAccessVerifier.Place place,
            TaskAccessVerifier.Mode mode,
            Types.Type type) {
        return new TaskAccessVerifier.TypedClaim(
                new TaskAccessVerifier.Claim(place, mode),
                type);
    }

    public static void main(String[] args) {
        var root = new TaskAccessVerifier.Root(
                "PROCESS", "process-main", 31, 1, true);
        var place = TaskAccessVerifier.Place.whole(root);
        var verifier = new TaskAccessVerifier();

        var read = verifier.admitScopedTyped(
                List.of(claim(
                        place,
                        TaskAccessVerifier.Mode.READ,
                        Types.Primitive.INT)),
                CORE);
        expect(verifier.activeChildren() == 1,
                "read loan admitted");
        verifier.requestCancel(read.ticket());
        expect(verifier.activeChildren() == 1,
                "cancel request cannot retire loan");
        expect(!verifier.parentMayAccess(
                        new TaskAccessVerifier.Claim(
                                place,
                                TaskAccessVerifier.Mode.WRITE)),
                "parent write blocked while child read lives");
        verifier.seal();
        expectFailure(
                IllegalStateException.class,
                verifier::finishScope,
                "sealed scope waits for physical completion");
        read.completion().releaseAfterPhysicalQuiescence();
        expect(verifier.activeChildren() == 0,
                "physical completion retires loan");
        expectFailure(
                IllegalStateException.class,
                read.completion()::releaseAfterPhysicalQuiescence,
                "completion capability is one-shot");
        verifier.finishScope();

        expectFailure(
                IllegalArgumentException.class,
                () -> new TaskAccessVerifier.Root(
                        "GUEST", "owner", 1, 1, true),
                "arbitrary trust domain rejected");

        expectFailure(
                IllegalArgumentException.class,
                () -> new TaskAccessVerifier.TypedClaim(
                        new TaskAccessVerifier.Claim(
                                place,
                                TaskAccessVerifier.Mode.READ),
                        new Types.Borrow(
                                Types.Primitive.INT, false)),
                "nested borrowed referent needs outlives proof");

        var readonly = new Types.Record(
                Map.of("value", Types.Primitive.INT), true);
        var verifier2 = new TaskAccessVerifier();
        expectFailure(
                IllegalArgumentException.class,
                () -> verifier2.admitScopedTyped(
                        List.of(claim(
                                place,
                                TaskAccessVerifier.Mode.WRITE,
                                readonly)),
                        CORE),
                "readonly capability cannot mint write loan");

        var list = new Types.ListType(Types.Primitive.INT);
        expectFailure(
                IllegalArgumentException.class,
                () -> verifier2.admitScopedTyped(
                        List.of(claim(
                                place,
                                TaskAccessVerifier.Mode.READ,
                                list)),
                        CORE),
                "List is not Sync for shared scoped loan");
        var listWrite = verifier2.admitScopedTyped(
                List.of(claim(
                        place,
                        TaskAccessVerifier.Mode.WRITE,
                        list)),
                CORE);
        listWrite.completion().releaseAfterPhysicalQuiescence();

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
        Types.Type dyn = new Types.Dyn(
                new Types.Named("Worker", List.of()));
        expectFailure(
                IllegalArgumentException.class,
                () -> verifier2.admitScopedTyped(
                        List.of(claim(
                                place,
                                TaskAccessVerifier.Mode.READ,
                                dyn)),
                        permissive),
                "unbounded dyn cannot acquire scoped READ");
        expectFailure(
                IllegalArgumentException.class,
                () -> verifier2.admitScopedTyped(
                        List.of(claim(
                                place,
                                TaskAccessVerifier.Mode.WRITE,
                                dyn)),
                        permissive),
                "unbounded dyn cannot acquire scoped WRITE");

        System.out.println(
                "TaskAccessVerifierSmoke PASS assertions=" + checked);
    }
}
