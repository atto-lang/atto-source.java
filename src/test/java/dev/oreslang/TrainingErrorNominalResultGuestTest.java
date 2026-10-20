package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.DeclaredEffectVerifier;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class TrainingErrorNominalResultGuestTest {
    private static final String ERRORS = """
            define enum TrainingError as
                INVALID_INPUT = 1
                STALE_GRAPH = 2
            end
            """;

    private static void verify(String source) {
        Ast.Program program = Parser.parse(source);
        DeclaredEffectVerifier.verify(program);
        TypeChecker.checkTypes(program);
        OwnershipChecker.check(program);
    }

    @Test void nominalResultRoundTripsAndExhaustiveMatchRuns() throws Exception {
        String program = ERRORS + """
                fnc render(Result<int, TrainingError> value): int {
                    match value over
                        on Ok(number) -> { return number; }
                        on Err(problem) -> {
                            match problem over
                                on TrainingError.INVALID_INPUT -> { return -7; }
                                on TrainingError.STALE_GRAPH -> { return -8; }
                            end
                        }
                    end
                }
                pub routine main(): void {
                    val Result<int, TrainingError> rejected =
                        Err(TrainingError.STALE_GRAPH());
                    val Result<int, TrainingError> negative = Ok(-10);
                    stdio.println(render(rejected));
                    stdio.println(render(negative));
                    return;
                }
                """;
        assertDoesNotThrow(() -> verify(program));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            Source executable = Source.newBuilder(
                    OresLanguage.ID,
                    program,
                    "training-error-nominal-result.ores")
                    .mimeType(OresLanguage.MIME_TYPE)
                    .build();
            assertDoesNotThrow(() -> context.eval(executable));
        }
        assertEquals("-8\n-10\n",
                output.toString(StandardCharsets.UTF_8));
    }

    @Test void equalCaseNameAndTagDoNotConferNominalIdentity() {
        String program = ERRORS + """
                define enum ForeignTrainingError as
                    INVALID_INPUT = 1
                    STALE_GRAPH = 2
                end
                fnc wrong(): Result<int, TrainingError> {
                    return Err(ForeignTrainingError.INVALID_INPUT());
                }
                """;
        assertDoesNotThrow(() -> Parser.parse(program));
        assertThrows(IllegalArgumentException.class, () -> verify(program));
    }

    @Test void wrongPayloadAndMissingCaseFailSemantically() {
        String wrongPayload = ERRORS + """
                fnc wrong(): Result<int, TrainingError> {
                    return Err("INVALID_INPUT");
                }
                """;
        assertDoesNotThrow(() -> Parser.parse(wrongPayload));
        assertThrows(IllegalArgumentException.class, () -> verify(wrongPayload));

        String missingCase = ERRORS + """
                fnc incomplete(TrainingError error): int {
                    match error over
                        on TrainingError.INVALID_INPUT -> { return 1; }
                    end
                }
                """;
        assertDoesNotThrow(() -> Parser.parse(missingCase));
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> verify(missingCase));
        assertTrue(failure.getMessage().contains("non-exhaustive match"),
                failure.toString());
    }

    @Test void maximumTagAllowsFinalTrailingSeparatorButNotAnotherCase() {
        for (String separator : new String[] {"", ",", ";"}) {
            String source = "define enum Terminal as\n"
                    + "    Last = 2147483647" + separator + "\n"
                    + "end\n";
            assertDoesNotThrow(() -> Parser.parse(source));
        }
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define enum Terminal as
                    Last = 2147483647;
                    TooLate
                end
                """));
    }

    @Test void enumNamesCannotShadowExistingRuntimeOrTypeDeclarations() {
        String functionCollision = """
                fnc TrainingError(): int { return 1; }
                define enum TrainingError as
                    INVALID_INPUT = 1
                end
                """;
        assertDoesNotThrow(() -> Parser.parse(functionCollision));
        assertThrows(IllegalArgumentException.class,
                () -> verify(functionCollision));

        String typeCollision = """
                define class TrainingError as
                end
                define enum TrainingError as
                    INVALID_INPUT = 1
                end
                """;
        assertDoesNotThrow(() -> Parser.parse(typeCollision));
        assertThrows(IllegalArgumentException.class,
                () -> verify(typeCollision));

        String builtinCollision = """
                define enum Result as
                    OK = 1
                end
                """;
        assertDoesNotThrow(() -> Parser.parse(builtinCollision));
        assertThrows(IllegalArgumentException.class,
                () -> verify(builtinCollision));
    }

    @Test void constructorArityAndForeignMatchDomainFailClosed() {
        String arity = ERRORS + """
                fnc bad(): TrainingError {
                    return TrainingError.INVALID_INPUT(7);
                }
                """;
        assertDoesNotThrow(() -> Parser.parse(arity));
        assertThrows(IllegalArgumentException.class, () -> verify(arity));

        String foreign = ERRORS + """
                define enum ForeignTrainingError as
                    INVALID_INPUT = 1
                end
                fnc bad(TrainingError error): int {
                    match error over
                        on ForeignTrainingError.INVALID_INPUT -> { return 1; }
                        on _ -> { return 0; }
                    end
                }
                """;
        assertDoesNotThrow(() -> Parser.parse(foreign));
        assertThrows(IllegalArgumentException.class, () -> verify(foreign));
    }
}
