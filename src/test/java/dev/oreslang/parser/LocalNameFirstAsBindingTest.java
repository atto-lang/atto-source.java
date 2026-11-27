package dev.oreslang.parser;

import dev.oreslang.ast.Ast;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class LocalNameFirstAsBindingTest {
    private static Ast.BindingStmt binding(String declaration) {
        var program = Parser.parse("define fnc int • main() { " + declaration + " return 42; }");
        var fn = program.modules().stream().flatMap(m -> m.declarations().stream())
                .filter(Ast.FunctionDecl.class::isInstance).map(Ast.FunctionDecl.class::cast)
                .findFirst().orElseThrow();
        return assertInstanceOf(Ast.BindingStmt.class, fn.body().getFirst());
    }

    private static Ast.Stmt firstStatement(String source) {
        var program = Parser.parse("define fnc int • main() { " + source + " return 42; }");
        return program.modules().stream().flatMap(m -> m.declarations().stream())
                .filter(Ast.FunctionDecl.class::isInstance).map(Ast.FunctionDecl.class::cast)
                .findFirst().orElseThrow().body().getFirst();
    }

    @Test void explicitUpdatesReuseAssignmentAstWithoutChangingCallSyntax() {
        assertEquals(firstStatement("x = x + 1;"), firstStatement("set x++;"));
        assertEquals(firstStatement("x = x + 1;"), firstStatement("set ++x;"));
        assertEquals(firstStatement("x = x - 1;"), firstStatement("set --x;"));
        assertEquals(firstStatement("x = x + 2;"), firstStatement("set x += 2;"));
        assertEquals(firstStatement("x = x * 2;"), firstStatement("set x *= 2;"));
        assertEquals(firstStatement("x = x - 2;"), firstStatement("set x -= 2;"));
        assertEquals(firstStatement("x = 42;"), firstStatement("set! (x = 42);"));
        assertInstanceOf(Ast.CallExpr.class,
                assertInstanceOf(Ast.ExprStmt.class, firstStatement("set(42);")).expression());
    }

    @Test void explicitCounterLoopHeadersMatchExistingAssignmentAst() {
        assertEquals(firstStatement("for let i = 0; i < 10; i++ {}"),
                firstStatement("for let i = 0; i < 10; set i++ {}"));
        assertEquals(firstStatement("for (let i = 10; i > 0; i--) {}"),
                firstStatement("for (let i = 10; i > 0; set --i) {}"));
        assertEquals(firstStatement("for (let i = 0; i < 10; i = i + 2) {}"),
                firstStatement("for (let i = 0; i < 10; set i += 2) {}"));
        for (String malformed : new String[]{"set x - = 2;", "set x * /* gap */ = 2;",
                "set ++x++;", "for let i = 0; i < 10; let i++ {}"}) {
            assertThrows(IllegalArgumentException.class, () -> firstStatement(malformed), malformed);
        }
    }

    @Test void nameFirstAsMatchesColonAndPrefixForEveryExplicitQualifier() {
        for (String qualifier : new String[]{"const", "const mut", "val", "let", "let mut"}) {
            var prefix = binding(qualifier + " int x = 42;");
            assertEquals(prefix, binding(qualifier + " x :int = 42;"));
            assertEquals(prefix, binding(qualifier + " x as int = 42;"));
        }
    }

    @Test void constMutAndValShareTheSameLocalBindingAst() {
        for (String declaration : new String[]{"int x = 42;", "x : int = 42;",
                "x as int = 42;", "x = 42;", "List<int> x = [1, 2];"}) {
            var alias = binding("const mut " + declaration);
            assertEquals(binding("val " + declaration), alias);
            assertEquals(Ast.BindingKind.VAL, alias.kind());
            assertTrue(alias.mutableReferent());
        }
        assertEquals(Ast.BindingKind.CONST, binding("const int x = 42;").kind());
        assertThrows(IllegalArgumentException.class, () -> binding("val mut int x = 42;"));
    }

    @Test void postfixCastStaysAnExpressionNotADeclarationAnnotation() {
        var postfix = binding("const x = 42 as int;");
        assertNull(postfix.declaredType());
        var cast = assertInstanceOf(Ast.CastExpr.class, postfix.initializer());
        assertEquals("int", cast.targetType().name());
        assertEquals(Ast.CastMode.CHECKED, cast.mode());
        var prefix = binding("const x as int = 42;");
        assertEquals("int", prefix.declaredType().name());
        assertFalse(prefix.initializer() instanceof Ast.CastExpr);
    }

    @Test void nestedTypeAndMalformedAnnotations() {
        assertEquals(binding("val List<int> x = [1, 2];"),
                binding("val x as List<int> = [1, 2];"));
        assertThrows(IllegalArgumentException.class, () -> binding("const x as = 42;"));
        assertThrows(IllegalArgumentException.class, () -> binding("const x as int : int = 42;"));
        assertThrows(IllegalArgumentException.class, () -> binding("const x as int;"));
    }
}
