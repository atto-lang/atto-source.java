package dev.oreslang.nodes;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Deterministic failure injection for physical multi-field state publication.
 * These checks do not pretend to be guest compilation or mailbox execution.
 */
final class AttoCriticalCommitQuarantineTest {
    @Test
    void successfulPublicationDoesNotQuarantineActorState() {
        var poisoned = new AtomicBoolean();
        long[] persisted = new long[3];
        CriticalActorTruffleV1.writeFieldsOrQuarantine(
                new long[] { 12, 34, 56 },
                (slot, value) -> {
                    persisted[slot] = value;
                    return true;
                },
                () -> poisoned.set(true));
        assertArrayEquals(new long[] { 12, 34, 56 }, persisted);
        assertFalse(poisoned.get());
    }

    @Test
    void falseWriteResultAfterFirstSlotPoisonsBeforeReturning() {
        var poisoned = new AtomicBoolean();
        var calls = new AtomicInteger();
        long[] persisted = new long[3];
        var failure = assertThrows(IllegalStateException.class,
                () -> CriticalActorTruffleV1.writeFieldsOrQuarantine(
                        new long[] { 1, 2, 3 },
                        (slot, value) -> {
                            calls.incrementAndGet();
                            if (slot == 1) return false;
                            persisted[slot] = value;
                            return true;
                        },
                        () -> poisoned.set(true)));
        assertTrue(failure.getMessage().contains("retired during commit"));
        assertEquals(2, calls.get(), "must never write slot 2 after a failure");
        assertEquals(1, persisted[0], "demonstrates a physically partial write");
        assertEquals(0, persisted[1]);
        assertTrue(poisoned.get(), "partial state must be quarantined");
    }

    @Test
    void thrownWriteFailureAlsoQuarantinesWithoutFurtherWrites() {
        var poisoned = new AtomicBoolean();
        var calls = new AtomicInteger();
        var failure = new IllegalStateException("injected arena invalidation");
        var observed = assertThrows(IllegalStateException.class,
                () -> CriticalActorTruffleV1.writeFieldsOrQuarantine(
                        new long[] { 1, 2, 3 },
                        (slot, value) -> {
                            calls.incrementAndGet();
                            if (slot == 1) throw failure;
                            return true;
                        },
                        () -> poisoned.set(true)));
        assertSame(failure, observed);
        assertEquals(2, calls.get());
        assertTrue(poisoned.get());
    }
}
