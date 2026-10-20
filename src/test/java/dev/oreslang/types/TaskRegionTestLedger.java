package dev.oreslang.types;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * TEST-ONLY cross-package seam. Production TaskAccessVerifier intentionally
 * stays package-private: guest/runtime callers may not mint region claims.
 * This fixture creates exactly two compiler-owned disjoint loan tickets
 * and exposes only narrow observations/physical-release callbacks to the
 * runtime scheduler integration test.
 */
public final class TaskRegionTestLedger {
    private final TaskAccessVerifier ledger = new TaskAccessVerifier();
    private final TaskAccessVerifier.Place column =
            TaskAccessVerifier.Place.whole(
                    new TaskAccessVerifier.Root("PROCESS", 203, 1, true))
                    .field("position", true);
    private final TaskAccessVerifier.Ticket left;
    private final TaskAccessVerifier.Ticket right;

    public TaskRegionTestLedger() {
        left = ledger.admit(List.of(write(0, 128)));
        right = ledger.admit(List.of(write(128, 256)));
    }

    private TaskAccessVerifier.Claim write(long from, long to) {
        return new TaskAccessVerifier.Claim(
                column.range(from, to, true), TaskAccessVerifier.Mode.WRITE);
    }

    public int activeChildren() {
        return ledger.activeChildren();
    }

    public boolean parentCanWrite() {
        return ledger.parentMayAccess(new TaskAccessVerifier.Claim(
                column, TaskAccessVerifier.Mode.WRITE));
    }

    public void cancelLeft() {
        ledger.requestCancel(left);
    }

    /** Only the runtime's trusted onPhysicalExit callback may call this. */
    public void physicalExitLeft() {
        ledger.quiescent(left);
    }

    /** Only the runtime's trusted onPhysicalExit callback may call this. */
    public void physicalExitRight() {
        ledger.quiescent(right);
    }

    public void verifyOverlapRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                ledger.admit(List.of(write(127, 129))));
    }

    public void verifyPrematureFinishRejected() {
        assertThrows(IllegalStateException.class, ledger::finishScope);
    }

    public void finishScope() {
        ledger.finishScope();
    }
}
