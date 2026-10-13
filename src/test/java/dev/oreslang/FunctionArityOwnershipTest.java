package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class FunctionArityOwnershipTest {
    @Test
    void secondFncOverloadMustConsumeItsMoveOnlyArgument() {
        String source = """
                define class Resource as
                  pub val int id = 7;
                end

                fnc consume(int marker): void { return; }
                fnc consume(Resource value, int marker): void { return; }

                fnc bad(): void {
                  let Resource resource = new Resource();
                  consume(resource, 42);
                  stdio.println(resource.id);
                  return;
                }
                """;
        IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class,
                        () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(failure.getMessage().contains("use of moved value 'resource'"),
                failure.getMessage());
    }

    @Test
    void secondRoutineOverloadMustConsumeItsMoveOnlyArgument() {
        String source = """
                define class Resource as
                  pub val int id = 7;
                end

                routine consume(int marker): void { return; }
                routine consume(Resource value, int marker): void { return; }

                routine bad(): void {
                  let Resource resource = new Resource();
                  consume(resource, 42);
                  stdio.println(resource.id);
                  return;
                }
                """;
        IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class,
                        () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(failure.getMessage().contains("use of moved value 'resource'"),
                failure.getMessage());
    }

    @Test
    void qualifiedModuleOverloadMustUseSelectedArityOwnershipSignature() {
        String source = """
                define module library as
                  fnc consume(int marker): void { return; }
                  fnc consume(Resource value, int marker): void { return; }
                end

                define class Resource as
                  pub val int id = 7;
                end

                fnc bad(): void {
                  let Resource resource = new Resource();
                  library.consume(resource, 1);
                  stdio.println(resource.id);
                  return;
                }
                """;
        IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class,
                        () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(failure.getMessage().contains("use of moved value 'resource'"),
                failure.getMessage());
    }

    @Test
    void overloadsByArityWithDifferentPrimitivePositionsAreValid() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc mix(int a): int { return a; }
                fnc mix(int a, String b): int { return a; }
                routine execute(bool value): void { return; }
                routine execute(bool value, int n): void { return; }

                fnc valid(): void {
                  mix(1);
                  mix(1, "ok");
                  execute(true);
                  execute(false, 3);
                  return;
                }
                """)));
    }

    @Test
    void fncAndRoutineShareOneCallableFamilyByArity() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc execute(int value): int { return value; }
                routine execute(int value, int extra): int { return value + extra; }

                fnc success(): int {
                  return execute(1) + execute(2, 3);
                }
                """)));

        IllegalArgumentException duplicate = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc execute(int value): int { return value; }
                        routine execute(float value): int { return 2; }
                        """)));
        assertTrue(duplicate.getMessage().contains("already has arity 1"),
                duplicate.getMessage());
    }

    @Test
    void moduleQualifiedSameNameFunctionsKeepTheirSeparateOverloadFamilies() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module first as
                  pub fnc select(int value): int { return value; }
                  pub fnc select(int value, int extra): int { return value + extra; }
                end

                define module second as
                  pub fnc select(int value): int { return value + 10; }
                end

                fnc success(): int {
                  return first.select(1, 2) + second.select(3);
                }
                """)));
    }

    @Test
    void mixedFncAndRoutineOverloadsDispatchCorrectlyAtRuntime() throws Exception {
        String program = """
                fnc pick(int value): int { return value + 10; }
                routine pick(int left, int right): int { return left + right; }

                pub routine main(): void {
                  stdio.stdout.write(pick(2));
                  stdio.stdout.write(":");
                  stdio.stdout.write(pick(2, 5));
                  return;
                }
                """;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, program, "mixed-fnc-routine-arity.ores")
                    .mimeType(OresLanguage.MIME_TYPE)
                    .build());
        }
        assertEquals("12:7", output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void sameArityPrimitiveSignaturesStillFailClosedUntilTypedDispatchExists() {
        IllegalArgumentException conflict = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc route(int value): int { return value; }
                        fnc route(float value): int { return 2; }
                        """)));
        assertTrue(conflict.getMessage().contains("already has arity 1"),
                conflict.getMessage());

        IllegalArgumentException routineConflict = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        routine route(bool value): void { return; }
                        routine route(String value): void { return; }
                        """)));
        assertTrue(routineConflict.getMessage().contains("already has arity 1"),
                routineConflict.getMessage());
    }
}
