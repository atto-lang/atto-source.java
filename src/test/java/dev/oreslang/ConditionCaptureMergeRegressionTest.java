package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.ast.ConditionCaptures;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ConditionCaptureMergeRegressionTest {
    @Test void nestedComparisonCapturesAreDiscovered() {
        Ast.Expr condition = new Ast.BinaryExpr("==",
                new Ast.CaptureExpr(new Ast.LiteralExpr(42), "value"),
                new Ast.LiteralExpr(42));
        var names = new LinkedHashSet<String>();
        ConditionCaptures.collectNames(condition, names);
        assertEquals(Set.of("value"), names);
    }

    @Test void captureNamesRemainAvailableOnShortCircuitLeftSide() {
        Ast.Expr condition = new Ast.BinaryExpr("&&",
                new Ast.CaptureExpr(new Ast.LiteralExpr(true), "ready"),
                new Ast.NameExpr("ready"));
        assertDoesNotThrow(() -> ConditionCaptures.validate(condition));
        var names = new LinkedHashSet<String>();
        ConditionCaptures.collectNames(condition, names);
        assertEquals(Set.of("ready"), names);
    }

    @Test void captureInConditionalShortCircuitRightSideIsRejected() {
        Ast.Expr condition = new Ast.BinaryExpr("&&", new Ast.LiteralExpr(true),
                new Ast.CaptureExpr(new Ast.LiteralExpr(1), "late"));
        assertThrows(IllegalArgumentException.class, () -> ConditionCaptures.validate(condition));
    }
}
