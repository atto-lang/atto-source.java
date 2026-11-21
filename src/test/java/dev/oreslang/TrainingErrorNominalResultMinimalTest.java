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

final class TrainingErrorNominalResultMinimalTest {
    private static final String ERRORS = """
            define enum TrainingError as
                INVALID_INPUT = 1
                STALE_GRAPH = 2
            end
            """;

    private static void verify(String source) {
        Ast.Program program = Parser.parse(source);
        ActorStateContract.verify(program);
        DeclaredEffectVerifier.verify(program);
        TypeChecker.checkTypes(program);
        OwnershipChecker.check(program);
    }

    @Test void closedNominalResultExecutesAndKeepsNegativeSuccessDistinct() throws Exception {
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
                    "training-error-minimal.ores")
                    .mimeType(OresLanguage.MIME_TYPE)
                    .build();
            assertDoesNotThrow(() -> context.eval(executable));
        }
        assertEquals("-8\n-10\n", output.toString(StandardCharsets.UTF_8));
    }

    @Test void sameCaseNameAndTagDoNotCreateNominalCompatibility() {
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

    @Test void rawStringCannotMasqueradeAsNominalError() {
        String program = ERRORS + """
                fnc wrong(): Result<int, TrainingError> {
                    return Err("INVALID_INPUT");
                }
                """;
        assertDoesNotThrow(() -> Parser.parse(program));
        assertThrows(IllegalArgumentException.class, () -> verify(program));
    }

    @Test void missingEnumCaseIsNonExhaustiveInsideCompleteResultMatch() {
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
        assertThrows(IllegalArgumentException.class, () -> verify(program));
    }

    @Test void enumNamespaceCannotBeSpoofedByLocalBinding() {
        String program = ERRORS + """
                fnc bad(int TrainingError): int {
                    return TrainingError;
                }
                """;
        assertDoesNotThrow(() -> Parser.parse(program));
        assertThrows(IllegalArgumentException.class, () -> verify(program));
    }

    @Test void payloadCaseChecksTypeStaticallyAndAtRuntimeContract() {
        String good = """
                define enum ErrorWithCode as
                    CODE: int = 1
                end
                pub routine main(): void {
                    val ErrorWithCode reason = ErrorWithCode.CODE(7);
                    match reason over
                        on ErrorWithCode.CODE(value) -> { stdio.println(value); }
                    end
                    return;
                }
                """;
        assertDoesNotThrow(() -> verify(good));

        String bad = """
                define enum ErrorWithCode as
                    CODE: int = 1
                end
                fnc bad(): ErrorWithCode {
                    return ErrorWithCode.CODE("seven");
                }
                """;
        assertDoesNotThrow(() -> Parser.parse(bad));
        assertThrows(IllegalArgumentException.class, () -> verify(bad));
    }
}
