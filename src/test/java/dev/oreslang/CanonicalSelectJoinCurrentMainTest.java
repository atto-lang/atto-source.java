package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.BuildOptions;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

final class CanonicalSelectJoinCurrentMainTest {
    private static String run(String source, boolean optimize) throws Exception {
        Ast.Program checked;
        if (optimize) {
            var optimized = OresCompiler.compileForBuildStrictAtto(source, BuildOptions.executable());
            var readmitted = TypeChecker.checkTypesWithDiagnostics(optimized.program(), true);
            OwnershipChecker.check(readmitted.program(), readmitted.shortInferredAliases());
            checked = readmitted.program();
        } else {
            checked = OresCompiler.parseAndTypeCheckStrictAtto(source);
        }
        var output = new ByteArrayOutputStream();
        String name = "canonical-select-join.ores";
        try (var lease = OresLanguage.openPrecheckedProgram(name, source, checked);
             Context context = Context.newBuilder(OresLanguage.ID)
                     .allowAllAccess(false).out(output).build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, source, name).build());
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    @Test
    void defaultArmVoidExitsRunDeferThenJoin() throws Exception {
        for (String exit : new String[] {"return;", "return void;", "^ void;", ""}) {
            String source = """
                    fnc compute(): int {
                        do select {
                            default: {
                                defer stdio.stdout.write("D");
                                stdio.stdout.write("A");
                                %s
                            }
                        }
                        stdio.stdout.write("J");
                        return 9;
                    }
                    pub routine main(): void {
                        stdio.stdout.write(compute());
                    }
                    """.formatted(exit);
            assertEquals("ADJ9", run(source, false));
            assertEquals("ADJ9", run(source, true));
        }
    }

    @Test
    void readyReadArmUsesItsBindingAndJoins() throws Exception {
        String source = """
                fnc compute(): int {
                    val Channel<int> incoming = Channel.new<int>(1);
                    writech incoming, 7;
                    do select {
                        case readch incoming: val number {
                            stdio.stdout.write(number);
                            ^ void;
                        }
                        default: { stdio.stdout.write("X"); }
                    }
                    return 9;
                }
                pub routine main(): void {
                    stdio.stdout.write(compute());
                }
                """;
        assertEquals("79", run(source, false));
    }

    @Test
    void nestedSelectJoinsNearestArmAndDoesNotSkipOuterCleanup() throws Exception {
        String source = """
                fnc compute(): int {
                    do select {
                        default: {
                            defer stdio.stdout.write("O");
                            do select {
                                default: {
                                    defer stdio.stdout.write("I");
                                    return void;
                                }
                            }
                            stdio.stdout.write("A");
                            ^ void;
                        }
                    }
                    return 9;
                }
                pub routine main(): void {
                    stdio.stdout.write(compute());
                }
                """;
        assertEquals("IAO9", run(source, false));
    }

    @Test
    void cooperatedArmRunsCleanupOnceAndContinues() throws Exception {
        String source = """
                fnc compute(): int {
                    defer stdio.stdout.write("O");
                    do select {
                        default: {
                            defer stdio.stdout.write("D");
                            stdio.stdout.write("B");
                            rt cooperate;
                            stdio.stdout.write("A");
                            return;
                        }
                    }
                    stdio.stdout.write("J");
                    return 9;
                }
                pub async fnc main(): void {
                    stdio.stdout.write(compute());
                    return;
                }
                """;
        assertEquals("BADJO9", run(source, false));
    }

    @Test
    void unsupportedEarlyReturnsAndNonvoidArmResultsAreRejected() {
        for (String arm : new String[] {"return 9;", "^ 9;",
                "return; stdio.stdout.write(1);", "do if true then return; fi"}) {
            var error = assertThrows(IllegalArgumentException.class, () -> Parser.parseStrictAtto("""
                    fnc compute(): int {
                        do select { default: { %s } }
                        return 9;
                    }
                    """.formatted(arm)));
            assertTrue(error.getMessage().contains("do select"), error::getMessage);
        }
    }

    @Test
    void legacyParserRetainsHistoricalCallableReturn() {
        assertDoesNotThrow(() -> Parser.parse("""
                fnc compute(): int {
                    do select { default: { return 9; } }
                    return 3;
                }
                """));
    }
}
