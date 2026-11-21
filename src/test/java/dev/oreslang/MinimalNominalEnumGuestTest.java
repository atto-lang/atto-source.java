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

                fnc make(): DetailError {
                    return DetailError.CODE(9);
                }

                fnc decode(DetailError error): int {
                    match error over
                        on DetailError.CODE(code) -> { return code; }
                        on DetailError.END -> { return 0; }
                    end
                }

                pub fnc main(): void {
                    stdio.println(decode(make()));
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

    @Test void qualifiedBareCaseDoesNotDependOnEnumCapitalization() {
        String program = """
                define enum training_error as
                    STALE = 1
                    INVALID = 2
                end

                fnc render(training_error value): int {
                    match value over
                        on training_error.STALE -> { return 1; }
                        on training_error.INVALID -> { return 2; }
                    end
                }
                """;

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(program)));
    }

    @Test void nominalEnumsUseValueEqualityNotReferenceIdentity() {
        String valueEquality = ERRORS + """
                fnc same(): bool {
                    return TrainingError.STALE_GRAPH()
                        == TrainingError.STALE_GRAPH();
                }
                """;
        assertDoesNotThrow(
                () -> TypeChecker.check(Parser.parse(valueEquality)));

        String identity = ERRORS + """
                fnc wrong(): bool {
                    return TrainingError.STALE_GRAPH()
                        is TrainingError.STALE_GRAPH();
                }
                """;
        assertDoesNotThrow(() -> Parser.parse(identity));
        assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(identity)));
    }

    @Test void actorCannotUseAmbientSameFileNominalEnum() {
        String program = """
                define enum TrainingError as
                    STALE = 1
                end

                define actor Bad as
                    state { version: int = 1; }

                    private bad(): TrainingError {
                        return TrainingError.STALE();
                    }
                end
                """;

        assertDoesNotThrow(() -> Parser.parse(program));
        assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(program)));
    }

    @Test void recursiveNominalEnumPayloadCyclesAreRejected() {
        String direct = """
                define enum Recursive as
                    NEXT: Recursive = 1
                end
                """;
        assertDoesNotThrow(() -> Parser.parse(direct));
        assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(direct)));

        String indirect = """
                define enum Left as
                    RIGHT: Right = 1
                end
                define enum Right as
                    LEFT: Left = 1
                end
                """;
        assertDoesNotThrow(() -> Parser.parse(indirect));
        assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(indirect)));
    }

    @Test void guardedOrPartialPayloadCasesDoNotFakeExhaustiveness() {
        String guarded = ERRORS + """
                fnc incomplete(TrainingError value): int {
                    match value over
                        on TrainingError.INVALID_INPUT when true -> { return 1; }
                        on TrainingError.STALE_GRAPH -> { return 2; }
                    end
                }
                """;
        assertDoesNotThrow(() -> Parser.parse(guarded));
        assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(guarded)));

        String partialPayload = """
                define enum DetailError as
                    CODE: int = 1
                    END = 2
                end
                fnc incomplete(DetailError value): int {
                    match value over
                        on DetailError.CODE(7) -> { return 7; }
                        on DetailError.END -> { return 0; }
                    end
                }
                """;
        assertDoesNotThrow(() -> Parser.parse(partialPayload));
        assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(partialPayload)));
    }

    @Test void wildcardCanCloseEnumMatchButDuplicateCaseIsUnreachable() {
        String wildcard = ERRORS + """
                fnc render(TrainingError value): int {
                    match value over
                        on TrainingError.INVALID_INPUT -> { return 1; }
                        on _ -> { return 2; }
                    end
                }
                """;
        assertDoesNotThrow(
                () -> TypeChecker.check(Parser.parse(wildcard)));

        String duplicate = ERRORS + """
                fnc bad(TrainingError value): int {
                    match value over
                        on TrainingError.INVALID_INPUT -> { return 1; }
                        on TrainingError.INVALID_INPUT -> { return 2; }
                        on TrainingError.STALE_GRAPH -> { return 3; }
                    end
                }
                """;
        assertDoesNotThrow(() -> Parser.parse(duplicate));
        assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(duplicate)));
    }

    @Test void enumConstructorAndPatternArityAreExact() {
        String extraArgument = ERRORS + """
                fnc bad(): TrainingError {
                    return TrainingError.INVALID_INPUT(1);
                }
                """;
        assertDoesNotThrow(() -> Parser.parse(extraArgument));
        assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(extraArgument)));

        String missingArgument = """
                define enum DetailError as
                    CODE: int = 1
                end
                fnc bad(): DetailError {
                    return DetailError.CODE();
                }
                """;
        assertDoesNotThrow(() -> Parser.parse(missingArgument));
        assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(missingArgument)));

        String patternMissingArgument = """
                define enum DetailError as
                    CODE: int = 1
                end
                fnc bad(DetailError value): int {
                    match value over
                        on DetailError.CODE -> { return 1; }
                    end
                }
                """;
        assertDoesNotThrow(() -> Parser.parse(patternMissingArgument));
        assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(patternMissingArgument)));
    }

    @Test void foreignEnumCaseCannotAppearInNominalMatch() {
        String program = ERRORS + """
                define enum ForeignTrainingError as
                    INVALID_INPUT = 1
                    STALE_GRAPH = 2
                end
                fnc bad(TrainingError value): int {
                    match value over
                        on ForeignTrainingError.INVALID_INPUT -> { return 1; }
                        on TrainingError.STALE_GRAPH -> { return 2; }
                    end
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
