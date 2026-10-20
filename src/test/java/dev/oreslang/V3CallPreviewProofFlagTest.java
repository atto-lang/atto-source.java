package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.types.V3CallLoweringPreview;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The staged preview must reject internally inconsistent Option metadata. */
final class V3CallPreviewProofFlagTest {
    private static final Ast.TypeRef INT = Ast.TypeRef.simple("int");
    private static final Ast.TypeRef OPTION_INT =
            new Ast.TypeRef("Option", List.of(INT), false);

    @Test
    void ordinaryIntCannotForgeNominalOptionProofFlag() {
        assertThrows(IllegalArgumentException.class,
                () -> new V3CallLoweringPreview.Slot(
                        0, "x", INT, V3CallLoweringPreview.SlotAction.CHECK_SUPPLIED,
                        null, 0, 0, true));
    }

    @Test
    void optionTypedFormalCannotHideMissingNominalProofFlag() {
        assertThrows(IllegalArgumentException.class,
                () -> new V3CallLoweringPreview.Slot(
                        0, "x", OPTION_INT, V3CallLoweringPreview.SlotAction.CHECK_SUPPLIED,
                        null, 0, 0, false));
    }

    @Test
    void matchingOptionShapeStillRequiresIndependentNominalResolution() {
        var slot = new V3CallLoweringPreview.Slot(
                0, "x", OPTION_INT, V3CallLoweringPreview.SlotAction.CHECK_SUPPLIED,
                null, 0, 0, true);
        assertTrue(slot.requiresNominalOptionProof());
        // A preview is still non-executable even with shape-consistent flags.
        assertEquals("Option", slot.unverifiedBodyType().name());
    }

    @Test
    void ordinaryIntKeepsUnprovedNonOptionClassification() {
        var slot = new V3CallLoweringPreview.Slot(
                0, "x", INT, V3CallLoweringPreview.SlotAction.CHECK_SUPPLIED,
                null, 0, 0, false);
        assertFalse(slot.requiresNominalOptionProof());
    }
}
