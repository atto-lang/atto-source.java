package dev.oreslang.types;

import dev.oreslang.types.ThreadSafetyTraits.NominalProof;
import dev.oreslang.types.ThreadSafetyTraits.Trait;
import dev.oreslang.types.Types.Type;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Dependency-free executable smoke for exact production Types and
 * ThreadSafetyTraits sources. It is not a replacement for Maven/JUnit.
 */
public final class SendSyncIntrinsicSmoke {
    private static int checked;

    private SendSyncIntrinsicSmoke() { }

    private static final NominalProof SPOOF = (type, trait) -> true;
    private static final NominalProof CORE = new NominalProof() {
        @Override
        public boolean has(Types.Named type, Trait trait) {
            return false;
        }

        @Override
        public boolean trustedIntrinsic(Types.Named type) {
            return Set.of("Option", "Result", "Channel",
                    "IteratorResult", "OptionUnwrapError").contains(type.name());
        }
    };
    private static final NominalProof SPOOF_WITH_CORE = new NominalProof() {
        @Override
        public boolean has(Types.Named type, Trait trait) {
            return true;
        }

        @Override
        public boolean trustedIntrinsic(Types.Named type) {
            return CORE.trustedIntrinsic(type);
        }
    };

    private static void expect(boolean condition, String label) {
        checked++;
        if (!condition) throw new AssertionError(label);
    }

    private static boolean supports(Type type, Trait trait, NominalProof proof) {
        return ThreadSafetyTraits.has(type, trait, proof);
    }

    public static void main(String[] args) {
        Type integer = Types.Primitive.INT;
        Type witness = new Types.Dyn(new Types.Named("Worker", List.of()));
        for (Trait trait : Trait.values()) {
            expect(supports(integer, trait, CORE), "scalar " + trait);
            expect(!supports(witness, trait, SPOOF_WITH_CORE), "unbounded dyn " + trait);
            expect(!supports(new Types.Borrow(witness, false), trait, SPOOF_WITH_CORE),
                    "borrowed dyn " + trait);
            expect(!supports(new Types.Named("Option", List.of(witness)), trait,
                    SPOOF_WITH_CORE), "Option<dyn> " + trait);
            expect(!supports(new Types.Named("Result", List.of(integer, witness)),
                    trait, SPOOF_WITH_CORE), "Result<_,dyn> " + trait);
            expect(!supports(new Types.Named("UserWrapper", List.of(witness)),
                    trait, SPOOF_WITH_CORE), "user wrapper dyn " + trait);
            expect(!supports(new Types.Tuple(List.of(integer, witness)), trait,
                    SPOOF_WITH_CORE), "tuple dyn " + trait);
            expect(!supports(new Types.Record(Map.of("value", witness), true),
                    trait, SPOOF_WITH_CORE), "record dyn " + trait);
            expect(!supports(new Types.Union(List.of(integer, witness)), trait,
                    SPOOF_WITH_CORE), "union dyn " + trait);
            expect(!supports(Types.Unknown.INSTANCE, trait, SPOOF_WITH_CORE),
                    "unknown " + trait);
            expect(!supports(new Types.Generic("T"), trait, SPOOF_WITH_CORE),
                    "generic " + trait);
        }

        for (Types.Named alleged : List.of(
                new Types.Named("Option", List.of(integer)),
                new Types.Named("Result", List.of(integer, integer)),
                new Types.Named("Channel", List.of(integer)),
                new Types.Named("IteratorResult", List.of(integer)),
                new Types.Named("OptionUnwrapError", List.of()))) {
            for (Trait trait : Trait.values()) {
                expect(!supports(alleged, trait, SPOOF),
                        "name spoof must not mint a trait: " + alleged);
            }
        }

        for (String unlowered : List.of("Arc", "Weak", "Rc", "RcWeak",
                "Mutex", "RwLock", "MutexGuard", "ReadGuard", "WriteGuard",
                "Shared", "AtomicRef", "ExecutionPermit", "ActorRef",
                "Task", "FfiHandle", "OresPointer", "ActorGroup",
                "ThreadLocal", "RawPointer", "BorrowGuard", "RefCell")) {
            Type forged = new Types.Named(unlowered, List.of(integer));
            for (Trait trait : Trait.values()) {
                expect(!supports(forged, trait, SPOOF_WITH_CORE),
                        "unlowered capability proof " + unlowered + " " + trait);
            }
        }

        Type mutableList = new Types.ListType(integer);
        Type immutableTuple = new Types.Tuple(List.of(integer, Types.Primitive.STRING));
        Type immutableRecord = new Types.Record(Map.of("hp", integer), true);
        Type mutableRecord = new Types.Record(Map.of("hp", integer));
        expect(supports(mutableList, Trait.SEND, CORE), "owned List Send");
        expect(!supports(mutableList, Trait.SYNC, CORE), "List not Sync");
        expect(!supports(new Types.Borrow(mutableList, false),
                Trait.SEND, CORE), "&List not Send");
        expect(supports(mutableRecord, Trait.SEND, CORE), "mutable Record Send");
        expect(!supports(mutableRecord, Trait.SYNC, CORE), "mutable Record not Sync");
        for (Trait trait : Trait.values()) {
            expect(supports(immutableTuple, trait, CORE), "tuple " + trait);

            expect(supports(new Types.Named("Option", List.of(integer)), trait, CORE),
                    "verified Option " + trait);
            expect(supports(new Types.Named("Result", List.of(integer, integer)),
                    trait, CORE), "verified Result " + trait);
        }
        expect(supports(immutableRecord, Trait.SEND, CORE),
                "readonly record remains raw structural Send");
        expect(!supports(immutableRecord, Trait.SYNC, CORE),
                "readonly view alone must never grant Sync");
        expect(!ThreadSafetyTraits.isOwnedSend(immutableRecord, CORE),
                "readonly view cannot enter detached Task without backing proof");
        expect(!ThreadSafetyTraits.isOwnedSend(
                new Types.Named("Option", List.of(immutableRecord)), CORE),
                "nested readonly view cannot hide in detached Option");
        expect(!ThreadSafetyTraits.isOwnedSend(
                new Types.Record(Map.of("view", immutableRecord)), CORE),
                "nested readonly view cannot hide in detached writable record");
        expect(!supports(new Types.Borrow(immutableRecord, false),
                Trait.SEND, CORE), "shared borrow of nonfrozen view cannot be Send");
        expect(supports(new Types.Named("Channel", List.of(integer)),
                Trait.SEND, CORE), "verified Channel Send");
        expect(!supports(new Types.Named("Channel", List.of(integer)),
                Trait.SYNC, CORE), "Channel Sync still unproven");

        Type borrow = new Types.Borrow(integer, false);
        expect(supports(borrow, Trait.SEND, CORE),
                "Rust-style borrowed Send can be valid with lifetime proof");
        expect(!ThreadSafetyTraits.isOwnedSend(borrow, CORE),
                "detached owned Send cannot admit a shared stack borrow");
        expect(!ThreadSafetyTraits.isOwnedSend(
                new Types.Borrow(integer, true), CORE),
                "detached owned Send cannot admit mutable stack borrow");
        for (Type candidate : List.of(
                new Types.ListType(borrow),
                new Types.Tuple(List.of(integer, borrow)),
                new Types.Record(Map.of("loan", borrow), true),
                new Types.Union(List.of(integer, borrow)),
                new Types.Named("Option", List.of(borrow)),
                new Types.Named("Result", List.of(integer, borrow)),
                new Types.Function(List.of(borrow), integer))) {
            expect(!ThreadSafetyTraits.isOwnedSend(candidate, CORE),
                    "nested owned Send forged borrowed lifetime: " + candidate);
        }
        expect(ThreadSafetyTraits.isOwnedSend(mutableList, CORE),
                "owned mutable List remains Send");
        expect(ThreadSafetyTraits.isOwnedSend(integer, CORE),
                "primitive remains owned Send");

        Type depthShared = integer;
        for (int i = 0; i < 255; i++) depthShared = new Types.ListType(depthShared);
        Type shallowPath = depthShared;
        expect(supports(shallowPath, Trait.SEND, CORE),
                "shallow path remains within depth limit");
        expect(!supports(new Types.Tuple(List.of(
                        shallowPath, new Types.ListType(shallowPath))),
                Trait.SEND, CORE),
                "shallow cached proof cannot authorize over-depth reused node");
        expect(supports(shallowPath, Trait.SEND, CORE),
                "depth-cache rejection must not poison fresh query");

        Type nested = integer;
        for (int i = 0; i < 400; i++) nested = new Types.ListType(nested);
        expect(!supports(nested, Trait.SEND, CORE), "deep Send must fail closed");
        expect(!ThreadSafetyTraits.isOwnedSend(nested, CORE),
                "deep owned Send preflight must fail closed");
        expect(!supports(nested, Trait.SYNC, CORE), "deep Sync must fail closed");
        expect(supports(integer, Trait.SEND, CORE), "post-depth valid query");

        Type shared = integer;
        for (int i = 0; i < 200; i++) shared = new Types.ListType(shared);
        java.util.ArrayList<Type> repeated = new java.util.ArrayList<>();
        for (int i = 0; i < 1000; i++) repeated.add(shared);
        expect(supports(new Types.Tuple(repeated), Trait.SEND, CORE),
                "shared DAG should reuse completed proof");

        java.util.ArrayList<Type> wide = new java.util.ArrayList<>();
        for (int i = 0; i < 100_100; i++) wide.add(integer);
        expect(!supports(new Types.Tuple(wide), Trait.SEND, CORE),
                "wide type must exhaust proof-step budget");
        expect(supports(integer, Trait.SEND, CORE),
                "post-budget valid query");
        System.out.println("SendSyncIntrinsicSmoke PASS assertions=" + checked);
    }
}
