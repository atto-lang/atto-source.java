package dev.oreslang.compiler;

import java.util.List;
import org.junit.jupiter.api.Test;
import static dev.oreslang.compiler.AffineDropPlanV1.*;
import static org.junit.jupiter.api.Assertions.*;

final class AffineDropPlanVerifierV1Test {
    private static final List<Parameter> OWNERS =
            List.of(new Parameter(1, "a", "int"), new Parameter(2, "b", "int"));
    private static final Finish END = new Finish();

    private static Plan plan(boolean returnsOwner, Instruction entry) {
        return new Plan(OWNERS, List.of("flag"), returnsOwner, entry);
    }

    private static void reject(boolean returnsOwner, Instruction instruction, String reason) {
        var error = assertThrows(IllegalArgumentException.class,
                () -> AffineDropPlanVerifierV1.verify(plan(returnsOwner, instruction)));
        assertTrue(error.getMessage().contains(reason), error::getMessage);
    }

    @Test
    void validatesBothPathSpecificDropOrders() {
        var yes = new Transfer(1, 3, new Drop(3, new Drop(2, END)));
        var no = new Drop(2, new Drop(1, END));
        assertDoesNotThrow(() -> AffineDropPlanVerifierV1.verify(
                plan(false, new Branch("flag", yes, no))));
    }

    @Test
    void validatesReturnTransferBeforeRemainingDrops() {
        assertDoesNotThrow(() -> AffineDropPlanVerifierV1.verify(
                plan(true, new Transfer(1, RETURN_SLOT, new Drop(2, END)))));
    }

    @Test
    void rejectsMissingOrOutOfOrderDropsOnEitherEdge() {
        reject(false, new Drop(1, new Drop(2, END)), "out-of-order");
        reject(false, new Branch("flag", new Drop(2, new Drop(1, END)),
                new Drop(2, END)), "live owner at finish");
        reject(false, new Drop(2, new Drop(2, END)), "dead slot");
    }

    @Test
    void rejectsDeadSourceAndReusedDestination() {
        reject(false, new Drop(2, new Transfer(2, 3, END)), "dead slot");
        reject(false, new Transfer(1, 2, END), "slot reused");
        reject(false, new Transfer(1, 3, new Transfer(3, 1, END)), "slot reused");
    }

    @Test
    void rejectsReturnProtocolViolations() {
        reject(false, new Transfer(1, RETURN_SLOT, END), "void plan");
        reject(true, new Transfer(1, RETURN_SLOT, new Transfer(2, 3, END)),
                "after return publication");
        reject(true, new Drop(2, new Drop(1, END)), "missing owned return");
        reject(true, new Transfer(1, RETURN_SLOT, new Branch("flag", END, END)),
                "after return publication");
    }

    @Test
    void rejectsUnknownBranchAndMissingInstruction() {
        reject(false, new Branch("unknown", END, END), "invalid branch");
        reject(false, new Drop(2, null), "missing or unrecognized instruction");
    }

    @Test
    void rejectsDuplicateParameterSlotsAndNames() {
        assertThrows(IllegalArgumentException.class, () -> AffineDropPlanVerifierV1.verify(
                new Plan(List.of(new Parameter(1, "a", "int"),
                        new Parameter(1, "b", "i32")), List.of(), false, END)));
        assertThrows(IllegalArgumentException.class, () -> AffineDropPlanVerifierV1.verify(
                new Plan(OWNERS, List.of("a"), false, END)));
    }

    @Test
    void boundsTraversalEvenForSharedBranchSubtrees() {
        Instruction tree = new Drop(2, new Drop(1, END));
        for (int i = 0; i < 13; i++) tree = new Branch("flag", tree, tree);
        reject(false, tree, "visit budget");
    }
}
