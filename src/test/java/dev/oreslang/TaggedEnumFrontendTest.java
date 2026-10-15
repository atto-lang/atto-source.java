package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class TaggedEnumFrontendTest {
    @Test
    void parsesRecursiveGenericExpressionAstWithDistinctConstructors() {
        Ast.Program parsed = Parser.parse("""
                define enum Expr as
                  Literal(int)
                  Add(Box<Expr>, Box<Expr>)
                  Multiply(Box<Expr>, Box<Expr>)
                end
                """);

        Ast.EnumDecl expr = assertInstanceOf(
                Ast.EnumDecl.class, parsed.modules().getFirst().declarations().getFirst());
        assertEquals("Expr", expr.name());
        assertEquals(List.of("Literal", "Add", "Multiply"),
                expr.variants().stream().map(Ast.EnumVariant::name).toList());
        assertEquals("int", expr.variants().getFirst().fields().getFirst().type().name());
        assertEquals("Box", expr.variants().get(1).fields().getFirst().type().name());
        assertEquals("Expr", expr.variants().get(1).fields().getFirst()
                .type().arguments().getFirst().name());
        assertFalse(expr.variants().get(1).recordStyle());
    }

    @Test
    void parsesNullaryNamedAndRecursiveGenericTreeVariants() {
        Ast.Program parsed = Parser.parse("""
                define enum Tree<T> as
                  Empty
                  Node: {
                    value: T,
                    left: Box<Tree<T>>,
                    right: Box<Tree<T>>
                  }
                end
                """);
        Ast.EnumDecl tree = (Ast.EnumDecl) parsed.modules().getFirst().declarations().getFirst();
        assertEquals(List.of("T"), tree.genericParameters());
        assertTrue(tree.variants().getFirst().fields().isEmpty());
        Ast.EnumVariant node = tree.variants().get(1);
        assertTrue(node.recordStyle());
        assertEquals(List.of("value", "left", "right"),
                node.fields().stream().map(Ast.EnumField::name).toList());
        assertEquals("Tree", node.fields().get(1).type().arguments().getFirst().name());
    }

    @Test
    void supportsModuleScopeAndNamedTupleStyle() {
        Ast.Program parsed = Parser.parse("""
                define module domain as
                  define enum Response<T> as {
                    Idle;
                    Ready(value: T);
                    Failed(message: String);
                  }
                end
                """);
        Ast.EnumDecl decl = (Ast.EnumDecl) parsed.modules().getFirst().declarations().getFirst();
        assertEquals("Response", decl.name());
        assertEquals(3, decl.variants().size());
        assertTrue(decl.variants().get(1).recordStyle());
        assertEquals("value", decl.variants().get(1).fields().getFirst().name());
    }

    @Test
    void qualifiedCasePatternsRetainEnumIdentity() {
        Ast.Program parsed = Parser.parse("""
                define enum Expr as
                  Literal(int)
                end

                fnc classify(Expr value): int {
                  match value over
                    on Expr.Literal(n) -> { return n; }
                    on _ -> { return 0; }
                  end
                }
                """);
        Ast.FunctionDecl fn = (Ast.FunctionDecl)
                parsed.modules().getFirst().declarations().get(1);
        Ast.MatchStmt matched = (Ast.MatchStmt) fn.body().getFirst();
        Ast.ConstructorPattern ctor = (Ast.ConstructorPattern)
                matched.arms().getFirst().pattern();
        assertEquals("Expr.Literal", ctor.constructor());
    }

    @Test
    void rejectsDuplicateConstructorsFieldsGenericsAndMixedPayloads() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define enum X as
                  A
                  A(int)
                end
                """));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define enum X as
                  A(a: int, a: String)
                end
                """));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define enum X<T, T> as
                  A(T)
                end
                """));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define enum X as
                  A(a: int, String)
                end
                """));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define enum X as
                end
                """));
    }

    @Test
    void enumTypeOverloadSignaturesParseButDoNotBypassExecutionGate() {
        Ast.Program parsed = Parser.parse("""
                define enum Color as
                  Red
                  Blue
                end
                define enum Direction as
                  North
                  South
                end

                fnc describe(Color input): int { return 1; }
                fnc describe(Direction input): int { return 2; }
                """);
        assertEquals(4, parsed.modules().getFirst().declarations().size());
        Ast.FunctionDecl first = (Ast.FunctionDecl)
                parsed.modules().getFirst().declarations().get(2);
        Ast.FunctionDecl second = (Ast.FunctionDecl)
                parsed.modules().getFirst().declarations().get(3);
        assertEquals("Color", first.parameters().getFirst().type().name());
        assertEquals("Direction", second.parameters().getFirst().type().name());
        // Nominal enum types are the future discriminants, never the variant
        // labels themselves. This source must not silently execute before
        // tagged values and type-aware overload dispatch are implemented.
        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(parsed));
        assertTrue(error.getMessage().contains("not executable")
                        || error.getMessage().contains("overload identity is name + arity"),
                error.getMessage());
    }

    @Test
    void nonExecutableEnumsFailClosedInTypeChecker() {
        Ast.Program parsed = Parser.parse("""
                define enum Expr as
                  Literal(int)
                  Add(Box<Expr>, Box<Expr>)
                end
                """);
        IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(parsed));
        assertTrue(failure.getMessage().contains("not executable"));
        assertTrue(failure.getMessage().contains("runtime lowering"));
    }
}
