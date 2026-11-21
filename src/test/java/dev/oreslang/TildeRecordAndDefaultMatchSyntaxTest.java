package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Migration of old PR #254 tilde construction syntax to canonical Type.of.
 * Structural field patterns remain an independent parser/type-checker feature.
 * Parser/AST tests do not establish backend AOT or guest execution.
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
    void formerShortAndExplicitTildeRecordConstructorsFailClosed() {
        for (String source : List.of(
                "~(foo: 42, ready: true)",
                "~record(foo: 42, ready: true)",
                "~record(foo: 42, ready: true, )",
                "~(\"first name\": 42)",
                "~record()",
                "~record(foo: 42)[\"foo\"]",
                "~record(...base, bar: 42)")) {
            assertThrows(IllegalArgumentException.class, () -> initializer(source), source);
        }
        // Construction has one canonical owner; parsing is not runtime proof.
        assertDoesNotThrow(() -> initializer("Record.of()"));
    }

    @Test
    void formerlyAcceptedTildeRecordSpreadsCannotEvadeSyntaxGate() {
        assertThrows(IllegalArgumentException.class, () -> function("""
                fnc f(): void {
                    const base = record{foo: 1};
                    const row = ~record(...base, bar: 42);
                    return;
                }
                """));
        // Legacy record{...} is an independent literal, not ~record(...).
        Ast.ObjectExpr record = assertInstanceOf(Ast.ObjectExpr.class,
                initializer("record{foo: 42}"));
        assertEquals(Ast.ObjectKind.RECORD, record.kind());
    }

    @Test
    void recordPostfixDoesNotResurrectRemovedTildeConstructors() {
        for (String source : List.of(
                "~(foo: 42).foo",
                "~record(foo: 42)[\"foo\"]",
                "~record().size")) {
            assertThrows(IllegalArgumentException.class, () -> initializer(source), source);
        }
    }

    @Test
    void legacyUnaryTildeRemainsDistinct() {
        Ast.UnaryExpr complement = assertInstanceOf(Ast.UnaryExpr.class, initializer("~(value)"));
        assertEquals("~", complement.operator());
        assertInstanceOf(Ast.NameExpr.class, complement.operand());
    }

    @Test
    void formerTypedAndShorthandMapConstructorsFailClosed() {
        for (String source : List.of(
                "~map<String,bool>(\"ready\" => true)",
                "~map(\"ready\" => true)",
                "~map()",
                "~<Map<string,int>>()",
                "~(\"ready\" => true)",
                "~(ready => true)",
                "~()",
                "~record(\"ready\" => true)",
                "~(foo: 1, foo: 2)",
                "~record(...)",
                "~record(foo: 1, \"x\" => true)")) {
            assertThrows(IllegalArgumentException.class, () -> initializer(source), source);
        }
        assertDoesNotThrow(() -> initializer("Map.of()"));
    }

    @Test
    void readonlyStructuralFieldPatternsAreCheckedBeforeRuntime() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck("""
                pub fnc main(): int {
                    const p = infer struct{status: 200, value: 42};
                    return match p over
                        on structural ~(status: 200, value: v) -> v
                        default -> 0
                    end;
                }
                """));

        for (String source : List.of(
                "on ~(status: 200, value: v) -> v",
                "on structural ~(secret: 42) -> 42",
                "on structural ~(status: 200, status: 201) -> 42")) {
            assertThrows(IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck("""
                            pub fnc main(): int {
                                const p = infer struct{status: 200, value: 42};
                                return match p over
                            """ + source + """
                                    default -> 0
                                end;
                            }
                            """), source);
        }
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
                "on default when true -> 42;",
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
    void legacyOnDefaultRemainsAnInputAliasOfWildcardFallback() {
        Ast.FunctionDecl value = function("""
                fnc f(int x): int {
                    return match<int> x over
                        on 1 -> 42
                        on default -> 0
                    end;
                }
                """);
        Ast.MatchExpr expression = assertInstanceOf(Ast.MatchExpr.class,
                ((Ast.ReturnStmt) value.body().getFirst()).value());
        assertEquals(2, expression.arms().size());
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
