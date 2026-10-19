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
}
