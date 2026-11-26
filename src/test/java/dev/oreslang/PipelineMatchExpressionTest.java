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
                "flag |> match<int> over on true -> { if flag then return 1; fi return 2; }, on false -> 0, end"
        }) {
            assertThrows(IllegalArgumentException.class, () -> returned(invalid), invalid);
        }
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
