package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ModuleLexicalFunctionResolutionTest {
    @Test
    void namedModuleMayCallItsOwnOptionalFunctionUnqualified() throws Exception {
        String code = """
                define module app
                  @DefaultArgVals(b: 2)
                  fnc calculate(int a, int b?): int {
                    return a + b;
                  }

                  pub fnc run(): int {
                    return calculate(40);
                  }
                end

                pub routine main(): void {
                  stdio.stdout.write(app.run());
                  return;
                }
                """;

        assertEquals("42", checkedExecution(code));
    }

    @Test
    void namedModuleMaySeeFileRootCallableButNotForeignModuleCallable()
            throws Exception {
        String rootVisible = """
                @DefaultArgVals(b: 2)
                fnc calculate(int a, int b?): int {
                  return a + b;
                }

                define module app
                  pub fnc run(): int {
                    return calculate(40);
                  }
                end

                pub routine main(): void {
                  stdio.stdout.write(app.run());
                  return;
                }
                """;
        assertEquals("42", checkedExecution(rootVisible));

        String foreignHidden = """
                define module math
                  @DefaultArgVals(b: 2)
                  pub fnc calculate(int a, int b?): int {
                    return a + b;
                  }
                end

                define module app
                  pub fnc run(): int {
                    return calculate(40);
                  }
                end
                """;

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(foreignHidden)));
        assertTrue(
                failure.getMessage().contains("unknown")
                        || failure.getMessage().contains("callable")
                        || failure.getMessage().contains("function"),
                failure.getMessage());
    }

    @Test
    void ordinaryForeignFunctionAlsoRequiresModuleQualification() {
        String code = """
                define module math
                  pub fnc add(int a, int b): int {
                    return a + b;
                  }
                end

                define module app
                  pub fnc run(): int {
                    return add(19, 23);
                  }
                end
                """;

        assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(code)));
    }

    @Test
    void ownershipPassIndependentlyDoesNotResolveForeignUniqueFunction() {
        Ast.Program program = Parser.parse("""
                define module math
                  pub fnc add(int a, int b): int {
                    return a + b;
                  }
                end

                define module app
                  pub fnc run(): int {
                    return add(19, 23);
                  }
                end
                """);

        // Ownership analysis is intentionally tolerant of unknown global names
        // as values, but it must not falsely bind this call to math.add merely
        // because that spelling is unique in the compilation unit.
        // TypeChecker remains the authoritative unknown-name rejection.
        try {
            OwnershipChecker.check(program);
        } catch (IllegalArgumentException failure) {
            assertTrue(
                    !failure.getMessage().contains("math.add"),
                    failure.getMessage());
        }
    }

    private static String checkedExecution(String code) throws Exception {
        Ast.Program parsed = Parser.parse(code);
        Ast.Program checked = TypeChecker.check(parsed);
        OwnershipChecker.check(checked);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(Source.newBuilder(
                            OresLanguage.ID,
                            code,
                            "module-lexical-function-resolution.ores")
                    .mimeType(OresLanguage.MIME_TYPE)
                    .build());
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
