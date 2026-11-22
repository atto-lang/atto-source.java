package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class WithExpressionParserGateTest {

    private static Ast.Expr expression(String source) {
        return new Parser(new Lexer(source).scan()).parseExpression();
    }

    @Test
    void parsesCopyUpdateOutsideRtUnaryOperand() {
        Ast.WithExpr update = assertInstanceOf(Ast.WithExpr.class,
                expression("rt copy point with {x => point.x + 1, y => 20}"));
        Ast.RuntimeCallExpr copy = assertInstanceOf(Ast.RuntimeCallExpr.class, update.source());
        assertEquals("copy", copy.operation());
        assertEquals("point", assertInstanceOf(Ast.NameExpr.class, copy.arguments().getFirst()).name());
        assertEquals(2, update.changes().size());
        assertEquals("x", update.changes().getFirst().name());
        assertInstanceOf(Ast.BinaryExpr.class, update.changes().getFirst().value());
        assertEquals("y", update.changes().get(1).name());
    }

    @Test
    void parsesMoveAndNestedWithWithoutImplicitReturn() {
        Ast.WithExpr outer = assertInstanceOf(Ast.WithExpr.class,
                expression("(rt take point) with {x => 11} with {y => 30}"));
        assertInstanceOf(Ast.WithExpr.class, outer.source());
        Ast.WithExpr inner = (Ast.WithExpr) outer.source();
        assertEquals("take", assertInstanceOf(Ast.RuntimeCallExpr.class, inner.source()).operation());
    }

    @Test
    void withIsContextualAndNotAReservedIdentifier() {
        Ast.CallExpr call = assertInstanceOf(Ast.CallExpr.class, expression("with(42)"));
        assertEquals("with", assertInstanceOf(Ast.NameExpr.class, call.callee()).name());
        Ast.WithExpr update = assertInstanceOf(Ast.WithExpr.class,
                expression("point with {x => 42,}"));
        assertEquals(1, update.changes().size());
    }

    @Test
    void rejectsDuplicateOrMalformedUpdates() {
        IllegalArgumentException duplicate = assertThrows(IllegalArgumentException.class,
                () -> expression("point with {x => 1, x => 2}"));
        assertTrue(duplicate.getMessage().contains("duplicate with field"), duplicate::getMessage);

        assertThrows(IllegalArgumentException.class,
                () -> expression("point with {}"));
        assertThrows(IllegalArgumentException.class,
                () -> expression("point with {x = 1}"));
        assertThrows(IllegalArgumentException.class,
                () -> expression("point with {x.y => 1}"));
    }

    @Test
    void typecheckerDoesNotAdmitUnloweredWithExpressions() {
        String source = "pub routine main(): int { return 1 with {x => 2}; }";
        Ast.Program program = Parser.parse(source);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(program));
        assertTrue(failure.getMessage().contains("with requires a readonly structural record"),
                failure::getMessage);
    }
}
