package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.types.CheckedCallItem;
import dev.oreslang.types.CheckedCallPlan;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CheckedCallItemTest {
    @Test
    void spreadProjectionsRetainOneRawSourceExpressionIdentity() {
        Ast.Expr source = new Ast.NameExpr("args");

        CheckedCallItem first =
                CheckedCallItem.recordProjection(
                        source, "b", 2, 0);
        CheckedCallItem second =
                CheckedCallItem.recordProjection(
                        source, "a", 2, 1);

        assertSame(source, first.sourceExpression());
        assertSame(source, second.sourceExpression());
        assertTrue(first.projected());
        assertEquals("b", first.inputSlot().label());
        assertEquals(2, first.inputSlot().callItemIndex());
        assertEquals(0, first.inputSlot().projectionIndex());
        assertEquals(1, second.inputSlot().projectionIndex());
    }

    @Test
    void directNamedItemIsNotAProjection() {
        Ast.Expr source = new Ast.LiteralExpr(42L);
        CheckedCallItem item =
                CheckedCallItem.named(source, "value", 0);

        assertSame(source, item.sourceExpression());
        assertEquals(
                CheckedCallItem.ProjectionKind.DIRECT,
                item.projectionKind());
        assertEquals(-1, item.projectionIndex());
        assertEquals("value", item.inputSlot().label());
    }

    @Test
    void sourceIdentityAndCanonicalInputSlotSurviveIndexMatrix() {
        // Validate the complete identity/projection conversion contract
        // across a broad range of raw call-item and projection indices.
        Ast.Expr source = new Ast.NameExpr("one-evaluation");
        for (int rawIndex = 0; rawIndex < 1024; rawIndex++) {
            CheckedCallItem positional =
                    CheckedCallItem.positional(source, rawIndex);
            CheckedCallItem named =
                    CheckedCallItem.named(source, "value", rawIndex);
            CheckedCallItem tuple =
                    CheckedCallItem.tupleProjection(
                            source, rawIndex, rawIndex);
            CheckedCallItem record =
                    CheckedCallItem.recordProjection(
                            source, "field", rawIndex, rawIndex);

            assertSame(source, positional.sourceExpression());
            assertSame(source, named.sourceExpression());
            assertSame(source, tuple.sourceExpression());
            assertSame(source, record.sourceExpression());
            assertEquals(
                    CheckedCallPlan.InputSlot.positional(rawIndex),
                    positional.inputSlot());
            assertEquals(
                    CheckedCallPlan.InputSlot.named("value", rawIndex),
                    named.inputSlot());
            assertEquals(
                    CheckedCallPlan.InputSlot.positionalProjection(
                            rawIndex, rawIndex),
                    tuple.inputSlot());
            assertEquals(
                    CheckedCallPlan.InputSlot.namedProjection(
                            "field", rawIndex, rawIndex),
                    record.inputSlot());
        }
    }

    @Test
    void malformedProjectionKindsFailClosed() {
        Ast.Expr source = new Ast.NameExpr("args");

        assertThrows(
                IllegalArgumentException.class,
                () -> new CheckedCallItem(
                        source,
                        CheckedCallItem.ProjectionKind.TUPLE,
                        "named",
                        0,
                        0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new CheckedCallItem(
                        source,
                        CheckedCallItem.ProjectionKind.RECORD,
                        null,
                        0,
                        0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new CheckedCallItem(
                        source,
                        CheckedCallItem.ProjectionKind.DIRECT,
                        null,
                        0,
                        0));
        assertThrows(
                IllegalArgumentException.class,
                () -> CheckedCallItem.positional(source, -1));
    }
}
