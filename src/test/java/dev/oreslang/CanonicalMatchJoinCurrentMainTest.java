package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.compiler.BuildOptions;
import dev.oreslang.parser.Parser;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

final class CanonicalMatchJoinCurrentMainTest {
    private static void check(String source) {
        OresCompiler.parseAndTypeCheckStrictAtto(source);
    }

    private static String run(String source, boolean optimize) throws Exception {
        var program = optimize
                ? OresCompiler.compileForBuildStrictAtto(source, BuildOptions.executable()).program()
                : OresCompiler.parseAndTypeCheckStrictAtto(source);
        var output = new ByteArrayOutputStream();
        String name = "canonical-match-join.ores";
        try (var lease = OresLanguage.openPrecheckedProgram(name, source, program);
             Context context = Context.newBuilder(OresLanguage.ID)
                     .allowAllAccess(false).out(output).build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, source, name).build());
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    @Test
    void bracedValueArmRunsCleanupBeforeJoining() throws Exception {
        String source = """
                fnc choose(bool flag): int {
                    val int n = match flag over
                        on true -> { defer stdio.stdout.write("D"); return 42; }
                        on false -> { return 7; }
                    end;
                    stdio.stdout.write("J");
                    return n;
                }
                pub routine main(): void {
                    stdio.stdout.write(choose(true));
                    stdio.stdout.write(choose(false));
                }
                """;
        assertEquals("DJ42J7", run(source, false));
        assertEquals("DJ42J7", run(source, true));
    }

    @Test
    void terminalVoidDoMatchExitsAreAllocationFreeAtAstLevel() throws Exception {
        for (String exit : new String[] {"return;", "return void;", "^ void;"}) {
            String source = """
                    fnc choose(bool flag): int {
                        do match flag over
                            on true -> { defer stdio.stdout.write("D"); %s }
                            on false -> { stdio.stdout.write("F"); }
                        end
                        return 9;
                    }
                    pub routine main(): void {
                        stdio.stdout.write(choose(true));
                        stdio.stdout.write(choose(false));
                    }
                    """.formatted(exit);
            assertEquals("D9F9", run(source, false));
        }
    }

    @Test
    void valueAndNestedEarlyReturnsCannotEscapeProceduralMatch() {
        for (String arm : new String[] {"return 9;", "return; stdio.stdout.write(1);",
                "do if true then return; fi"}) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parseStrictAtto("""
                    fnc choose(bool flag): int {
                        do match flag over
                            on true -> { %s }
                            on false -> { }
                        end
                        return 2;
                    }
                    """.formatted(arm)));
        }
    }

    @Test
    void discardedValueMatchCannotSatisfyNamedCallableReturn() {
        assertThrows(IllegalArgumentException.class, () -> check("""
                fnc choose(bool flag): int {
                    match flag over
                        on true -> { return 42; }
                        on false -> { return 7; }
                    end
                }
                """));
    }

    @Test
    void discardedMatchContinuesAndBareExpressionSuppliesArmValue() throws Exception {
        String source = """
                fnc choose(bool flag): int {
                    match flag over
                        on true -> { return 42; }
                        on false -> { 7; }
                    end;
                    return 9;
                }
                pub routine main(): void {
                    stdio.stdout.write(choose(true));
                    stdio.stdout.write(choose(false));
                }
                """;
        assertEquals("99", run(source, false));
    }
}
