package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;

import java.util.List;

/**
 * Standalone parser/AST migration smoke. Tilde is structural syntax, not a
 * Map/Record/Tuple factory. This is not guest execution or AOT certification.
 */
public final class TildeRecordParserSmoke {
    private TildeRecordParserSmoke() {}

    private static int checks;

    private static void check(boolean condition, String why) {
        checks++;
        if (!condition) throw new AssertionError(why);
    }

    private static Ast.FunctionDecl function(String source) {
        return Parser.parse(source).modules().stream()
                .flatMap(m -> m.declarations().stream())
                .filter(Ast.FunctionDecl.class::isInstance)
                .map(Ast.FunctionDecl.class::cast).findFirst().orElseThrow();
    }

    private static Ast.Expr initializer(String expr) {
        var f = function("fnc f(): void { const x = " + expr + "; return; }");
        return ((Ast.BindingStmt) f.body().getFirst()).initializer();
    }

    private static void rejects(String expression) {
        checks++;
        try {
            initializer(expression);
        } catch (IllegalArgumentException correct) {
            return;
        }
        throw new AssertionError("accepted forbidden expression: " + expression);
    }

    private static void rejectsProgram(String source) {
        checks++;
        try {
            Parser.parse(source);
        } catch (IllegalArgumentException correct) {
            return;
        }
        throw new AssertionError("accepted forbidden program: " + source);
    }

    public static void main(String[] args) {
        // Previously positive aggregate factories must now fail at the
        // parser boundary, including whitespace, typed and bare forms.
        for (String candidate : List.of(
                "~(foo: 42)",
                "~record(foo: 42)",
                "~record(foo: 42, ready: true)",
                "~record(foo: 42,)",
                "~(\"hello world\": 42)",
                "~record()",
                "~record(...base, extra: 41)",
                "~map(\"ready\" => true)",
                "~map<String,bool>(\"ready\" => true)",
                "~map()",
                "~tuple()",
                "~tuple(1, \"two\")",
                "~array(1, 2)",
                "~ array(1, 2)",
                "~ tuple(1, 2)",
                "~<Map<string,int>>()",
                "~(\"ready\" => true)",
                "~(ready => true)",
                "~()",
                "~(foo: 1, foo: 2)",
                "~record(...)",
                "~record(foo: 1, \"x\" => true)")) {
            rejects(candidate);
        }
        rejectsProgram("""
                fnc f(): void {
                    const Map<string,int> flags = ~map();
                    return;
                }
                """);

        // Existing independently defined literals are preserved.
        var plainRecord = (Ast.ObjectExpr) initializer("record{foo: 42}");
        check(plainRecord.kind() == Ast.ObjectKind.RECORD,
                "record{} literal retains record identity");
        var unary = (Ast.UnaryExpr) initializer("~(value)");
        check(unary.operator().equals("~"), "unary tilde remains independent");
        check(unary.operand() instanceof Ast.NameExpr, "unary operand kept");

        // Canonical factories parse as calls; execution is separately gated.
        initializer("Map.of()");
        initializer("Tuple.of(1, \"two\")");
        initializer("Array.of(1, 2)");
        initializer("Record.of()");
        checks += 4;

        var expression = function("""
                fnc f(int x): int {
                    return match<int> x over
                        on 1 -> 42;
                        default -> 0;
                    end;
                }
                """);
        var match = (Ast.MatchExpr) ((Ast.ReturnStmt) expression.body().getFirst()).value();
        check(match.arms().size() == 2, "match has explicit and wildcard arm");
        check(match.arms().getLast().pattern() instanceof Ast.WildcardPattern,
                "default is wildcard");

        var procedure = function("""
                fnc f(int x): int {
                    match x over
                        on 1 -> { return 42; }
                        default -> { return 0; }
                    end
                }
                """);
        var stmt = (Ast.MatchStmt) procedure.body().getFirst();
        check(stmt.arms().getLast().pattern() instanceof Ast.WildcardPattern,
                "statement wildcard stays final");

        for (String wrong : List.of(
                "default when true -> 42;",
                "default -> 42; default -> 0;",
                "default -> 42; on 1 -> 0;",
                "on default -> 42;",
                "else -> 42;")) {
            rejectsProgram("fnc f(int x): int { return match<int> x over " + wrong + " end; }");
        }
        rejectsProgram("""
                fnc f(int x): int {
                    match x over
                        default -> { return 42; }
                        on 1 -> { return 0; }
                    end
                }
                """);

        var legacyWildcard = function("""
                fnc f(int x): int {
                    return match<int> x over
                        on 1 -> 42;
                        on _ -> 0;
                    end;
                }
                """);
        var oldMatch = (Ast.MatchExpr)
                ((Ast.ReturnStmt) legacyWildcard.body().getFirst()).value();
        check(oldMatch.arms().getLast().pattern() instanceof Ast.WildcardPattern,
                "on _ remains a wildcard fallback");

        System.out.println("ATTO_TILDE_RECORD_PARSER_SMOKE_PASS checks=" + checks);
    }
}
