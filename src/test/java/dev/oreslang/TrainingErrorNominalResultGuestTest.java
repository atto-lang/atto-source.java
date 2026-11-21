package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Compiler-owned nominal Result<E> acceptance prerequisite for ML actor
 * TrainingError. This tests the ENUM feature branch directly; it does not
 * certify the separate with/using actor-state compiler branch.
 */
final class TrainingErrorNominalResultGuestTest {
    private static final String ERRORS = """
            define enum TrainingError as
                INVALID_INPUT = 1
                STALE_GRAPH = 2
            end
            """;

    @Test void acceptsClosedNominalResultWithLegitimateNegativeSuccess() throws Exception {
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
                pub fnc main(): void {
                    val Result<int, TrainingError> rejected =
                        Err(TrainingError.STALE_GRAPH());
                    val Result<int, TrainingError> negative = Ok(-10);
                    stdio.println(render(rejected));
                    stdio.println(render(negative));
                    return;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(program)));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program,
                "ml-nominal-result-enum-source.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            assertDoesNotThrow(() -> context.eval(source));
        }
        assertEquals("-8\n-10", output.toString(StandardCharsets.UTF_8)
                .strip().replace("\r\n", "\n"));
    }

    @Test void sameWireTagAndCaseNameDoNotPermitForeignNominalError() {
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
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(program)));
    }

    @Test void stringCannotMasqueradeAsNominalError() {
        String program = ERRORS + """
                fnc wrong(): Result<int, TrainingError> {
                    return Err("INVALID_INPUT");
                }
                """;
        assertDoesNotThrow(() -> Parser.parse(program));
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(program)));
    }

    @Test void missingNominalCaseIsNonExhaustiveEvenWhenResultArmsAreComplete() {
        String program = ERRORS + """
                fnc incomplete(Result<int, TrainingError> value): int {
                    match value over
                        on Ok(number) -> { return number; }
                        on Err(problem) -> {
                            match problem over
                                on TrainingError.INVALID_INPUT -> { return -7; }
                            end
                        }
                    end
                }
                """;
        assertDoesNotThrow(() -> Parser.parse(program));
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(program)));
    }
}
