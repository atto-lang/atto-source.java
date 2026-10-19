package dev.oreslang.types;

import java.util.List;
import java.util.Map;
import java.util.Set;

public final class TaskResolvedSendIntrinsicSmoke {
    private static int checked;
    private TaskResolvedSendIntrinsicSmoke() { }

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
                    return Set.of("Option", "Result", "Channel",
                            "IteratorResult", "OptionUnwrapError")
                            .contains(type.name());
                }
            };

    private static void expect(boolean condition, String label) {
        checked++;
        if (!condition) throw new AssertionError(label);
    }

    public static void main(String[] args) {
        var integer = Types.Primitive.INT;
        expect(ThreadSafetyTraits.isOwnedSend(integer, CORE),
                "primitive owned Send");

        var channel = new Types.Named("Channel", List.of(integer));
        expect(ThreadSafetyTraits.has(
                        channel, ThreadSafetyTraits.Trait.SEND, CORE),
                "trusted Channel Send");
        expect(!ThreadSafetyTraits.has(
                        channel, ThreadSafetyTraits.Trait.SYNC, CORE),
                "Channel Sync denied");

        var borrow = new Types.Borrow(integer, false);
        expect(ThreadSafetyTraits.has(
                        borrow, ThreadSafetyTraits.Trait.SEND, CORE),
                "raw borrow Send");
        expect(!ThreadSafetyTraits.isOwnedSend(borrow, CORE),
                "detached Task rejects borrow");

        var readonly = new Types.Record(
                Map.of("value", integer), true);
        expect(ThreadSafetyTraits.has(
                        readonly, ThreadSafetyTraits.Trait.SEND, CORE),
                "readonly raw Send");
        expect(!ThreadSafetyTraits.isOwnedSend(readonly, CORE),
                "detached Task rejects readonly alias");

        var deep = (Types.Type) integer;
        for (int i = 0; i < 400; i++) {
            deep = new Types.ListType(deep);
        }
        expect(!ThreadSafetyTraits.has(
                        deep, ThreadSafetyTraits.Trait.SEND, CORE),
                "deep proof fails closed");
        expect(ThreadSafetyTraits.isOwnedSend(integer, CORE),
                "later valid proof remains healthy");

        System.out.println(
                "TaskResolvedSendIntrinsicSmoke PASS assertions=" + checked);
    }
}
