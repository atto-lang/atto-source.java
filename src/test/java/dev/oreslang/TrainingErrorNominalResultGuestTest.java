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

/**
 * Minimal nominal enum/exhaustive-match prerequisite for typed ML actor errors.
 * This deliberately excludes #623's actor-wire and pattern-spread subsystems.
 */
final class TrainingErrorNominalResultGuestTest {
    private static void verify(String source) {
        Ast.Program program = Parser.parse(source);
        ActorStateContract.verify(program);
        DeclaredEffectVerifier.verify(program);
        TypeChecker.checkTypes(program);
        OwnershipChecker.check(program);
    }

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
        assertDoesNotThrow(() -> verify(program));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(
                        OresLanguage.ID,
                        program,
                        "ml-nominal-result-enum-source.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            assertDoesNotThrow(() -> context.eval(source));
        }
        assertEquals("-8\n-10", output.toString(StandardCharsets.UTF_8)
                .strip().replace("\r\n", "\n"));
    }

    @Test void sameTagAndCaseNameDoNotPermitForeignNominalError() {
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

    @Test void missingNominalCaseIsNonExhaustive() {
        String program = ERRORS + """
                fnc incomplete(TrainingError value): int {
                    match value over
                        on TrainingError.INVALID_INPUT -> { return -7; }
                    end
                }
                """;
        assertDoesNotThrow(() -> Parser.parse(program));
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(program)));
    }

    @Test void payloadCaseBindsOneTypedValue() throws Exception {
        String program = """
                define enum ErrorWithCode as
                    NONE = 1
                    CODE: int = 2
                end
                fnc render(ErrorWithCode value): int {
                    match value over
                        on ErrorWithCode.NONE -> { return 0; }
                        on ErrorWithCode.CODE(code) -> { return code; }
                    end
                }
                pub fnc main(): void {
                    stdio.println(render(ErrorWithCode.CODE(9)));
                    return;
                }
                """;
        assertDoesNotThrow(() -> verify(program));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            Source source = Source.newBuilder(
                            OresLanguage.ID,
                            program,
                            "nominal-enum-payload.ores")
                    .mimeType(OresLanguage.MIME_TYPE)
                    .build();
            assertDoesNotThrow(() -> context.eval(source));
        }
        assertEquals("9", output.toString(StandardCharsets.UTF_8).strip());
    }

    @Test void payloadArityAndTypeAreChecked() {
        String wrongArity = """
                define enum E as
                    CODE: int = 1
                end
                fnc wrong(): E {
                    return E.CODE();
                }
                """;
        assertDoesNotThrow(() -> Parser.parse(wrongArity));
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(wrongArity)));

        String wrongType = """
                define enum E as
                    CODE: int = 1
                end
                fnc wrong(): E {
                    return E.CODE("bad");
                }
                """;
        assertDoesNotThrow(() -> Parser.parse(wrongType));
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(wrongType)));
    }

    @Test void parserRejectsDuplicateCasesTagsAndNonMonotonicTags() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define enum E as
                    A = 1
                    A = 2
                end
                """));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define enum E as
                    A = 1
                    B = 1
                end
                """));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define enum E as
                    A = 2
                    B = 1
                end
                """));
    }
}
