package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Exact unsigned u64 receipt boundary shared with LLVM's uint64_t native ABI.
 * These arithmetic tests are NOT compiled AttoLang guest or Native Image proof.
 */
final class ActorPubSubUnsignedSequenceTest {
    @Test
    void signedStorageCrossesUnsignedU64BoundaryWithoutReusingZero() {
        assertEquals(1L, ActorEventBus.nextTopicSequenceUnsigned(0L));
        assertEquals(Long.MAX_VALUE,
                ActorEventBus.nextTopicSequenceUnsigned(Long.MAX_VALUE - 1L));
        assertEquals(Long.MIN_VALUE,
                ActorEventBus.nextTopicSequenceUnsigned(Long.MAX_VALUE),
                "2^63 is a legal unsigned topic sequence");
        assertEquals(-1L, ActorEventBus.nextTopicSequenceUnsigned(-2L),
                "unsigned 2^64-1 is the final legal sequence");
        assertThrows(ArithmeticException.class,
                () -> ActorEventBus.nextTopicSequenceUnsigned(-1L),
                "unsigned u64 must not roll over and reuse zero");
    }

    @Test
    void guestReceiptSerializesUnsignedValueRatherThanSignedJavaLong() {
        var done = OresFuture.completed(List.<Void>of());
        var firstHigh = new ActorEventBus.PublishReceipt(
                0, 0, 0, 0, done, Long.MIN_VALUE);
        var last = new ActorEventBus.PublishReceipt(
                0, 0, 0, 0, done, -1L);
        assertEquals("9223372036854775808", firstHigh.sequenceUnsignedString());
        assertEquals("18446744073709551615", last.sequenceUnsignedString());
        assertEquals(0L, new ActorEventBus.PublishReceipt(
                0, 0, 0, 0, done).sequence(),
                "zero still means no committed publication in legacy receipts");
    }
}
