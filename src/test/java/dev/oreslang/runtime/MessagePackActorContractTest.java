package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.List;
import static dev.oreslang.runtime.MessagePackCollectionCodec.*;
import static org.junit.jupiter.api.Assertions.*;

final class MessagePackActorContractTest {
    private static MessagePackActorContract contract() {
        return new MessagePackActorContract("demo.ActorWorker.v1",
                new MessagePackActorContract.Alternative(11, string()),
                new MessagePackActorContract.Alternative(12, tuple(string(), int64())),
                new MessagePackActorContract.Alternative(13, list(int64())));
    }

    @Test
    void exactVariantAndPortableCollectionRoundTrips() {
        MessagePackActorContract domain = contract();
        assertEquals(3, domain.variantCount());
        assertEquals(new MessagePackActorContract.TaggedMessage(11, "hello"),
                domain.decode(domain.encode(11, "hello")));
        assertEquals(new MessagePackActorContract.TaggedMessage(12, List.of("hello", 5L)),
                domain.decode(domain.encode(12, List.of("hello", 5))));
        assertEquals(new MessagePackActorContract.TaggedMessage(13, List.of(1L, 2L)),
                domain.decode(domain.encode(13, List.of(1, 2))));

        // The recipient's closed contract, not an attacker-supplied numeric
        // tag or structurally similar list, chooses the decoder.
        MessagePackActorContract foreign = new MessagePackActorContract("demo.OtherWorker.v1",
                new MessagePackActorContract.Alternative(11, string()));
        byte[] valid = domain.encode(11, "hello");
        assertThrows(IllegalArgumentException.class, () -> foreign.decode(valid));
        assertThrows(IllegalArgumentException.class, () -> domain.encode(99, "hello"));
        assertThrows(IllegalArgumentException.class, () -> domain.encode(12, "hello"));
        assertThrows(IllegalArgumentException.class, () -> domain.encode(13, List.of("wrong")));
    }

    @Test
    void overlappingPayloadShapesRequireDistinctExplicitVariants() {
        MessagePackActorContract domain = new MessagePackActorContract("demo.Overlapping.v1",
                new MessagePackActorContract.Alternative(1, string()),
                new MessagePackActorContract.Alternative(2, string()));
        assertEquals(1, domain.decode(domain.encode(1, "same")).variantId());
        assertEquals(2, domain.decode(domain.encode(2, "same")).variantId());
        assertThrows(IllegalArgumentException.class, () ->
                new MessagePackActorContract("demo.Bad.v1",
                        new MessagePackActorContract.Alternative(1, string()),
                        new MessagePackActorContract.Alternative(1, int64())));
    }

    @Test
    void malformedFramesCannotBypassTypedDecoder() {
        MessagePackActorContract domain = contract();
        byte[] valid = domain.encode(12, List.of("x", 7L));
        byte[] wrongVersion = valid.clone();
        wrongVersion[1] = 1;
        assertThrows(IllegalArgumentException.class, () -> domain.decode(wrongVersion));
        byte[] changedShape = valid.clone();
        int digest = -1;
        for (int i = 0; i < changedShape.length - 34; i++) {
            if ((changedShape[i] & 255) == 0xc4 && (changedShape[i + 1] & 255) == 32) {
                digest = i + 2;
                break;
            }
        }
        assertTrue(digest > 0);
        changedShape[digest] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> domain.decode(changedShape));
        assertThrows(IllegalArgumentException.class,
                () -> domain.decode(Arrays.copyOf(valid, valid.length - 1)));
        byte[] extra = Arrays.copyOf(valid, valid.length + 1);
        assertThrows(IllegalArgumentException.class, () -> domain.decode(extra));
        assertThrows(IllegalArgumentException.class, () -> domain.decode(new byte[0]));
    }
}
