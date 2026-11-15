package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;

import java.util.List;

/**
 * Standalone JDK-21 parser/AST smoke. Does NOT claim typed Map/structural-pattern
 * lowering or backend guest execution. Keeps source-only mirrors dependency-free.
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
        for (String candidate : List.of(
                "~(foo: 42)",
                "~record(foo: 42)",
                "~record(foo: 42, ready: true)",
                "~record(foo: 42,)",
                "~(\"hello world\": 42)",
                "~record()")) {
            var o = (Ast.ObjectExpr) initializer(candidate);
            check(o.kind() == Ast.ObjectKind.RECORD, "tilde literal must be readonly record");
            check(o.structuralRecord(), "tilde record retains structural readonly kind");
        }

        var f = function("""
                fnc f(): int {
                    const base = record{foo: 1};
                    const r = ~record(...base, extra: 41);
                    const x = ~(foo: 42).foo;
                    const y = ~record(foo: 42)["foo"];
                    return x + y;
                }
                """);
        var record = (Ast.ObjectExpr) ((Ast.BindingStmt) f.body().get(1)).initializer();
        check(record.fields().size() == 2, "spread retains both fields");
        check(record.fields().getFirst().spread(), "spread preserves order");
        check(((Ast.BindingStmt) f.body().get(2)).initializer() instanceof Ast.MemberExpr,
                "postfix member after tilde record");
        check(((Ast.BindingStmt) f.body().get(3)).initializer() instanceof Ast.IndexExpr,
                "postfix indexing after tilde record");

        var oldUnary = (Ast.UnaryExpr) initializer("~(value)");
        check(oldUnary.operator().equals("~"), "legacy unary tilde remains unary");
        check(oldUnary.operand() instanceof Ast.NameExpr, "legacy unary keeps original operand");

        var value = function("""
                fnc f(int x): int {
                    return match<int> x over
                        on 1 -> 42;
                        default -> 0;
                    end;
                }
                """);
        var match = (Ast.MatchExpr) ((Ast.ReturnStmt) value.body().getFirst()).value();
        check(match.arms().size() == 2, "value match includes two arms");
        check(match.arms().getLast().pattern() instanceof Ast.WildcardPattern,
                "value default desugars to last wildcard");

        var procedural = function("""
                fnc f(int x): int {
                    match x over
                        on 1 -> { return 42; }
                        default -> { return 0; }
                    end
                }
                """);
        var statement = (Ast.MatchStmt) procedural.body().getFirst();
        check(statement.arms().getLast().pattern() instanceof Ast.WildcardPattern,
                "statement default is last wildcard");

        for (String wrong : List.of(
                "~()",
                "~map(\"ready\" => true)",
                "~map<String,bool>(\"ready\" => true)",
                "~(\"ready\" => true)",
                "~(ready => true)",
                "~record(\"ready\" => true)",
                "~(foo: 1, foo: 2)",
                "~record(...)",
                "~record(foo: 1, \"x\" => true)")) {
            rejects(wrong);
        }
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
        // Existing wildcard fallbacks remain valid during syntax migration.
        var compat = function("""
                fnc f(int x): int {
                    return match<int> x over
                        on 1 -> 42;
                        on _ -> 0;
                    end;
                }
                """);
        var previous = (Ast.MatchExpr) ((Ast.ReturnStmt) compat.body().getFirst()).value();
        check(previous.arms().getLast().pattern() instanceof Ast.WildcardPattern,
                "legacy on _ stays compatible");

        // V3 explicit kind and typed-empty syntax: parser admission is not
        // authority to construct the unproven guest Map<K,V> or nominal types.
        var emptyTuple = initializer("~tuple()");
        check(emptyTuple instanceof Ast.TupleExpr,
                "~tuple() must resolve to existing positional TupleExpr");
        var pairTuple = initializer("~tuple(1, \"two\")");
        check(pairTuple instanceof Ast.TupleExpr,
                "~tuple(1, two) must remain a TupleExpr");
        var typedEmptyMap = initializer("~<Map<string,int>>()");
        check(typedEmptyMap instanceof Ast.TypedEmptyAggregateExpr,
                "~<Map<K,V>>() must preserve a type witness");
        check(((Ast.TypedEmptyAggregateExpr) typedEmptyMap).aggregateType() != null,
                "typed-empty map must not erase its compile-time type witness");
        rejects("~<>()");
        rejects("~<Map<string,int>>(42)");
        rejects("~(foo: 1, foo: 2)");

        // Explicit structural record-field matching remains reserved until
        // type checking has proved field projection, ownership and visibility.
        rejectsProgram("""
                fnc f(int x): int {
                    return match<int> x over
                        on structural ~(foo: value) -> 42;
                        default -> 0;
                    end;
                }
                """);

        // The newer parent admits a contextual empty map *expression*
        // only when type checking can prove a Map<K,V> expectation.
        var contextual = function("""
                fnc f(): void {
                    const Map<string,int> flags = ~map();
                    return;
                }
                """);
        Ast.Expr contextualValue =
                ((Ast.BindingStmt) contextual.body().getFirst()).initializer();
        check(contextualValue instanceof Ast.TypedEmptyAggregateExpr,
                "typed contextual ~map() must be resolved to a typed-empty AST at binding admission");
        check(((Ast.TypedEmptyAggregateExpr) contextualValue).aggregateType().name().equals("Map"),
                "contextual map witness must retain its sealed Map identity");
        rejects("~map()");
        check(initializer("~map().size") instanceof Ast.MemberExpr,
                "postfix is parsed, but unresolved map ownership must be rejected by TypeChecker");
        // Map typed-empty admissions from #2725 must coexist with #2721
        // match default + record syntax. This only proves AST/lexical behavior.
        var typedMap = initializer("~map<string,int>()");
        check(typedMap instanceof Ast.TypedEmptyAggregateExpr,
                "explicit typed map must retain typed-empty witness");
        var typedMapMember = initializer("~map<string,int>().size");
        check(typedMapMember instanceof Ast.MemberExpr,
                "typed map .size parses as ordinary member access");
        var mapWitnessMember = initializer("~<Map<string,int>>().size");
        check(mapWitnessMember instanceof Ast.MemberExpr,
                "generic typed-empty map .size parses as member access");
        rejects("~map<string,int>(\"hello\" => 42)");
        rejects("~map<string,int,int>()");
        rejects("~(foo: 1, foo: 2)");

        System.out.println("ATTO_TILDE_RECORD_PARSER_SMOKE_PASS checks=" + checks);
    }
}
