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

/** Regression tests for postfix Option sugar in every type-reference grammar position. */
final class OptionTypeSuffixAllPositionsTest {
    @Test
    void typeSuffixIsNotRestrictedToFormalParameters() {
        Ast.Program program = Parser.parse("""
                type MaybeInt = int?;

                define class Box as
                    string? label: None
                end

                fnc identity(Option<int>? nested): Option<int>? {
                    return nested;
                }

                fnc result(): int? {
                    return None;
                }

                pub routine main(): void {
                    const int? local = result();
                    stdio.stdout.write(local.unwrap_or(0));
                    return;
                }
                """);
        assertTrue(program.modules().stream().anyMatch(m -> !m.declarations().isEmpty()));
    }

    @Test
    void returnAndLocalTypeSuffixCompileAndExecuteAsOption() throws Exception {
        String source = """
                fnc result(): int? {
                    return Some(42);
                }

                pub routine main(): void {
                    const int? value = result();
                    stdio.stdout.write(value.unwrap_or(0));
                    return;
                }
                """;
        assertEquals("42", checkedExecution(source));
    }

    @Test
    void typeSuffixDoesNotConsumeExpressionPropagationOrTernaryQuestionMarks()
            throws Exception {
        String source = """
                fnc propagate(int? value): int? {
                    return value?;
                }

                fnc choose(bool yes, int value): int {
                    return yes ? value : 0;
                }

                pub routine main(): void {
                    stdio.stdout.write(choose(true, propagate(Some(42)).unwrap_or(0)));
                    return;
                }
                """;
        assertEquals("42", checkedExecution(source));
    }

    @Test
    void doubledTypeSuffixIsRejectedInsteadOfBecomingImplicitNestedOption() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        fnc invalid(): int?? {
                            return 1;
                        }
                        """));
        assertTrue(failure.getMessage().contains("?")
                || failure.getMessage().contains("type"), failure.getMessage());
    }

    private static String checkedExecution(String source) throws Exception {
        Ast.Program parsed = Parser.parse(source);
        var typed = TypeChecker.check(parsed);
        OwnershipChecker.check(typed);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(Source.newBuilder(
                            OresLanguage.ID,
                            source,
                            "option-type-suffix-all-positions.atto")
                    .mimeType(OresLanguage.MIME_TYPE)
                    .build());
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
