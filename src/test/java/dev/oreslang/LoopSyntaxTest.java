package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class LoopSyntaxTest {
    private static Ast.Stmt firstStatement(String body) {
        Ast.Program program = Parser.parse("pub routine main(): void {\n" + body + "\n}");
        Ast.FunctionDecl main = (Ast.FunctionDecl) program.modules().getFirst().declarations().getFirst();
        return main.body().getFirst();
    }

    @Test
    void whileDoDoneLowersToConditionalLoop() {
        Ast.Stmt statement = firstStatement("let x = 0;\nwhile x < 3 do\n  x = x + 1;\ndone");
        // Declaration is the first statement; the while statement follows it.
        Ast.Program parsed = Parser.parse("pub routine main(): void { let x = 0; while x < 3 do x = x + 1; done }");
        Ast.FunctionDecl main = (Ast.FunctionDecl) parsed.modules().getFirst().declarations().getFirst();
        assertInstanceOf(Ast.ForStmt.class, main.body().get(1));
        Ast.ForStmt loop = (Ast.ForStmt) main.body().get(1);
        assertNull(loop.initializer());
        assertNull(loop.update());
        assertNotNull(loop.condition());
        assertEquals(1, loop.body().size());
    }

    @Test
    void whileBracesAndParenthesesHaveSameAst() {
        assertInstanceOf(Ast.ForStmt.class, firstStatement("while true { break; }"));
        assertInstanceOf(Ast.ForStmt.class, firstStatement("while (true) { break; }"));
    }

    @Test
    void infiniteLoopAliasesHaveSameAst() {
        assertInstanceOf(Ast.LoopStmt.class, firstStatement("loop { break; }"));
        assertInstanceOf(Ast.LoopStmt.class, firstStatement("for { break; }"));
    }

    @Test
    void blockIsNotImplicitLoop() {
        assertInstanceOf(Ast.BlockStmt.class, firstStatement("block { const x = 1; }"));
    }

    @Test
    void malformedWhileFailsClosed() {
        assertThrows(IllegalArgumentException.class, () -> firstStatement("while { break; }"));
        assertThrows(IllegalArgumentException.class, () -> firstStatement("while true do break;"));
    }
}
