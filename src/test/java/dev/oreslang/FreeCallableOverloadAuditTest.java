package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** Package/file root and named modules share one closed-world name+arity callable contract. */
final class FreeCallableOverloadAuditTest {
    @Test
    void fncAndRoutineOfDifferentAritiesCoexistAtRootAndInModules() throws Exception {
        String output = run("""
                fnc pick(): int { return 4; }
                routine pick(int value): int { return value + 5; }
                fnc pick(int left, int right): int { return left + right; }

                define module ops as
                  pub routine choose(): int { return 10; }
                  pub fnc choose(int value): int { return value + 1; }
                  pub routine choose(int left, int right): int { return left + right; }
                end

                pub routine main(): void {
                  stdio.stdout.write(pick());
                  stdio.stdout.write(pick(4));
                  stdio.stdout.write(pick(3, 2));
                  stdio.stdout.write("|");
                  stdio.stdout.write(ops.choose());
                  stdio.stdout.write(ops.choose(2));
                  stdio.stdout.write(ops.choose(6, 1));
                  return;
                }
                """);
        assertEquals("495|1037", output);
    }

    @Test
    void fncAndRoutineOfTheSameArityCollideEvenWithDifferentParameterTypes() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        pub fnc pick(int value): int { return value; }
                        pub routine pick(bool value): int { return value ? 1 : 0; }
                        """)));
        assertTrue(error.getMessage().contains("already has arity 1"), error::getMessage);
    }

    @Test
    void moduleFncAndRoutineWithSameArityAreRejectedBeforeBodyChecking() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define module ops as
                          pub routine choose(int value): int { return value; }
                          pub fnc choose(String value): int { return 1; }
                        end
                        """)));
        assertTrue(error.getMessage().contains("already has arity 1"), error::getMessage);
    }

    @Test
    void moduleValueMemberCannotShadowCallableFamily() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define module ops as
                          pub fnc data(): int { return 1; }
                          pub val int data = 2;
                        end
                        """)));
        assertTrue(error.getMessage().contains("module value member"), error::getMessage);
    }

    @Test
    void sameNamedCallablesInSeparateModulesRequireQualification() throws Exception {
        String output = run("""
                define module first as
                  pub fnc answer(): int { return 1; }
                end
                define module second as
                  pub routine answer(): int { return 2; }
                end
                pub routine main(): void {
                  stdio.stdout.write(first.answer());
                  stdio.stdout.write(second.answer());
                  return;
                }
                """);
        assertEquals("12", output);
    }

    @Test
    void distinctAritiesDoNotAuthorizeStructuralOrReturnTypeOverloading() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        fnc same(int value): int { return value; }
                        fnc same(String value): String { return value; }
                        """)));
        assertTrue(error.getMessage().contains("already has arity 1"), error::getMessage);
    }

    private static String run(String program) throws Exception {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(program)));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "free-callable-overloads.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
