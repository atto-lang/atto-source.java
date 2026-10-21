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
    void runtimeStreamDoesNotLoseCommittedBuffersWhenClosed() {
        OresStream<Integer> stream = new OresStream<>(1);
        assertTrue(stream.channel().tryWrite(42));
        stream.close();
        assertTrue(stream.isClosed());
        assertEquals(42, stream.channel().tryRead().orElseThrow());
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
    void streamConstructorEnforcesPositiveCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new OresStream<>(0));
        assertThrows(IllegalArgumentException.class, () -> new OresStream<>(-1));
        assertThrows(IllegalArgumentException.class,
                () -> new OresStream<>(OresStream.MAX_CAPACITY + 1));
    }
}
