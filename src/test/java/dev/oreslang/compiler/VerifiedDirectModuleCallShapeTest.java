package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class VerifiedDirectModuleCallShapeTest {
    @Test
    void scalarPositionPasses() {
        Ast.CallExpr call = new Ast.CallExpr(new Ast.NameExpr("f"), List.of(), false, List.of(new Ast.LiteralExpr(4L)));
        assertDoesNotThrow(() -> VerifiedDirectModuleCall.requirePlainPositionalCallShape(call));
    }

    @Test
    void explicitGenericApplicationNeedsSeparateProof() {
        Ast.CallExpr emptyApplication = new Ast.CallExpr(
                new Ast.NameExpr("f"), List.of(), true,
                List.of(new Ast.LiteralExpr(4L)));
        Ast.CallExpr concreteApplication = new Ast.CallExpr(
                new Ast.NameExpr("f"), List.of(Ast.TypeRef.simple("int")),
                true, List.of(new Ast.LiteralExpr(4L)));
        assertThrows(IllegalArgumentException.class,
                () -> VerifiedDirectModuleCall.requirePlainPositionalCallShape(emptyApplication));
        assertThrows(IllegalArgumentException.class,
                () -> VerifiedDirectModuleCall.requirePlainPositionalCallShape(concreteApplication));
    }

    @Test
    void spreadArgumentsNeedIndependentExpansionProof() {
        Ast.CallExpr oneSpread = new Ast.CallExpr(
                new Ast.NameExpr("f"), List.of(), false,
                List.of(new Ast.SpreadExpr(new Ast.NameExpr("values"))));
        Ast.CallExpr mixed = new Ast.CallExpr(
                new Ast.NameExpr("f"), List.of(), false,
                List.of(new Ast.LiteralExpr(1L),
                        new Ast.SpreadExpr(new Ast.NameExpr("rest"))));
        assertThrows(IllegalArgumentException.class,
                () -> VerifiedDirectModuleCall.requirePlainPositionalCallShape(oneSpread));
        assertThrows(IllegalArgumentException.class,
                () -> VerifiedDirectModuleCall.requirePlainPositionalCallShape(mixed));
    }

}
