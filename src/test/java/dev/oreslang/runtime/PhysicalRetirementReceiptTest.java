package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.*;

final class PhysicalRetirementReceiptTest {

    @Test
    void receiptRequiresSealedAdmissionAndEveryPhysicalLeaseToRetire() {
        PhysicalRetirementReceipt gate = new PhysicalRetirementReceipt(41);
        PhysicalRetirementReceipt.Lease first = gate.acquire("source-task");
        PhysicalRetirementReceipt.Lease second = gate.acquire("native-loan");

        assertEquals(PhysicalRetirementReceipt.State.OPEN, gate.state());
        assertEquals(2, gate.activeLeases());
        assertNull(gate.receipt());

        gate.seal();
        assertEquals(PhysicalRetirementReceipt.State.SEALED, gate.state());
        assertNull(gate.receipt());
        assertThrows(
                RejectedExecutionException.class,
                () -> gate.acquire("late-task"));

        first.close();
        assertEquals(1, gate.activeLeases());
        assertNull(gate.receipt());

        second.close();
        PhysicalRetirementReceipt.Receipt receipt = gate.receipt();
        assertNotNull(receipt);
        assertEquals(41, receipt.generation());
        assertEquals(PhysicalRetirementReceipt.State.DRAINED, gate.state());
        assertTrue(gate.validates(receipt));
        assertTrue(gate.hasValidReceipt());

        first.close();
        second.close();
        assertSame(receipt, gate.receipt(), "receipt publication must be once-only");
        assertEquals(0, gate.activeLeases());
    }

    @Test
    void failedRetirementQuarantinesGenerationAndCanNeverMintReceipt() {
        PhysicalRetirementReceipt gate = new PhysicalRetirementReceipt(7);
        PhysicalRetirementReceipt.Lease lease = gate.acquire("source-task");
        RuntimeException failure = new RuntimeException("cleanup failed");

        lease.fail(failure);
        gate.seal();

        assertEquals(PhysicalRetirementReceipt.State.FAILED, gate.state());
        assertSame(failure, gate.failure());
        assertEquals(0, gate.activeLeases());
        assertNull(gate.receipt());
        assertFalse(gate.hasValidReceipt());
        assertThrows(
                RejectedExecutionException.class,
                () -> gate.acquire("new-source-task"));

        lease.close();
        assertNull(gate.receipt(), "a failed lease cannot later become successful");
    }

    @Test
    void receiptIsIssuerAndGenerationBound() {
        PhysicalRetirementReceipt oldGeneration = new PhysicalRetirementReceipt(100);
        PhysicalRetirementReceipt.Lease oldLease =
                oldGeneration.acquire("source-task");
        oldGeneration.seal();
        oldLease.close();
        PhysicalRetirementReceipt.Receipt oldReceipt = oldGeneration.receipt();
        assertNotNull(oldReceipt);

        PhysicalRetirementReceipt newGeneration = new PhysicalRetirementReceipt(101);
        newGeneration.seal();
        PhysicalRetirementReceipt.Receipt newReceipt = newGeneration.receipt();
        assertNotNull(newReceipt);

        assertTrue(oldGeneration.validates(oldReceipt));
        assertTrue(newGeneration.validates(newReceipt));
        assertFalse(oldGeneration.validates(newReceipt));
        assertFalse(newGeneration.validates(oldReceipt));
    }

    @Test
    void zeroLeaseGenerationCanIssueReceiptOnlyAfterSeal() {
        PhysicalRetirementReceipt gate = new PhysicalRetirementReceipt(1);
        assertNull(gate.receipt());
        assertFalse(gate.hasValidReceipt());

        gate.seal();

        assertNotNull(gate.receipt());
        assertTrue(gate.hasValidReceipt());
        assertEquals(PhysicalRetirementReceipt.State.DRAINED, gate.state());
    }

    @Test
    void liveParentMayForkStructuredDescendantAfterSeal() {
        PhysicalRetirementReceipt gate = new PhysicalRetirementReceipt(55);
        PhysicalRetirementReceipt.Lease parent = gate.acquire("native-worker");

        gate.seal();
        assertNull(gate.receipt());
        assertThrows(
                RejectedExecutionException.class,
                () -> gate.acquire("new-root-work"));

        PhysicalRetirementReceipt.Lease child =
                parent.fork("registered-socket");
        assertEquals(2, gate.activeLeases());

        parent.close();
        assertNull(
                gate.receipt(),
                "parent retirement cannot outrun descendant physical ownership");

        child.close();
        assertNotNull(gate.receipt());
        assertTrue(gate.hasValidReceipt());
        assertEquals(PhysicalRetirementReceipt.State.DRAINED, gate.state());

        assertThrows(
                IllegalStateException.class,
                () -> parent.fork("late-child"));
    }


}
