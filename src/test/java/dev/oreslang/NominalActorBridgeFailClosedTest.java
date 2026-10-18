package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Transitional negative compiler gate: the ADT parser/AST is present on this
 * branch but nominal typechecker, match exhaustiveness and runtime lowering
 * are NOT certified. Tests must not accidentally turn into positive guests.
 */
final class NominalActorBridgeFailClosedTest {
    private static final String ENUM = """
            define enum TrainingError as
                INVALID_INPUT = 1
                STALE_GRAPH = 2
            end
            """;

    @Test void parserRecognizesNominalCasesBeforeTheFailClosedSemanticGate() {
        Ast.Program parsed = assertDoesNotThrow(() -> Parser.parse(ENUM));
        assertTrue(parsed.modules().stream()
                .flatMap(m -> m.declarations().stream())
                .anyMatch(d -> d instanceof Ast.EnumDecl e
                        && e.cases().size() == 2));
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> TypeChecker.check(parsed));
        assertTrue(error.getMessage().contains("not yet executable"),
                "Must reject absent enum/Result lowering, not just fail parsing: " + error);
    }

    @Test void enumTagAndCaseCollisionStillRejectBeforeExecutableAdmission() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define enum Duplicate as
                    A = 1
                    A = 2
                end
                """));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define enum DuplicateTag as
                    A = 1
                    B = 1
                end
                """));
    }

    @Test void enumCannotCollideWithOrdinaryTypeOrBuiltinName() {
        Ast.Program parsed = assertDoesNotThrow(() -> Parser.parse("""
                define enum Result as
                    Ready = 1
                end
                """));
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(parsed));
    }

    @Test void ordinaryNonNominalProgramsRemainAdmissible() {
        Ast.Program parsed = Parser.parse("""
                fnc identity(int value): int {
                    return value;
                }
                """);
        assertDoesNotThrow(() -> TypeChecker.check(parsed));
    }
}
