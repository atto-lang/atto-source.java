package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Frontend stage-1 tests for PR #254 syntax.
 *
 * Untyped and effectful Map literals and structural record-field patterns
 * remain fail-closed; literal-only typed maps are a separate reviewed slice.
 * Parser/AST tests are not backend AOT or guest-execution evidence.
 */
final class TildeRecordAndDefaultMatchSyntaxTest {
    private static Ast.FunctionDecl function(String source) {
        return Parser.parse(source).modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(Ast.FunctionDecl.class::isInstance)
                .map(Ast.FunctionDecl.class::cast)
                .findFirst().orElseThrow();
    }

    private static Ast.Expr initializer(String literal) {
        Ast.FunctionDecl f = function("fnc f(): void { const v = " + literal + "; return; }");
        return ((Ast.BindingStmt) f.body().getFirst()).initializer();
    }

    @Test
    void shortAndExplicitTildeRecordsReuseReadonlyRecordType() {
        for (String source : List.of(
                "~(foo: 42, ready: true)",
                "~record(foo: 42, ready: true)",
                "~record(foo: 42, ready: true, )",
                "~(\"first name\": 42)")) {
            Ast.ObjectExpr value = assertInstanceOf(Ast.ObjectExpr.class, initializer(source), source);
            assertEquals(Ast.ObjectKind.RECORD, value.kind(), source);
            assertTrue(value.structuralRecord());
            assertFalse(value.inferredStruct());
        }
        Ast.FunctionDecl typed = function(
                "fnc f(): void { const Map<string,int> value = ~map(); return; }");
        Ast.TypedEmptyAggregateExpr map = assertInstanceOf(Ast.TypedEmptyAggregateExpr.class,
                ((Ast.BindingStmt) typed.body().getFirst()).initializer(),
                "typed binding must resolve contextual ~map() to a compiler-owned Map type");
        assertEquals("Map", map.aggregateType().name());
        assertEquals(2, map.aggregateType().arguments().size());
        assertThrows(IllegalArgumentException.class, () -> initializer("~map()"),
                "untyped map() must fail at the parser boundary");
        Ast.ObjectExpr empty = assertInstanceOf(Ast.ObjectExpr.class, initializer("~record()"));
        assertTrue(empty.fields().isEmpty());
        assertEquals(Ast.ObjectKind.RECORD, empty.kind());
    }

    @Test
    void sourceOrderedRecordSpreadDoesNotBecomeDynamicMap() {
        Ast.FunctionDecl f = function("""
                fnc f(): void {
                    const base = record{foo: 1};
                    const row = ~record(...base, bar: 42);
                    return;
                }
                """);
        Ast.ObjectExpr row = assertInstanceOf(
                Ast.ObjectExpr.class, ((Ast.BindingStmt) f.body().get(1)).initializer());
        assertEquals(Ast.ObjectKind.RECORD, row.kind());
        assertEquals(2, row.fields().size());
        assertTrue(row.fields().getFirst().spread());
        assertFalse(row.fields().get(1).spread());
        assertEquals("bar", row.fields().get(1).name());
    }

    @Test
    void tildeRecordsKeepPostfixMemberAndIndexedAccess() {
        Ast.FunctionDecl f = function("""
                fnc f(): int {
                    const x = ~(foo: 42).foo;
                    const y = ~record(foo: 42)["foo"];
                    return x + y;
                }
                """);
        assertInstanceOf(Ast.MemberExpr.class,
                ((Ast.BindingStmt) f.body().getFirst()).initializer());
        assertInstanceOf(Ast.IndexExpr.class,
                ((Ast.BindingStmt) f.body().get(1)).initializer());
    }

    @Test
    void legacyUnaryTildeRemainsDistinct() {
        Ast.UnaryExpr complement = assertInstanceOf(Ast.UnaryExpr.class, initializer("~(value)"));
        assertEquals("~", complement.operator());
        assertInstanceOf(Ast.NameExpr.class, complement.operand());
    }

    @Test
    void mapLiteralsAndAmbiguousSyntaxFailClosed() {
        for (String source : List.of(
                "~()", "~map(\"ready\" => true)",
                "~map<String,bool>(ready => true)",
                "~(\"ready\" => true)",
                "~(ready => true)",
                "~record(\"ready\" => true)",
                "~(foo: 1, foo: 2)",
                "~record(...)",
                "~record(foo: 1, \"x\" => true)")) {
            assertThrows(IllegalArgumentException.class, () -> initializer(source), source);
        }
    }

    @Test
    void explicitPopulatedTypedMapRetainsTypeAndEntryOrder() {
        Ast.TypedMapLiteralExpr map = assertInstanceOf(Ast.TypedMapLiteralExpr.class,
                initializer("~map<string,int>(\"a\" => 1, \"b\" => 2)"));
        assertEquals("Map", map.aggregateType().name());
        assertEquals(2, map.entries().size());
        assertEquals("a", ((Ast.LiteralExpr) map.entries().getFirst().key()).value());
    }

    @Test
    void matchDefaultIsWildcardOnlyAndMustBeFinal() {
        Ast.FunctionDecl f = function("""
                fnc f(int x): int {
                    return match<int> x over
                        on 1 -> 42;
                        default -> 0;
                    end;
                }
                """);
        Ast.MatchExpr m = assertInstanceOf(Ast.MatchExpr.class,
                ((Ast.ReturnStmt) f.body().getFirst()).value());
        assertEquals(2, m.arms().size());
        assertInstanceOf(Ast.WildcardPattern.class, m.arms().getLast().pattern());

        Ast.FunctionDecl p = function("""
                fnc f(int x): int {
                    match x over
                        on 1 -> { return 42; }
                        default -> { return 0; }
                    end
                }
                """);
        Ast.MatchStmt stmt = assertInstanceOf(Ast.MatchStmt.class, p.body().getFirst());
        assertEquals(2, stmt.arms().size());
        assertInstanceOf(Ast.WildcardPattern.class, stmt.arms().getLast().pattern());
    }

    @Test
    void defaultGuardsDuplicatesAndNonterminalFallbackReject() {
        for (String body : List.of(
                "default when true -> 42;",
                "default -> 42; default -> 0;",
                "default -> 42; on 1 -> 0;",
                "on default -> 42;",
                "else -> 42;")) {
            String source = "fnc f(int x): int { return match<int> x over "
                    + body + " end; }";
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(source), source);
        }
        assertThrows(IllegalArgumentException.class, () ->
                Parser.parse("""
                        fnc f(int x): int {
                            match x over
                                default -> { return 42; }
                                on 1 -> { return 0; }
                            end
                        }
                        """));
    }

    @Test
    void unguardedWildcardCannotPrecedeAnotherArmOrDefault() {
        for (String arms : List.of(
                "on _ -> 7; default -> 8;",
                "on _ -> 7; on 1 -> 8;",
                "on _ -> 7; on _ -> 8;")) {
            String source = "fnc f(int x): int { return match<int> x over "
                    + arms + " end; }";
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class, () -> Parser.parse(source), source);
            assertTrue(failure.getMessage().contains("unguarded wildcard match arm must be last"),
                    failure.getMessage());
        }
        for (String arms : List.of(
                "on _ -> { return 7; } default -> { return 8; }",
                "on _ -> { return 7; } on 1 -> { return 8; }",
                "on _ -> { return 7; } on _ -> { return 8; }")) {
            String source = "fnc f(int x): int { match x over " + arms + " end }";
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(source), source);
        }

        // Guarded wildcard may fail its guard, so a final fallback remains reachable.
        Ast.FunctionDecl guarded = function("""
                fnc f(int x): int {
                    return match<int> x over
                        on _ when false -> 1;
                        default -> 2;
                    end;
                }
                """);
        Ast.MatchExpr expression = assertInstanceOf(Ast.MatchExpr.class,
                ((Ast.ReturnStmt) guarded.body().getFirst()).value());
        assertNotNull(expression.arms().getFirst().guard());
        assertInstanceOf(Ast.WildcardPattern.class, expression.arms().getLast().pattern());
    }

    @Test
    void existingOnWildcardFallbackRemainsAccepted() {
        Ast.FunctionDecl f = function("""
                fnc f(int x): int {
                    return match<int> x over
                        on 1 -> 42;
                        on _ -> 0;
                    end;
                }
                """);
        Ast.MatchExpr m = assertInstanceOf(Ast.MatchExpr.class,
                ((Ast.ReturnStmt) f.body().getFirst()).value());
        assertInstanceOf(Ast.WildcardPattern.class, m.arms().getLast().pattern());
    }
}
