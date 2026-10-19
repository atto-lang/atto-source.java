package dev.oreslang.types;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * TEST-ONLY cross-package seam. Production TaskAccessVerifier stays
 * package-private. The runtime sees only callbacks; it cannot mint or retire
 * compiler loans by ticket.
 */
public final class TaskRegionTestLedger {
    private static final ThreadSafetyTraits.NominalProof CORE =
            new ThreadSafetyTraits.NominalProof() {
                @Override
                public boolean has(
                        Types.Named type,
                        ThreadSafetyTraits.Trait trait) {
                    return false;
                }
            };

    private final TaskAccessVerifier ledger = new TaskAccessVerifier();
    private final TaskAccessVerifier.Place column =
            TaskAccessVerifier.Place.whole(
                    new TaskAccessVerifier.Root(
                            "PROCESS", "process-main", 203, 1, true))
                    .field("position", true);
    private final TaskAccessVerifier.Admission left;
    private final TaskAccessVerifier.Admission right;

    public TaskRegionTestLedger() {
        left = admit(write(0, 128));
        right = admit(write(128, 256));
    }

    private TaskAccessVerifier.Claim write(long from, long to) {
        return new TaskAccessVerifier.Claim(
                column.range(from, to, true), TaskAccessVerifier.Mode.WRITE);
    }

    private TaskAccessVerifier.Admission admit(TaskAccessVerifier.Claim claim) {
        return ledger.admitScopedTyped(
                List.of(new TaskAccessVerifier.TypedClaim(
                        claim, Types.Primitive.INT)),
                CORE,
                place -> Types.Primitive.INT);
    }

    public int activeChildren() {
        return ledger.activeChildren();
    }

    public boolean parentCanWrite() {
        return ledger.parentMayAccess(new TaskAccessVerifier.Claim(
                column, TaskAccessVerifier.Mode.WRITE));
    }

    public void cancelLeft() {
        ledger.requestCancel(left.ticket());
    }

    /** Only the runtime's trusted onPhysicalExit callback calls this seam. */
    public void physicalExitLeft() {
        left.completion().releaseAfterPhysicalQuiescence();
    }

    /** Only the runtime's trusted onPhysicalExit callback calls this seam. */
    public void physicalExitRight() {
        right.completion().releaseAfterPhysicalQuiescence();
    }

    public void verifyOverlapRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> admit(write(127, 129)));
    }

    public void verifyPrematureFinishRejected() {
        assertThrows(IllegalStateException.class, ledger::finishScope);
    }

    public void finishScope() {
        ledger.seal();
        ledger.finishScope();
    }
}
