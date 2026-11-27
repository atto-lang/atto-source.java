package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

final class PipelineMatchExpressionTest {
    private static Ast.Expr returned(String expr) {
        var program = Parser.parseStrictAtto(
                "fnc f(bool flag): int { return " + expr + "; }");
        var fn = (Ast.FunctionDecl) program.modules().getFirst().declarations().getFirst();
        return ((Ast.ReturnStmt) fn.body().getFirst()).value();
    }

    @Test
    void typedPipelineAndStandaloneMatchHaveTheSameAst() {
        assertEquals(
                returned("match<int> flag over on true -> 42, on false -> 1, end"),
                returned("flag |> match<int> over on true -> 42, on false -> 1, end"));
    }

    @Test
    void braceAndEndDelimitedMatchesProduceIdenticalAst() {
        for (String type : new String[] {"", "<int>"}) {
            var expected = returned("match" + type
                    + " flag over on true -> 42, on false -> { return 1; }, end");
            assertEquals(expected, returned("match" + type
                    + " flag over { on true -> 42, on false -> { return 1; }, }"));
            assertEquals(expected, returned("flag |> match" + type
                    + " over { on true -> 42, on false -> { return 1; }, }"));
            assertEquals(expected, returned("flag |> match" + type
                    + " over { on true -> 42, on false -> { return 1; } }"));
        }
        assertEquals(
                returned("match flag over on true -> 42, on false -> 1, end"),
                returned("flag |> match over { on true { return 42; } on false -> 1 }"));
    }

    @Test
    void optionalOnBeforeIsIsIdenticalInStandaloneAndPipelineMatches() {
        var expected = returned("match flag over { on is bool -> 42; }");
        assertEquals(expected, returned("match flag over { is bool -> 42; }"));
        assertEquals(expected, returned("flag |> match over { is bool -> 42; }"));
        assertEquals(expected, returned("flag |> match over is bool -> 42; end"));
        assertThrows(IllegalArgumentException.class,
                () -> returned("flag |> match over { true -> 42; on _ -> 0; }"));
        assertThrows(IllegalArgumentException.class,
                () -> returned("flag |> match over { _ -> 0; }"));
        assertThrows(IllegalArgumentException.class,
                () -> returned("flag |> match over { is -> 42; }"));
    }

    @Test
    void standaloneTypeArmAndPipelineTypeArmAreCheckedAndExecuted() throws Exception {
        String source = """
                fnc choose(int | string value): int {
                    return match value over {
                        on 42 -> 100;
                        is int -> -20;
                        on _ -> 0;
                    };
                }
                fnc piped(int | string value): int {
                    return value |> match over {
                        on 42 -> 100;
                        is int -> -20;
                        on _ -> 0;
                    };
                }
                pub routine main(): void {
                    stdio.stdout.write(choose(42));
                    stdio.stdout.write(choose(7));
                    stdio.stdout.write(choose("other"));
                    stdio.stdout.write(piped(42));
                    stdio.stdout.write(piped(7));
                    stdio.stdout.write(piped("other"));
                }
                """;
        var program = OresCompiler.parseAndTypeCheckStrictAtto(source);
        var output = new ByteArrayOutputStream();
        String name = "optional-on-type-match.ores";
        try (var lease = OresLanguage.openPrecheckedProgram(name, source, program);
             Context context = Context.newBuilder(OresLanguage.ID)
                     .allowAllAccess(false).out(output).build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, source, name).build());
        }
        assertEquals("100-200100-200", output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void scalarTypeListUsesExistingUnionTypePatternAndExecutes() throws Exception {
        var expected = returned("match flag over { is bool | int -> 42; }");
        assertEquals(expected, returned("match flag over { is in [bool, int] -> 42; }"));
        assertEquals(expected, returned("flag |> match over { on is in [int, bool] -> 42; }"));
        String source = """
                fnc choose(int | string value): int {
                    return match value over {
                        on 42 -> 100;
                        is in [int, String] -> 42;
                    };
                }
                fnc piped(int | string value): int {
                    return value |> match over {
                        is in [int, string] -> 42;
                    };
                }
                pub routine main(): void {
                    stdio.stdout.write(choose(42));
                    stdio.stdout.write(choose(7));
                    stdio.stdout.write(choose("text"));
                    stdio.stdout.write(piped(7));
                    stdio.stdout.write(piped("text"));
                }
                """;
        var program = OresCompiler.parseAndTypeCheckStrictAtto(source);
        var output = new ByteArrayOutputStream();
        String name = "scalar-type-list-match.ores";
        try (var lease = OresLanguage.openPrecheckedProgram(name, source, program);
             Context context = Context.newBuilder(OresLanguage.ID)
                     .allowAllAccess(false).out(output).build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, source, name).build());
        }
        assertEquals("10042424242", output.toString(StandardCharsets.UTF_8));
        for (String bad : new String[] {
                "is in []", "is in [int,int]", "is in [String,string]",
                "is in [bool,42]", "is in [bool,Widget]", "is in [bool int]"
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> returned("match flag over { " + bad + " -> 42; on _ -> 0; }"), bad);
        }
        assertThrows(IllegalArgumentException.class, () ->
                OresCompiler.parseAndTypeCheckStrictAtto(
                        "fnc f(bool flag): int { return match flag over { is in [bool,int] -> 42; on _ -> 0; }; }"));
    }

    @Test
    void storedScalarPatternsAndTypeSetsExecuteThroughExistingMatch() throws Exception {
        String source = """
                const Pattern p = on 5;
                const Pattern s = on "x";
                const Pattern b = on true;
                const Pattern[] type_patterns = [is int, is bool];
                fnc choose(int | bool value): int {
                    return match value over {
                        on p -> 5;
                        is in type_patterns -> 42;
                    };
                }
                fnc text(string value): int {
                    return match value over { on s -> 42; on _ -> 0; };
                }
                fnc flag(bool value): int {
                    return match value over { on b -> 42; on false -> 0; };
                }
                pub routine main(): void {
                    stdio.stdout.write(choose(5));
                    stdio.stdout.write(choose(true));
                    stdio.stdout.write(text("x"));
                    stdio.stdout.write(flag(true));
                }
                """;
        var program = OresCompiler.parseAndTypeCheckStrictAtto(source);
        var output = new ByteArrayOutputStream();
        String name = "stored-patterns.ores";
        try (var lease = OresLanguage.openPrecheckedProgram(name, source, program);
             Context context = Context.newBuilder(OresLanguage.ID)
                     .allowAllAccess(false).out(output).build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, source, name).build());
        }
        assertEquals("5424242", output.toString(StandardCharsets.UTF_8));
        for (String bad : new String[] {
                "const Pattern p = on q; fnc f():int { return 42; }",
                "const Pattern[] z = [is int,is int]; fnc f():int { return 42; }",
                "const Pattern[] z = [is int,on 5]; fnc f():int { return 42; }",
                "const Pattern p = on 5; fnc f():int { return p; }",
                "const Pattern p = on 5; fnc f(int p):int { return match p over on p -> 42, on _ -> 0 end; }"
        }) assertThrows(IllegalArgumentException.class, () -> Parser.parseStrictAtto(bad), bad);
    }

    @Test
    void delimitedSubjectAndNamedPatternHaveIdenticalAst() {
        var plain = Parser.parseStrictAtto(
                "const Pattern p = on 5; fnc f(int n): int { return match n over on p -> 42, on _ -> 0 end; }");
        var delimited = Parser.parseStrictAtto(
                "const Pattern p = on 5; fnc f(int n): int { return match |n| over on p -> 42, on _ -> 0 end; }");
        var direct = Parser.parseStrictAtto(
                "fnc f(int n): int { return match n over on 5 -> 42, on _ -> 0 end; }");
        assertEquals(plain, delimited);
        assertEquals(direct, plain);
    }

    @Test
    void braceDelimitedResultMatchContinuesPipeline() {
        String source = """
                fnc increment(int n): int { return n + 1; }
                fnc choose(Result<int,int> result): int {
                    return result
                        |> match over {
                            on Ok(value) -> value,
                            on Err(error) -> 42,
                        } |> increment;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheckStrictAtto(source));
    }

    @Test
    void matchStagesComposeWithOrdinaryCallsAndOtherMatches() {
        var expr = returned("flag |> match over on true -> 42, on false -> 1, end"
                + " |> match<int> over on 42 -> 7, default -> 0, end |> increment");
        var call = assertInstanceOf(Ast.CallExpr.class, expr);
        assertEquals("increment", ((Ast.NameExpr) call.callee()).name());
        assertInstanceOf(Ast.MatchExpr.class, call.arguments().getFirst());
    }

    @Test
    void malformedStagesAndUnprovenArmReturnsFailClosed() {
        for (String invalid : new String[] {
                "flag |> match<int> flag over on true -> 1, on false -> 0, end",
                "match<int> over on true -> 1, on false -> 0, end",
                "flag |> match<int> over end",
                "flag |> match over {}",
                "flag |> match over { on true -> 1, on false -> 0, end",
                "flag |> match over on true -> 1, on false -> 0, }",
                "flag |> match over { on true 1, on false -> 0, }",
                "flag |> match over { default -> 1, on true -> 0, }",
                "flag |> match over { on true -> 1,, on false -> 0, }",
                "flag |> match<int> over default -> 1, on true -> 0, end",
                "flag |> match<int> over on true -> { do if flag then return 1; fi return 2; }, on false -> 0, end",
                "flag |> match<int> over on true -> { if flag { return 1; } return 2; }, on false -> 0, end"
        }) {
            assertThrows(IllegalArgumentException.class, () -> returned(invalid), invalid);
        }
    }

    @Test
    void functionalIfActionIsNotAnEnclosingReturn() {
        var match = assertInstanceOf(Ast.MatchExpr.class, returned(
                "flag |> match<int> over on true -> { if flag then return 1; fi return 2; }, on false -> 0, end"));
        var arm = match.arms().getFirst();
        var conditional = assertInstanceOf(Ast.IfStmt.class, arm.prefixStatements().getFirst());
        assertInstanceOf(Ast.ExprStmt.class, conditional.branches().getFirst().body().getFirst());
        assertEquals(new Ast.LiteralExpr(2L), arm.value());
    }

    @Test
    void normalTypeAndExhaustivenessChecksRemainInForce() {
        for (String invalid : new String[] {
                "flag |> match<int> over on true -> 1, end",
                "flag |> match<int> over { on true -> 1, }",
                "flag |> match<int> over { on true -> 1, on false -> \"wrong\", }",
                "flag |> match<int> over on true -> 1, on false -> \"wrong\", end"
        }) {
            assertThrows(IllegalArgumentException.class, () ->
                    OresCompiler.parseAndTypeCheckStrictAtto(
                            "fnc f(bool flag): int { return " + invalid + "; }"), invalid);
        }
    }

    @Test
    void evaluatesSubjectOnceAndJoinsBeforeNextStage() throws Exception {
        String source = """
                fnc input(): bool {
                    stdio.stdout.write("S");
                    return true;
                }
                fnc increment(int n): int { return n + 1; }
                fnc choose(Option<int> value): int {
                    return value |> match<int> over {
                        on Some(n) -> n,
                        on None -> { return 7; },
                    } |> increment;
                }
                pub routine main(): void {
                    val int n = input() |> match over {
                        on true -> { defer stdio.stdout.write("D"); return 41; },
                        on false -> 0,
                    } |> increment;
                    stdio.stdout.write(n);
                    stdio.stdout.write(choose(Some(41)));
                    stdio.stdout.write(choose(None));
                }
                """;
        var program = OresCompiler.parseAndTypeCheckStrictAtto(source);
        var output = new ByteArrayOutputStream();
        String name = "pipeline-match.ores";
        try (var lease = OresLanguage.openPrecheckedProgram(name, source, program);
             Context context = Context.newBuilder(OresLanguage.ID)
                     .allowAllAccess(false).out(output).build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, source, name).build());
        }
        assertEquals("SD42428", output.toString(StandardCharsets.UTF_8));
    }
}
