package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Compatibility gate while safe-collection-access-v1 is a candidate.
 *
 * <p>These tests deliberately DO NOT prove Option or ReadLoan lowering.
 * They pin the current direct-value Array.get contract and ensure a
 * half-implemented get_copy name is not admitted as a guest member.
 * Replace this gate only alongside a reviewed source-language migration
 * with real Option tag, rooted loan, and executable guest evidence.
 */
final class CollectionOptionMigrationAdmissionTest {
    @Test
    void existingArrayGetRemainsDirectScalarUntilMigrated() {
        Ast.Program program = Parser.parse("""
                pub fnc main(): int {
                  let mut Array<int> values = new Array<int>();
                  values.add(42);
                  return values.get(0);
                }
                """);
        assertDoesNotThrow(() -> TypeChecker.check(program));
    }

    @Test
    void candidateGetCopyCannotBeAdmittedByParserAlone() {
        // Parsing a member spelling is not proof of typed API admission.
        Ast.Program program = Parser.parse("""
                pub fnc main(): int {
                  let mut Array<int> values = new Array<int>();
                  values.add(42);
                  return values.get_copy(0);
                }
                """);
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(program));
    }
}
