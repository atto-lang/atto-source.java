package dev.oreslang.types;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Source-free observer checks; these do NOT replace real parser/compiler tests.
 */
final class BorrowMirAuditV1CollectorTest {
    @Test
    void duplicateCompilerRootIdentityFailsClosed() {
        var collector = new BorrowMirAuditV1.Collector();
        Object root = new Object();
        collector.beginFunction("dup", true);
        collector.parameter(root, true);
        collector.parameter(root, true);
        collector.returned();
        collector.finishFunction();
        var trace = collector.finishReport().functions().getFirst();
        assertEquals(BorrowMirAuditV1.UNSUPPORTED, trace.status());
        assertTrue(trace.instructions().isEmpty());
    }

    @Test
    void unknownRootCannotProduceACompleteShadowProof() {
        var collector = new BorrowMirAuditV1.Collector();
        collector.beginFunction("unknown", true);
        collector.readCopyParameter(new Object());
        collector.returned();
        collector.finishFunction();
        assertEquals(BorrowMirAuditV1.UNSUPPORTED,
                collector.finishReport().functions().getFirst().status());
    }

    @Test
    void missingReturnInstructionFailsClosed() {
        var collector = new BorrowMirAuditV1.Collector();
        collector.beginFunction("no_return", true);
        collector.parameter(new Object(), true);
        collector.finishFunction();
        assertEquals(BorrowMirAuditV1.UNSUPPORTED,
                collector.finishReport().functions().getFirst().status());
    }

    @Test
    void functionInventoryCannotGrowBeyondBudget() {
        var collector = new BorrowMirAuditV1.Collector();
        for (int i = 0; i < 128; i++) {
            collector.beginFunction("f" + i, true);
            collector.parameter(new Object(), true);
            collector.returned();
            collector.finishFunction();
        }
        assertEquals(128, collector.finishReport().functions().size());
        assertThrows(IllegalArgumentException.class,
                () -> collector.beginFunction("overflow", true));
    }

    @Test
    void shadowEventOrdinalsAreNotCanonicalSourceNodeProofs() {
        var collector = new BorrowMirAuditV1.Collector();
        collector.beginFunction("copy", true);
        Object root = new Object();
        collector.parameter(root, true);
        collector.readCopyParameter(root);
        collector.returned();
        collector.finishFunction();
        var result = collector.finishReport();
        assertNotEquals(BorrowMirAuditV1.CANONICAL_TARGET, result.version());
        assertFalse(result.completeSourceInventory());
        assertFalse(result.verifiedEmittedCode());
        assertFalse(result.functions().getFirst().instructions().getFirst().isSend());
    }
}
