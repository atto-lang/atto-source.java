package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The slim arrow separates destructuring syntax from executable arm bodies. */
final class ArrowArmBoundaryTest {
    private static Ast.FunctionDecl parseFunction(String text) {
        Ast.Program program = Parser.parse(text);
        return (Ast.FunctionDecl) program.modules().getFirst().declarations().getFirst();
    }

    @Test
    void doMatchConstructorRecordProjectionUsesRequiredSlimArrow() {
        var fn = parseFunction("""
                fnc handle(int x): void {
                  do match x over
                    on Foo as {v} -> {
                      stdio.println(v);
                    }
                  end
                }
                """);
        var match = (Ast.MatchStmt) fn.body().getFirst();
        assertTrue(match.procedural());
        var arm = match.arms().getFirst();
        assertInstanceOf(Ast.ConstructorPattern.class, arm.pattern());
        var constructor = (Ast.ConstructorPattern) arm.pattern();
        assertEquals("Foo", constructor.constructor());
        assertInstanceOf(Ast.RecordPattern.class, constructor.arguments().getFirst());
        assertEquals(1, arm.body().size());
    }

    @Test
    void doSelectUsesLetRecordProjectionWithArrow() {
        var fn = parseFunction("""
                fnc receive(Channel<{x: int, y: int}> ch): void {
                  do select {
                    when readch ch: let {x,y} -> {
                      stdio.println(x);
                    }
                  }
                }
                """);
        var select = (Ast.SelectStmt) fn.body().getFirst();
        assertTrue(select.explicitDo());
        var arm = select.arms().getFirst();
        assertEquals(Ast.DestructureKind.OBJECT, arm.destructureKind());
        assertEquals(2, arm.destructureBindings().size());
        assertEquals(Ast.BindingKind.LET, arm.destructureBindings().getFirst().kind());
    }

    @Test
    void typedSelectDestructuringRejectsMissingArrow() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc receive(Channel<{x: int}> ch): void {
                  do select {
                    when readch ch: let {x} {
                      stdio.println(x);
                    }
                  }
                }
                """));
    }

    @Test
    void constructorMatchProjectionRejectsMissingArrow() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc handle(int x): void {
                  do match x over
                    on Foo as {v} {
                      stdio.println(v);
                    }
                  end
                }
                """));
    }
    @Test
    void selectAstRejectsBindingPermissionsWithoutAnActualBinding() {
        var channel = new Ast.NameExpr("messages");
        assertThrows(IllegalArgumentException.class, () -> new Ast.SelectArm(
                Ast.ChannelOperation.READ, channel, null,
                Ast.BindingKind.CONST, null, java.util.List.of(), false));
        assertThrows(IllegalArgumentException.class, () -> new Ast.SelectArm(
                Ast.ChannelOperation.READ, channel, null,
                null, null, java.util.List.of(), true));
        assertThrows(IllegalArgumentException.class, () -> new Ast.SelectArm(
                Ast.ChannelOperation.WRITE, channel, new Ast.LiteralExpr(1),
                Ast.BindingKind.VAL, null, java.util.List.of(), false));

        // Legacy no-binding read and write arms still have valid AST shapes.
        assertDoesNotThrow(() -> new Ast.SelectArm(
                Ast.ChannelOperation.READ, channel, null,
                null, null, java.util.List.of(), false));
        assertDoesNotThrow(() -> new Ast.SelectArm(
                Ast.ChannelOperation.WRITE, channel, new Ast.LiteralExpr(1),
                null, null, java.util.List.of(), false));
    }

}
