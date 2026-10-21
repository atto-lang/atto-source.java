package dev.oreslang;

import dev.oreslang.runtime.OresStream;
import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.types.ThreadSafetyTraits;
import dev.oreslang.types.Types;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class StreamOwnershipProofTest {
    private static final ThreadSafetyTraits.NominalProof TRUSTED =
            new ThreadSafetyTraits.NominalProof() {
                @Override
                public boolean has(Types.Named nominal, ThreadSafetyTraits.Trait trait) {
                    return false;
                }

                @Override
                public boolean trustedIntrinsic(Types.Named nominal) {
                    return nominal.name().equals("Stream");
                }
            };

    @Test
    void trustedBoundedStreamIsOwnedSendButNeverSync() {
        Types.Named stream = new Types.Named("Stream", List.of(Types.Primitive.INT));
        assertTrue(ThreadSafetyTraits.isOwnedSend(stream, TRUSTED));
        assertFalse(ThreadSafetyTraits.has(stream, ThreadSafetyTraits.Trait.SYNC, TRUSTED));
        assertFalse(ThreadSafetyTraits.isOwnedSend(stream, (type, trait) -> false));
    }

    @Test
    void nestedBorrowFutureAndUnknownStreamPayloadsFailClosed() {
        for (Types.Type payload : List.<Types.Type>of(
                new Types.Borrow(Types.Primitive.INT, false),
                new Types.Named("Future", List.of(Types.Primitive.INT)),
                Types.Unknown.INSTANCE)) {
            Types.Named stream = new Types.Named("Stream", List.of(payload));
            assertFalse(ThreadSafetyTraits.isOwnedSend(stream, TRUSTED),
                    "unsafe payload " + payload);
        }
    }

    @Test
    void nestedTransportHandlesCannotForgeStreamSendProof() {
        ThreadSafetyTraits.NominalProof trustedTransport =
                new ThreadSafetyTraits.NominalProof() {
                    @Override
                    public boolean has(
                            Types.Named type, ThreadSafetyTraits.Trait trait) {
                        return false;
                    }

                    @Override
                    public boolean trustedIntrinsic(Types.Named type) {
                        return java.util.Set.of("Stream", "Channel", "Option", "Result")
                                .contains(type.name());
                    }
                };
        Types.Type scalar = Types.Primitive.INT;
        Types.Named innerStream = new Types.Named("Stream", List.of(scalar));
        Types.Named innerChannel = new Types.Named("Channel", List.of(scalar));
        Types.Type[] unsafe = {
                innerStream,
                innerChannel,
                new Types.Named("Option", List.of(innerStream)),
                new Types.ListType(innerChannel),
                new Types.Named("Option",
                        List.of(new Types.ListType(innerStream))),
                new Types.Named("Result", List.of(scalar, innerChannel)),
                new Types.Tuple(List.of(scalar, innerStream)),
                new Types.Record(java.util.Map.of("payload", innerChannel)),
                new Types.Union(List.of(scalar, innerStream))
        };
        for (Types.Type payload : unsafe) {
            Types.Named outer = new Types.Named("Stream", List.of(payload));
            assertFalse(ThreadSafetyTraits.isOwnedSend(outer, trustedTransport),
                    "nested transport escaped Stream data-only policy: " + payload);
        }
        Types.Named dataOnly = new Types.Named("Stream", List.of(
                new Types.Named("Option", List.of(scalar))));
        assertTrue(ThreadSafetyTraits.isOwnedSend(dataOnly, trustedTransport));
        Types.Named nestedDataOnly = new Types.Named("Stream", List.of(
                new Types.Named("Result", List.of(
                        new Types.Record(java.util.Map.of("value", scalar)),
                        new Types.Tuple(List.of(scalar, scalar))))));
        assertTrue(ThreadSafetyTraits.isOwnedSend(nestedDataOnly, trustedTransport),
                "ordinary nested values should retain a Send proof");
    }

    @Test
    void excessivelyDeepStreamPayloadTypesFailClosed() {
        ThreadSafetyTraits.NominalProof intrinsicOnly =
                new ThreadSafetyTraits.NominalProof() {
                    @Override
                    public boolean has(Types.Named type, ThreadSafetyTraits.Trait trait) {
                        return false;
                    }

                    @Override
                    public boolean trustedIntrinsic(Types.Named type) {
                        return java.util.Set.of("Stream", "Option").contains(type.name());
                    }
                };
        Types.Type nested = Types.Primitive.INT;
        for (int depth = 0; depth < 270; depth++) {
            nested = new Types.Named("Option", List.of(nested));
        }
        assertFalse(ThreadSafetyTraits.isOwnedSend(
                new Types.Named("Stream", List.of(nested)), intrinsicOnly),
                "adversarial nesting must not bypass compiler proof budgets");
    }

    @Test
    void runtimeStreamDoesNotLoseCommittedBuffersWhenClosed() {
        OresStream<Integer> stream = new OresStream<>(1);
        assertTrue(stream.channel().tryWrite(42));
        stream.close();
        assertTrue(stream.isClosed());
        assertEquals(42, stream.channel().tryRead().orElseThrow().intValue());
    }

    @Test
    void streamCannotBeFrozenAsActorMessageOrNestedSharedData() {
        OresStream<Integer> handle = new OresStream<>(1);
        try {
            assertThrows(IllegalArgumentException.class,
                    () -> ActorRuntime.freeze(handle));
            assertThrows(IllegalArgumentException.class,
                    () -> ActorRuntime.freeze(List.of(handle)));
            assertThrows(IllegalArgumentException.class,
                    () -> ActorRuntime.freeze(List.of(List.of(handle))));
        } finally {
            handle.close();
        }
    }

    @Test
    void guestStreamConstructorRejectsFractionalOverflowAndImpostorNumbers() {
        for (Object invalid : List.of(
                1.5d, 1.0d, Float.NaN, Double.POSITIVE_INFINITY,
                new java.math.BigDecimal("1.0"),
                java.math.BigInteger.valueOf(Integer.MAX_VALUE).add(java.math.BigInteger.ONE),
                "1")) {
            assertThrows(IllegalArgumentException.class,
                    () -> OresStream.fromGuestCapacity(invalid),
                    "invalid capacity " + invalid);
        }
        OresStream<Integer> accepted = OresStream.fromGuestCapacity(2L);
        try {
            assertTrue(accepted.channel().tryWrite(42));
            assertEquals(42, accepted.channel().tryRead().orElseThrow().intValue());
        } finally {
            accepted.close();
        }
    }

    @Test
    void streamConstructorEnforcesPositiveCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new OresStream<>(0));
        assertThrows(IllegalArgumentException.class, () -> new OresStream<>(-1));
        assertThrows(IllegalArgumentException.class,
                () -> new OresStream<>(OresStream.MAX_CAPACITY + 1));
    }
}
