package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class MinimalNominalEnumGuestTest {
    private static final String ERRORS = """
            define enum TrainingError as
                INVALID_INPUT = 1
                STALE_GRAPH = 2
            end
            """;

    @Test void executesClosedNominalResultAndPreservesNegativeSuccess() throws Exception {
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
        Source source = Source.newBuilder(
                        OresLanguage.ID,
                        program,
                        "minimal-nominal-enum.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            assertDoesNotThrow(() -> context.eval(source));
        }
        assertEquals(
                "-8\n-10",
                output.toString(StandardCharsets.UTF_8)
                        .strip()
                        .replace("\r\n", "\n"));
    }

    @Test void payloadEnumConstructsAndBindsOnlyItsDeclaredPayload() throws Exception {
        String program = """
                define enum DetailError as
                    CODE: int = 1
                    END = 2
                end

                fnc decode(DetailError error): int {
                    match error over
                        on DetailError.CODE(code) -> { return code; }
                        on DetailError.END -> { return 0; }
                    end
                }

                pub fnc main(): void {
                    stdio.println(decode(DetailError.CODE(9)));
                    return;
                }
                """;

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(program)));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(Source.newBuilder(
                            OresLanguage.ID,
                            program,
                            "minimal-nominal-enum-payload.ores")
                    .mimeType(OresLanguage.MIME_TYPE)
                    .build());
        }
        assertEquals("9", output.toString(StandardCharsets.UTF_8).strip());
    }

    @Test void sameTagAndCaseNameFromForeignEnumRemainNominallyDistinct() {
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
        assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(program)));
    }

    @Test void wrongPayloadAndStringMasqueradingAreRejected() {
        String wrongPayload = """
                define enum DetailError as
                    CODE: int = 1
                end

                fnc wrong(): DetailError {
                    return DetailError.CODE("nine");
                }
                """;
        assertDoesNotThrow(() -> Parser.parse(wrongPayload));
        assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(wrongPayload)));

        String stringError = ERRORS + """
                fnc wrong(): Result<int, TrainingError> {
                    return Err("INVALID_INPUT");
                }
                """;
        assertDoesNotThrow(() -> Parser.parse(stringError));
        assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(stringError)));
    }

    @Test void matchOverNominalEnumMustCoverEveryDeclaredCase() {
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
        assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(program)));
    }

    @Test void enumNamespaceCannotBeLexicallyShadowed() {
        String program = ERRORS + """
                fnc wrong(int TrainingError): int {
                    return TrainingError.STALE_GRAPH();
                }
                """;

        assertDoesNotThrow(() -> Parser.parse(program));
        assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(program)));
    }

    @Test void tagsArePositiveUniqueAndMonotonic() {
        assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        define enum Bad as
                            A = 2
                            B = 1
                        end
                        """));
        assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        define enum Bad as
                            A = 1
                            B = 1
                        end
                        """));
        assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        define enum Bad as
                            A = 0
                        end
                        """));
    }
}
