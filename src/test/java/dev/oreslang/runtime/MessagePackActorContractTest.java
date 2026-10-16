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
    @Test
    void enforcesNativeDescriptorLimitAtContractRegistration() {
        MessagePackCollectionCodec.Shape[] huge =
                new MessagePackCollectionCodec.Shape[3000];
        java.util.Arrays.fill(huge, string());
        IllegalArgumentException bad = assertThrows(IllegalArgumentException.class,
                () -> new MessagePackActorContract("demo.Oversized.v1",
                        new MessagePackActorContract.Alternative(1, tuple(huge))));
        assertTrue(bad.getMessage().contains("native limit"), bad.getMessage());
    }

    @Test
    void rejectsInvalidFloatAndRetainsNoAliasToDecodedWireBytes() {
        MessagePackActorContract floatContract = new MessagePackActorContract("demo.Float.v1",
                new MessagePackActorContract.Alternative(1, float64()));
        assertEquals(1.5, floatContract.decode(floatContract.encode(1, 1.5)).value());
        assertThrows(IllegalArgumentException.class,
                () -> floatContract.encode(1, Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> floatContract.encode(1, Double.NEGATIVE_INFINITY));
        byte[] encoded = contract().encode(12, List.of("safe", 5L));
        var decoded = contract().decode(encoded);
        java.util.Arrays.fill(encoded, (byte) 0);
        assertEquals(List.of("safe", 5L), decoded.value());
    }

    @Test
    void nestedEnumDescriptorUsesCanonicalGrammarAndNativeLimit() {
        // NestedEnum implements Shape but not Sequence. A separate actor
        // descriptor walker previously cast it to Sequence, failing even for
        // a valid closed nested message type.
        MessagePackCollectionCodec.NestedEnum nested =
                new MessagePackCollectionCodec.NestedEnum("Inner",
                        List.of(
                                new MessagePackCollectionCodec.EnumCaseShape("Ping", 1, null),
                                new MessagePackCollectionCodec.EnumCaseShape("Payload", 2, string())));
        int actual = MessagePackCollectionCodec.shapeDescriptorByteLength(nested);
        assertTrue(actual > 0 && actual < MessagePackActorContract.MAX_DESCRIPTOR_BYTES);
        assertDoesNotThrow(() -> new MessagePackActorContract("demo.Nested.v1",
                new MessagePackActorContract.Alternative(1, nested)));

        java.util.ArrayList<MessagePackCollectionCodec.EnumCaseShape> cases =
                new java.util.ArrayList<>();
        for (int i = 1; i <= 90; i++) {
            cases.add(new MessagePackCollectionCodec.EnumCaseShape(
                    "Case_" + i + "_" + "X".repeat(70), i, null));
        }
        var huge = new MessagePackCollectionCodec.NestedEnum("Large", cases);
        assertTrue(MessagePackCollectionCodec.shapeDescriptorByteLength(huge)
                > MessagePackActorContract.MAX_DESCRIPTOR_BYTES);
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> new MessagePackActorContract("demo.Large.v1",
                        new MessagePackActorContract.Alternative(1, huge)));
        assertTrue(rejected.getMessage().contains("native limit"), rejected.getMessage());
    }

}
