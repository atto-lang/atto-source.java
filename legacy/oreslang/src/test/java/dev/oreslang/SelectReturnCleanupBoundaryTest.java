package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.ReturnCleanupChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Return cleanup must distinguish caller returns from detached select arms. */
final class SelectReturnCleanupBoundaryTest {
    @Test
    void proceduralDoSelectReturnRemainsSubjectToCallerDeferGuard() {
        String source = """
                fnc guarded(): int {
                  let int value = 1;
                  defer value = 2;
                  do select {
                    default: {
                      return value;
                    }
                  }
                  return 3;
                }
                """;
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> ReturnCleanupChecker.check(Parser.parse(source)));
        assertTrue(failure.getMessage().contains("defer cannot reassign returned binding"),
                failure::getMessage);
    }

    @Test
    void LegacyDetachedDoNbSelectReturnDoesNotPretendToExitCaller() {
        String source = """
                actor fnc detached(): void {
                  let int value = 1;
                  defer value = 2;
                  do nb select {
                    default: {
                      return value;
                    }
                  }
                  return;
                }
                """;
        assertDoesNotThrow(() -> ReturnCleanupChecker.check(Parser.parse(source)));
    }

    @Test
    void LegacyDetachedNbSelectReturnDoesNotPretendToExitCaller() {
        String source = """
                actor fnc detached(): void {
                  let int value = 1;
                  defer value = 2;
                  nb select {
                    default: {
                      return;
                    }
                  }
                  return;
                }
                """;
        assertDoesNotThrow(() -> ReturnCleanupChecker.check(Parser.parse(source)));
    }
}
