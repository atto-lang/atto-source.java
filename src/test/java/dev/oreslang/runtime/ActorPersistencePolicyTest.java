package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import static dev.oreslang.runtime.ActorPersistencePolicy.*;
import static org.junit.jupiter.api.Assertions.*;

final class ActorPersistencePolicyTest {
    @Test
    void exactSymbolModesAndDefaultPolicy() {
        assertEquals(Mode.NONE, parseModeSymbol("#none"));
        assertEquals(Mode.SNAPSHOT, parseModeSymbol("#snapshot"));
        assertEquals(Mode.EVENT_SOURCED, parseModeSymbol("#event_sourced"));
        assertEquals(Mode.BATCHED_SIDECAR, parseModeSymbol("#batched_sidecar"));
        assertEquals(StorageKind.NONE, of(Mode.NONE).storageKind());
        assertFalse(of(Mode.NONE).isDurable());
        assertFalse(of(Mode.NONE).admittedIsDurable());
        assertThrows(IllegalArgumentException.class, () -> parseModeSymbol("snapshot"));
        assertThrows(IllegalArgumentException.class, () -> parseModeSymbol("#Snapshot"));
        assertThrows(IllegalArgumentException.class, () -> parseModeSymbol("#unknown"));
    }

    @Test
    void immutableDurablePoliciesRequireCommittedReceipts() {
        assertEquals(StorageKind.SNAPSHOT, of(Mode.SNAPSHOT).storageKind());
        assertEquals(StorageKind.JOURNAL, of(Mode.EVENT_SOURCED).storageKind());
        assertEquals(Ack.COMMITTED, of(Mode.SNAPSHOT).ack());
        assertEquals(Ack.COMMITTED, of(Mode.EVENT_SOURCED).ack());
        assertTrue(of(Mode.SNAPSHOT).isDurable());
        assertThrows(IllegalArgumentException.class,
                () -> fromSymbols("#snapshot", null, "#admitted"));
        assertThrows(IllegalArgumentException.class,
                () -> fromSymbols("#event_sourced", "#snapshot", null));
    }

    @Test
    void batchedTransportRequiresExplicitDurableBacking() {
        var snapshot = fromSymbols("#batched_sidecar", "#snapshot", "#admitted");
        assertEquals(StorageKind.SNAPSHOT, snapshot.storageKind());
        assertTrue(snapshot.isBatched());
        assertEquals(Ack.ADMITTED, snapshot.ack());
        assertFalse(snapshot.admittedIsDurable());
        var journal = fromSymbols("#batched_sidecar", "#event_sourced", null);
        assertEquals(StorageKind.JOURNAL, journal.storageKind());
        assertEquals(Ack.COMMITTED, journal.ack());
        assertThrows(IllegalArgumentException.class,
                () -> fromSymbols("#batched_sidecar", null, null));
        assertThrows(IllegalArgumentException.class,
                () -> fromSymbols("#batched_sidecar", "#none", null));
        assertThrows(IllegalArgumentException.class,
                () -> fromSymbols("#batched_sidecar", "#batched_sidecar", null));
    }

    @Test
    void noneRejectsProviderAndAckConfigurations() {
        assertThrows(IllegalArgumentException.class,
                () -> fromSymbols("#none", "#snapshot", null));
        assertThrows(IllegalArgumentException.class,
                () -> fromSymbols("#none", null, "#committed"));
        assertEquals(Ack.COMMITTED, parseAckSymbol("#committed"));
        assertEquals(Ack.ADMITTED, parseAckSymbol("#admitted"));
        assertThrows(IllegalArgumentException.class, () -> parseAckSymbol("committed"));
    }
}
