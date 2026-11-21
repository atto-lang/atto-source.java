package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.DeclaredEffectVerifier;
import dev.oreslang.types.OwnershipChecker;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Synchronous console output can wait for a slow pipe/terminal, regardless
 * of whether its syntax is labeled as io rather than block.
 */
final class CriticalConsoleAndNoBlockAuditTest {
    private static void rejectEffect(String source, String diagnostic) {
        var program = assertDoesNotThrow(() -> Parser.parse(source));
        var failure = assertThrows(IllegalArgumentException.class,
                () -> DeclaredEffectVerifier.verify(program));
        assertTrue(failure.getMessage().contains(diagnostic), failure.getMessage());
    }

    @Test void noBlockCannotAuthorizeConsoleOutput() {
        rejectEffect("fnc bad(): void requires no_block { stdio.println(1); return; }",
                "no_block");
        rejectEffect("fnc bad(): void requires no_block { stdio.print(1); return; }",
                "no_block");
        rejectEffect("fnc bad(): void with io { stdio.println(1); return; }",
                "effect block");
    }

    @Test void explicitIoAndBlockRowCanAdmitConsoleOutput() {
        var program = assertDoesNotThrow(() -> Parser.parse(
                "fnc good(): void with io, block { stdio.println(1); return; }"));
        assertDoesNotThrow(() -> DeclaredEffectVerifier.verify(program));
    }

    @Test void criticalStateLoanCannotPerformSynchronousConsoleOutput() {
        for (String statement : new String[]{
                "stdio.println(s.count);", "stdio.print(s.count);",
                "stdio.stdout.write(s.count);", "stdio.stdout.log(s.count);"
        }) {
            var program = assertDoesNotThrow(() -> Parser.parse("""
                    define actor Counter as
                      state { count: int = 0; }
                      pub probe(): void {
                        self.handle_state(critical write |s| -> {
                    """ + statement + """
                    
                          s.count = 1;
                        });
                        return;
                      }
                    end
                    """), statement);
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> OwnershipChecker.check(program), statement);
            assertTrue(failure.getMessage().contains("critical actor state loan"),
                    failure.getMessage());
        }
    }

    @Test void consoleOutputAfterStateLoanExitsIsAllowed() {
        var program = assertDoesNotThrow(() -> Parser.parse("""
                define actor Counter as
                  state { count: int = 0; }
                  pub probe(): void {
                    self.handle_state(critical write |s| -> { s.count = 1; });
                    stdio.println(1);
                    return;
                  }
                end
                """));
        assertDoesNotThrow(() -> OwnershipChecker.check(program));
    }
}
