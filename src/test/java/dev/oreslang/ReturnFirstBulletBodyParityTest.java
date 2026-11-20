package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Canonical return-first header/body agreement with atto-spec #151 and
 * atto-platform-conformance #242. Parser checks are not guest-execution proofs.
 */
final class ReturnFirstBulletBodyParityTest {
    private static Ast.FunctionDecl firstFunction(String source) {
        Ast.Program program = Parser.parse(source);
        return assertInstanceOf(Ast.FunctionDecl.class,
                program.modules().getFirst().declarations().getFirst());
    }

    @Test
    void arrowAndBulletHaveIdenticalFunctionSignaturesAndBodyAst() {
        String[] headers = {
                "fnc int <- main()", "fnc int • main()",
                "int <- main()", "int• main()"
        };
        String[] bodies = {
                "{ return 42; }",
                "begin return 42; end",
                "return 42; end",
                "begin\n return 42;\nend.",
                "begin\n return 42;\nend;"
        };
        for (String header : headers) {
            for (String body : bodies) {
                String source = header + " " + body;
                Ast.FunctionDecl fnc = firstFunction(source);
                assertEquals("main", fnc.name(), source);
                assertEquals("int", fnc.returnType().name(), source);
                assertEquals(1, fnc.body().size(), source);
                assertInstanceOf(Ast.ReturnStmt.class, fnc.body().getFirst(), source);
            }
        }
    }

    @Test
    void arrowAndBulletMethodBodyVariantsPreserveTypedMethodAst() {
        for (String header : new String[] {
                "int <- calculate(int x)", "int• calculate(int x)",
                "fnc int <- calculate(int x)", "fnc int • calculate(int x)"
        }) {
            for (String body : new String[] {
                    "{ return x * 2; }",
                    "begin return x * 2; end",
                    "return x * 2; end"
            }) {
                String source = "define class C as " + header + " " + body + " end";
                Ast.Program program = Parser.parse(source);
                Ast.ClassDecl klass = assertInstanceOf(Ast.ClassDecl.class,
                        program.modules().getFirst().declarations().getFirst());
                assertEquals(1, klass.methods().size(), source);
                Ast.MethodDecl method = klass.methods().getFirst();
                assertEquals("calculate", method.name(), source);
                assertEquals("int", method.returnType().name(), source);
                assertInstanceOf(Ast.ReturnStmt.class, method.body().getFirst(), source);
            }
        }
    }

    @Test
    void splitArrowLookalikesAndDoubleResultFailClosed() {
        for (String header : new String[] {
                "int < - main()", "int · main()", "int ∙ main()",
                "int • main() -> int", "int <- main(): int"
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse(header + " { return 42; }"), header);
        }
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("int • main() begin return 42;"));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("int • main() { return 42; "));
    }

    @Test
    void plainEndAndExplicitBeginDoNotInventImplicitReturn() {
        for (String header : new String[] { "int <- main()", "int • main()" }) {
            Ast.FunctionDecl explicit = firstFunction(header + " begin 42; end");
            Ast.FunctionDecl bare = firstFunction(header + " 42; end");
            assertFalse(explicit.body().getFirst() instanceof Ast.ReturnStmt);
            assertFalse(bare.body().getFirst() instanceof Ast.ReturnStmt);
        }
    }
}
