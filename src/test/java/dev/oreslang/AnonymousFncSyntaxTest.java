package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class AnonymousFncSyntaxTest {
    @Test
    void anonymousFncAndPipeLambdaProduceTheSameAst() {
        Ast.Program program = Parser.parse("""
                pub fnc demo(): void {
                    let a = fnc (chunk) { return chunk; };
                    let b = |chunk| -> { return chunk; };
                    let c = nlex fnc (int value) { return value; };
                    let d = nlex |int value| -> { return value; };
                    let noArgs = fnc () { return 42; };
                    let noArgsPipe = || -> { return 42; };
                    let symbols = [#aos, #soa, #aosoa];
                    return;
                }
                """);

        Ast.FunctionDecl function = assertInstanceOf(Ast.FunctionDecl.class,
                program.modules().getFirst().declarations().getFirst());
        Ast.LambdaExpr a = lambda(function.body().get(0));
        Ast.LambdaExpr b = lambda(function.body().get(1));
        Ast.LambdaExpr c = lambda(function.body().get(2));
        Ast.LambdaExpr d = lambda(function.body().get(3));
        Ast.LambdaExpr noArgs = lambda(function.body().get(4));
        Ast.LambdaExpr noArgsPipe = lambda(function.body().get(5));

        assertEquals(b, a, "anonymous fnc must share the pipe-lambda AST and capture semantics");
        assertEquals(d, c, "nlex must preserve the identical capture barrier");
        assertEquals(noArgsPipe, noArgs, "zero-parameter anonymous fnc must be equivalent");
        assertFalse(a.nonLexical());
        assertTrue(c.nonLexical());

        Ast.BindingStmt symbols = assertInstanceOf(Ast.BindingStmt.class, function.body().get(6));
        Ast.ListExpr list = assertInstanceOf(Ast.ListExpr.class, symbols.initializer());
        assertEquals(3, list.elements().size());
        for (Ast.Expr element : list.elements()) {
            assertInstanceOf(Ast.LiteralExpr.class, element);
        }
    }

    @Test
    void anonymousFncWorksAsAnInlineCallArgument() {
        Ast.Program program = assertDoesNotThrow(() -> Parser.parse("""
                pub fnc demo(): void {
                    consume(fnc(chunk) { return chunk; });
                    consume(|chunk| -> { return chunk; });
                }
                """));
        Ast.FunctionDecl function = (Ast.FunctionDecl) program.modules().getFirst()
                .declarations().getFirst();
        Ast.CallExpr first = assertInstanceOf(Ast.CallExpr.class,
                ((Ast.ExprStmt) function.body().getFirst()).expression());
        Ast.CallExpr second = assertInstanceOf(Ast.CallExpr.class,
                ((Ast.ExprStmt) function.body().get(1)).expression());
        assertEquals(second.arguments().getFirst(), first.arguments().getFirst());
    }

    @Test
    void anonymousFncRequiresABracedBlock() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("pub fnc demo(): void { let cb = fnc(x) -> x; }"));
        assertTrue(error.getMessage().contains("anonymous fnc requires a braced block body"),
                error::getMessage);
    }

    private static Ast.LambdaExpr lambda(Ast.Stmt statement) {
        return assertInstanceOf(Ast.LambdaExpr.class,
                assertInstanceOf(Ast.BindingStmt.class, statement).initializer());
    }
}
