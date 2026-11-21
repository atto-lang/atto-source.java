package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.ActorStateContract;
import dev.oreslang.types.DeclaredEffectVerifier;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class TrainingErrorNominalMinSliceTest {
    private static void verify(String source) {
        Ast.Program program = Parser.parse(source);
        ActorStateContract.verify(program);
        DeclaredEffectVerifier.verify(program);
        TypeChecker.checkTypes(program);
        OwnershipChecker.check(program);
    }

    @Test void nominalResultRoundTripAndExhaustiveEnumMatch() throws Exception {
        String program = """
                define enum TrainingError as
                    INVALID_INPUT = 1
                    STALE_GRAPH = 2
                end
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
                .allowAllAccess(false).out(output).build()) {
            Source executable = Source.newBuilder(
                    OresLanguage.ID, program, "training-error-minslice.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build();
            assertDoesNotThrow(() -> context.eval(executable));
        }
        assertEquals("-8\n-10\n", output.toString(StandardCharsets.UTF_8));
    }

    @Test void onePayloadEnumConstructorAndPatternAreTypedNominally() throws Exception {
        String program = """
                define enum ParseError as
                    CODE: int = 1
                    TEXT: String = 2
                end
                fnc render(ParseError error): int {
                    match error over
                        on ParseError.CODE(value) -> { return value; }
                        on ParseError.TEXT(message) -> { return 99; }
                    end
                }
                pub routine main(): void {
                    stdio.println(render(ParseError.CODE(7)));
                    return;
                }
                """;
        assertDoesNotThrow(() -> verify(program));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            Source executable = Source.newBuilder(
                    OresLanguage.ID, program, "enum-payload-minslice.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build();
            assertDoesNotThrow(() -> context.eval(executable));
        }
        assertEquals("7\n", output.toString(StandardCharsets.UTF_8));

        String wrongPayload = """
                define enum ParseError as
                    CODE: int = 1
                end
                fnc wrong(): ParseError {
                    return ParseError.CODE("seven");
                }
                """;
        assertDoesNotThrow(() -> Parser.parse(wrongPayload));
        assertThrows(IllegalArgumentException.class, () -> verify(wrongPayload));
    }

    @Test void foreignNominalEnumWithSameCaseAndTagIsRejected() {
        String program = """
                define enum TrainingError as
                    INVALID_INPUT = 1
                end
                define enum ForeignTrainingError as
                    INVALID_INPUT = 1
                end
                fnc wrong(): Result<int, TrainingError> {
                    return Err(ForeignTrainingError.INVALID_INPUT());
                }
                """;
        assertDoesNotThrow(() -> Parser.parse(program));
        assertThrows(IllegalArgumentException.class, () -> verify(program));
    }

    @Test void stringCannotMasqueradeAsNominalEnum() {
        String program = """
                define enum TrainingError as
                    INVALID_INPUT = 1
                end
                fnc wrong(): Result<int, TrainingError> {
                    return Err("INVALID_INPUT");
                }
                """;
        assertDoesNotThrow(() -> Parser.parse(program));
        assertThrows(IllegalArgumentException.class, () -> verify(program));
    }

    @Test void missingEnumCaseIsRejectedAsNonExhaustive() {
        String program = """
                define enum TrainingError as
                    INVALID_INPUT = 1
                    STALE_GRAPH = 2
                end
                fnc wrong(TrainingError problem): int {
                    match problem over
                        on TrainingError.INVALID_INPUT -> { return -7; }
                    end
                }
                """;
        assertDoesNotThrow(() -> Parser.parse(program));
        assertThrows(IllegalArgumentException.class, () -> verify(program));
    }

    @Test void foreignEnumCaseCannotMatchSubject() {
        String program = """
                define enum TrainingError as
                    INVALID_INPUT = 1
                end
                define enum ForeignTrainingError as
                    INVALID_INPUT = 1
                end
                fnc wrong(TrainingError problem): int {
                    match problem over
                        on ForeignTrainingError.INVALID_INPUT -> { return 1; }
                        on TrainingError.INVALID_INPUT -> { return 2; }
                    end
                }
                """;
        assertDoesNotThrow(() -> Parser.parse(program));
        assertThrows(IllegalArgumentException.class, () -> verify(program));
    }
}
