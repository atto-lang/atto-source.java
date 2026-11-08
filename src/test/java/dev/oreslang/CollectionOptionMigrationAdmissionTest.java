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
 * only the first concrete get_copy<int> lane is admitted. Other element
 * types and borrowed get/ReadLoan remain unimplemented and fail closed.
 * A typed regression is not proof of JVM/AOT execution on its own.
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
    void checkedCopyIntIsTypedOptionButOtherElementsRemainUnadmitted() {
        // The new, restricted Copy<int> case is admitted in both compiler
        // passes. This is distinct from a borrowed get/ReadLoan.
        Ast.Program permitted = Parser.parse("""
                pub fnc main(): int {
                  let mut Array<int> values = new Array<int>();
                  values.add(42);
                  return values.get_copy(0).unwrap_or(0);
                }
                """);
        assertDoesNotThrow(() -> TypeChecker.check(permitted));

        Ast.Program denied = Parser.parse("""
                pub fnc main(): bool {
                  let mut Array<String> values = new Array<String>();
                  values.add("secret");
                  return values.get_copy(0).is_some();
                }
                """);
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(denied));
    }
}
