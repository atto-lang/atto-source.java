package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Canonical Atto conditionals must not silently interchange value expressions
 * and imperative statements. Legacy parse() remains a migration entry point.
 */
final class AttoConditionalStatementExpressionTest {
    @Test
    void valueIfProducesAStaticallyCheckedValue() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parseStrictAtto("""
                fnc choose(bool ready): int {
                    return if ready then 11 else 22 fi;
                }
                """)));
        assertEquals("1122", run("""
                fnc choose(bool ready): int {
                    return if ready then 11 else 22 fi;
                }
                pub routine main(): void {
                    stdio.stdout.write(choose(true));
                    stdio.stdout.write(choose(false));
                }
                """));
    }

    @Test
    void valueIfAndTernaryAgreeOnValueSemantics() {
        assertEquals("77", run("""
                pub routine main(): void {
                    val a = if true then 7 else 9 fi;
                    val b = true ? 7 : 9;
                    stdio.stdout.write(a);
                    stdio.stdout.write(b);
                }
                """));
    }

    @Test
    void plainKeywordIfCannotContainImperativeBreakEvenInsideALoop() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> Parser.parseStrictAtto("""
                pub routine main(): void {
                    loop {
                        if true then break; fi
                    }
                }
                """));
        assertTrue(failure.getMessage().contains("use 'do if"), failure::getMessage);
    }

    @Test
    void valueIfRequiresAnElseValue() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> Parser.parseStrictAtto("""
                fnc choose(): int {
                    return if true then 7 fi;
                }
                """));
        assertTrue(failure.getMessage().contains("requires an 'else' value"), failure::getMessage);
    }

    @Test
    void valueBranchesCannotReturnControlFlowStatements() {
        for (String control : new String[] { "break", "continue", "return" }) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> Parser.parseStrictAtto("fnc choose(): int { return if true then "
                            + control + "; else 7 fi; }"));
            assertTrue(failure.getMessage().contains("must produce a value"), failure::getMessage);
        }
    }

    @Test
    void canonicalGuestFixtureParsesAndRuns() throws Exception {
        String source = Files.readString(
                Path.of("tests/fixtures/conditional/do-if-nested-loop.atto"));
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parseStrictAtto(source)));
        assertEquals("3", run(source));
    }

    @Test
    void doIfAllowsBreakAndContinueInNestedLoops() {
        String source = """
                pub routine main(): void {
                    let outer = 0;
                    let total = 0;
                    loop {
                        outer = outer + 1;
                        do if outer > 3 then break; fi
                        let inner = 0;
                        loop {
                            inner = inner + 1;
                            do if inner eq 2 then continue; fi
                            do if inner > 2 then break; fi
                            total = total + inner;
                        }
                    }
                    stdio.stdout.write(total);
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parseStrictAtto(source)));
        assertEquals("3", run(source));
    }

    @Test
    void doIfBreakOutsideLoopStillFailsTypeChecking() {
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parseStrictAtto("""
                pub routine main(): void {
                    do if true then break; fi
                }
                """)));
    }

    @Test
    void canonicalBracesAreImperativeAndNeedNoFi() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parseStrictAtto("""
                fnc choose(bool ready): int {
                    if ready {
                        return 1;
                    } else {
                        return 2;
                    }
                }
                """)));
    }

    @Test
    void keywordIfInStatementPositionRequiresDoInStrictProfile() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> Parser.parseStrictAtto("""
                pub routine main(): void {
                    if true then stdio.stdout.write(3); fi
                }
                """));
        assertTrue(failure.getMessage().contains("use 'do if"), failure::getMessage);
    }

    @Test
    void mismatchedExpressionArmsFailTypeChecking() {
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parseStrictAtto("""
                fnc choose(bool ready): int {
                    return if ready then 3 else "wrong-type" fi;
                }
                """)));
    }

    private static String run(String source) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(OresLanguage.ID, source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
