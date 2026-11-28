package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Strict canonical return annotations are not inferred as void. */
final class AttoCanonicalCallableReturnAnnotationTest {
    private static void rejectsMissingReturn(String source) {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> Parser.parseStrictAtto(source));
        assertTrue(error.getMessage().contains("E_RETURN_ANNOTATION"),
                error::getMessage);
    }

    @Test
    void canonicalNamedFunctionsAndRoutinesRejectOmittedReturns() {
        rejectsMissingReturn("fnc absent() { return; }");
        rejectsMissingReturn("routine absent() { return; }");
        rejectsMissingReturn("pub fnc absent(int input) { return; }");
    }

    @Test
    void canonicalClassMethodsAndStaticCallablesRejectOmittedReturns() {
        rejectsMissingReturn("""
                define class C as
                  run() { return; }
                end
                """);
        rejectsMissingReturn("""
                define class C as
                  static fnc run() { return; }
                end
                """);
    }

    @Test
    void canonicalNamedLambdaStyleCallableCannotInferVoidSilently() {
        rejectsMissingReturn("fnc absent = || -> { return; }");
    }

    @Test
    void interfaceSignatureMustAlsoDeclareItsResult() {
        rejectsMissingReturn("""
                define interface Api {
                  fnc read();
                }
                """);
        assertDoesNotThrow(() -> Parser.parseStrictAtto("""
                define interface Api {
                  fnc read() => int;
                }
                """));
    }

    @Test
    void explicitlyTypedCallableFormsRemainAccepted() {
        for (String source : new String[] {
                "fnc empty(): void { return; }",
                "fnc answer() -> int { return 42; }",
                "routine answer() -> int { return 42; }",
                """
                define class C as
                  run(): void { return; }
                  static fnc answer(): int { return 42; }
                end
                """
        }) {
            assertDoesNotThrow(() -> Parser.parseStrictAtto(source), source);
        }
    }

    @Test
    void returnFirstPrefixIsAlreadyAnExplicitResult() {
        Ast.FunctionDecl result = assertInstanceOf(Ast.FunctionDecl.class,
                Parser.parseStrictAtto("""
                        int <- answer()
                        begin
                          return 42;
                        end
                        """).modules().getFirst().declarations().getFirst());
        assertEquals("int", result.returnType().name());
    }

    @Test
    void explicitInferIsRecordedWithoutPretendingRuntimeInferenceIsVerified() {
        Ast.FunctionDecl result = assertInstanceOf(Ast.FunctionDecl.class,
                Parser.parseStrictAtto("fnc answer() -> infer { return 42; }")
                        .modules().getFirst().declarations().getFirst());
        assertEquals("infer", result.returnType().name());
    }

    @Test
    void explicitlyVersionedAttoRejectsOmittedReturnAnnotation() {
        for (int version : new int[] {1, 2}) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> Parser.parseVersionedAtto(
                            "fnc missing() { return; }", version));
            assertTrue(error.getMessage().contains("E_RETURN_ANNOTATION"));
            assertDoesNotThrow(() -> Parser.parseVersionedAtto(
                    "fnc declared(): void { return; }", version));
        }
    }

    @Test
    void strictCheckerRejectsExplicitInferWithoutProofInsteadOfTreatingItAsNominal() {
        for (String source : new String[] {
                "fnc guessed() -> infer { return 42; }",
                """
                define class C as
                  guessed() -> infer { return 42; }
                end
                """,
                """
                define interface Guess {
                  fnc guessed() => infer;
                }
                """
        }) {
            // The grammar accepts the intentional opt-in, but the Java backend
            // must not select a guest ABI until return inference is proved.
            assertDoesNotThrow(() -> Parser.parseStrictAtto(source), source);
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> dev.oreslang.compiler.OresCompiler.parseAndTypeCheckStrictAtto(source),
                    source);
            assertTrue(error.getMessage().contains("E_RETURN_INFER"),
                    error::getMessage);
        }
    }

    @Test
    void directStrictTypecheckerCannotBypassInferenceGateWithPrebuiltAst() {
        Ast.Program program = Parser.parseStrictAtto(
                "fnc guessed() -> infer { return 42; }");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> dev.oreslang.types.TypeChecker.checkTypesWithDiagnostics(program, true));
        assertTrue(error.getMessage().contains("E_RETURN_INFER"), error::getMessage);
    }

    @Test
    void legacyUnversionedParserStillAllowsOmittedVoid() {
        Ast.FunctionDecl result = assertInstanceOf(Ast.FunctionDecl.class,
                Parser.parse("fnc legacy() { return; }")
                        .modules().getFirst().declarations().getFirst());
        assertEquals("void", result.returnType().name());
    }
}
