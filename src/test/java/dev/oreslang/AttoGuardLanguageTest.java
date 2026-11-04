package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class AttoGuardLanguageTest {
    @Test
    void successfulGuardFallsThroughAndFailureReturns() throws Exception {
        assertEquals("3-1", run("""
                fnc choose(int x): int {
                  guard x > 0 { return -1; }
                  return x;
                }
                pub routine main(): void {
                  stdio.stdout.write(choose(3));
                  stdio.stdout.write(choose(0));
                }
                """));
    }

    @Test
    void commaConditionsShortCircuitAndRequireBothChecks() throws Exception {
        assertEquals("399", run("""
                fnc choose(int x): int {
                  guard x > 0, x < 5 { return 9; }
                  return x;
                }
                pub routine main(): void {
                  stdio.stdout.write(choose(7));
                  stdio.stdout.write(choose(0));
                  stdio.stdout.write(choose(8));
                }
                ""));
    }

    @Test
    void guardAcceptsExplicitVoidReturnAndKeepsFollowingStatements() throws Exception {
        assertEquals("yes", run("""
                fnc emit(bool ready): void {
                  guard ready { return; }
                  stdio.stdout.write("yes");
                }
                pub routine main(): void {
                  emit(false);
                  emit(true);
                }
                """));
    }

    @Test
    void rejectsNonTerminalOrEmptyFailureBody() {
        reject("""
                fnc choose(int x): int {
                  guard x > 0 { stdio.stdout.write(x); }
                  return x;
                }
                """);
        reject("""
                fnc choose(int x): int {
                  guard x > 0 { }
                  return x;
                }
                """);
    }

    @Test
    void rejectsElseSyntaxAndNonBooleanCondition() {
        reject("""
                fnc choose(int x): int {
                  guard x > 0 else { return 0; }
                  return x;
                }
                """);
        reject("""
                fnc choose(int x): int {
                  guard x { return 0; }
                  return x;
                }
                """);
    }

    @Test
    void requiresBothBranchesOfNestedConditionalToExit() {
        reject("""
                fnc choose(int x): int {
                  guard x > 0 {
                    if x == 0 { return 0; } fi
                  }
                  return x;
                }
                """);
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc choose(int x): int {
                  guard x > 0 {
                    if x == 0 { return 0; } else { return 1; } fi
                  }
                  return x;
                }
                """)));
    }

    private static void reject(String source) {
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
    }

    private static String run(String source) throws Exception {
        TypeChecker.check(Parser.parse(source));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source program = Source.newBuilder(OresLanguage.ID, source, "guard.atto")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(program);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
