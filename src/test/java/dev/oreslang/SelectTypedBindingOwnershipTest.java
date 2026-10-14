package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A typed select binding cannot reclassify a move-only source event as a
 * copyable scalar. This tests OwnershipChecker on its own, not merely after
 * TypeChecker has rejected an incompatible annotation.
 */
final class SelectTypedBindingOwnershipTest {
    @Test
    void fakeFutureResultAnnotationCannotMakeMutexGuardCopyable() {
        Ast.Program program = Parser.parse("""
                fnc bad(Future<MutexGuard<int>> source): void {
                  do select first {
                    when await source: int disguised -> {
                      val copied = rt copy disguised;
                    }
                  }
                  return;
                }
                """);
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> OwnershipChecker.check(program));
        assertTrue(error.getMessage().contains("rt copy")
                        || error.getMessage().contains("move-only"),
                error::getMessage);
    }

    @Test
    void fakeChannelResultAnnotationCannotMakeMutexGuardCopyable() {
        Ast.Program program = Parser.parse("""
                fnc bad(Channel<MutexGuard<int>> source): void {
                  do select first {
                    when readch source: int disguised -> {
                      val copied = rt copy disguised;
                    }
                  }
                  return;
                }
                """);
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> OwnershipChecker.check(program));
        assertTrue(error.getMessage().contains("rt copy")
                        || error.getMessage().contains("move-only"),
                error::getMessage);
    }

    @Test
    void correctlyTypedFutureValueStillAllowsOrdinaryCopy() {
        Ast.Program program = Parser.parse("""
                fnc valid(Future<int> source): void {
                  do select first {
                    when await source: int value -> {
                      val copied = rt copy value;
                    }
                  }
                  return;
                }
                """);
        assertDoesNotThrow(() -> OwnershipChecker.check(program));
    }
}
