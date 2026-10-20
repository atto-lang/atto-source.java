package dev.oreslang.types;

import dev.oreslang.ast.Ast;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * TEST-ONLY cross-package bridge. This uses the PRODUCTION compiler-region
 * admission / lifetime binding, not a second invented verifier test path.
 * Host runtime tests pass physicalExitLeft/Right as trusted scheduler final
 * callbacks. The guest compiler still cannot create executable nb regions.
 */
public final class TaskRegionTestLedger {
    private final TaskRegionPermitBridge permits = new TaskRegionPermitBridge();
    private final TaskAccessVerifier.Place column =
            TaskAccessVerifier.Place.whole(
                    new TaskAccessVerifier.Root("PROCESS", 203, 1, true))
                    .field("position", true);
    private final TaskRegionPermitBridge.Permit left;
    private final TaskRegionPermitBridge.Permit right;
    private final Runnable leftPhysicalExit;
    private final Runnable rightPhysicalExit;
    private final SendSyncTraits source = new SendSyncTraits(
            new Ast.Program(List.of()), Set.of("Array"));
    private final ThreadSafetyTraits.NominalProof resolved =
            (type, trait) -> false;

    public TaskRegionTestLedger() {
        left = permits.admit(List.of(write(0, 128)), source, resolved);
        try {
            right = permits.admit(List.of(write(128, 256)), source, resolved);
        } catch (RuntimeException | Error admissionFailure) {
            // Nothing was ever scheduled: this is pre-binding abandonment.
            left.abortBeforeScheduling();
            throw admissionFailure;
        }
        leftPhysicalExit = left.bindPhysicalExit();
        rightPhysicalExit = right.bindPhysicalExit();
    }

    private TaskAccessVerifier.CoherentTypedClaim write(long from, long to) {
        return new TaskAccessVerifier.CoherentTypedClaim(
                new TaskAccessVerifier.Claim(
                        column.range(from, to, true), TaskAccessVerifier.Mode.WRITE),
                new Ast.TypeRef("Array",
                        List.of(new Ast.TypeRef("int", List.of(), false)), false),
                new Types.ListType(Types.Primitive.INT));
    }

    public int activeChildren() { return permits.activeChildren(); }

    public boolean parentCanWrite() {
        return permits.parentMayAccess(new TaskAccessVerifier.Claim(
                column, TaskAccessVerifier.Mode.WRITE));
    }

    public void cancelLeft() { left.requestCancel(); }

    /** Called only by the scheduler after confirmed physical final exit. */
    public void physicalExitLeft() { leftPhysicalExit.run(); }

    /** Called only by the scheduler after confirmed physical final exit. */
    public void physicalExitRight() { rightPhysicalExit.run(); }

    public void verifyOverlapRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> permits.admit(List.of(write(127, 129)), source, resolved));
    }

    public void verifyPrematureFinishRejected() {
        assertThrows(IllegalStateException.class, permits::finishScope);
    }

    public void finishScope() { permits.finishScope(); }
}
