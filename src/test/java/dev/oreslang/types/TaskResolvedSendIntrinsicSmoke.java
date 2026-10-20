package dev.oreslang.types;

import dev.oreslang.types.ThreadSafetyTraits.NominalProof;
import dev.oreslang.types.ThreadSafetyTraits.Trait;
import dev.oreslang.types.Types.Type;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Dependency-free exact-source smoke for the Task resolved Send bridge. */
public final class TaskResolvedSendIntrinsicSmoke {
    private static int checked;

    private TaskResolvedSendIntrinsicSmoke() { }

    private static final NominalProof CORE = new NominalProof() {
        @Override
        public boolean has(Types.Named type, Trait trait) {
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

    private static final NominalProof PERMISSIVE = new NominalProof() {
        @Override
        public boolean has(Types.Named type, Trait trait) {
            return true;
        }

        @Override
        public boolean trustedIntrinsic(Types.Named type) {
            return CORE.trustedIntrinsic(type);
        }
    };

    private static void expect(boolean condition, String message) {
        checked++;
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        Type integer = Types.Primitive.INT;
        Type dyn = new Types.Dyn(
                new Types.Named("Worker", List.of()));
        expect(!Types.isAssignable(Types.Unknown.INSTANCE, dyn),
                "unknown cannot fabricate direct dyn witness");
        expect(!Types.isAssignable(
                        new Types.Named("Option", List.of(Types.Unknown.INSTANCE)),
                        new Types.Named("Option", List.of(dyn))),
                "unknown nested payload cannot fabricate dyn witness");
        expect(Types.isAssignable(dyn, dyn),
                "an already validated identical dyn witness remains assignable");
        expect(ThreadSafetyTraits.has(integer, Trait.SEND, CORE),
                "int Send");
        expect(ThreadSafetyTraits.has(integer, Trait.SYNC, CORE),
                "int Sync");
        expect(ThreadSafetyTraits.isOwnedSend(integer, CORE),
                "int owned Send");

        Type borrow = new Types.Borrow(integer, false);
        expect(ThreadSafetyTraits.has(borrow, Trait.SEND, CORE),
                "shared borrow has structural Send when target is Sync");
        expect(!ThreadSafetyTraits.isOwnedSend(borrow, CORE),
                "detached Task rejects borrowed lifetime");

        Type readonly = new Types.Record(Map.of("value", integer), true);
        expect(ThreadSafetyTraits.has(readonly, Trait.SEND, CORE),
                "readonly record remains structural Send");
        expect(!ThreadSafetyTraits.has(readonly, Trait.SYNC, CORE),
                "readonly view is not a frozen backing proof");
        expect(!ThreadSafetyTraits.isOwnedSend(readonly, CORE),
                "readonly alias cannot enter detached Task");

        Type list = new Types.ListType(integer);
        expect(ThreadSafetyTraits.isOwnedSend(list, CORE),
                "owned List<int> can move");
        expect(!ThreadSafetyTraits.has(list, Trait.SYNC, CORE),
                "mutable List is not Sync");

        expect(!ThreadSafetyTraits.has(
                        new Types.Named("Future", List.of(integer)),
                        Trait.SEND, PERMISSIVE),
                "Future remains denied even under permissive nominal callback");
        expect(!ThreadSafetyTraits.has(
                        new Types.Named("Option", List.of(integer)),
                        Trait.SEND, (type, trait) -> true),
                "source spelling alone cannot mint trusted Option");
        expect(ThreadSafetyTraits.isOwnedSend(
                        new Types.Named("Option", List.of(integer)),
                        CORE),
                "trusted Option<int> moves by value");

        Type nested = integer;
        for (int i = 0; i < 400; i++) {
            nested = new Types.ListType(nested);
        }
        expect(!ThreadSafetyTraits.has(nested, Trait.SEND, CORE),
                "deep type fails closed");
        expect(!ThreadSafetyTraits.isOwnedSend(nested, CORE),
                "deep owned transfer fails closed");

        ArrayList<Type> wide = new ArrayList<>();
        for (int i = 0; i < 100_100; i++) wide.add(integer);
        expect(!ThreadSafetyTraits.has(
                        new Types.Tuple(wide), Trait.SEND, CORE),
                "wide proof exhausts global work budget");
        expect(ThreadSafetyTraits.isOwnedSend(integer, CORE),
                "budget exhaustion does not poison next query");

        System.out.println(
                "TaskResolvedSendIntrinsicSmoke PASS assertions=" + checked);
    }
}
