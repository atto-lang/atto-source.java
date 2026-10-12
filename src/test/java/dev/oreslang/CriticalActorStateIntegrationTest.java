package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Combined-source admission: logical critical scopes, actor state schema,
 * and lexical borrows must all be validated on the same AST.
 *
 * These are compiler tests, not a claim of runnable guest handle_state/FFM
 * source lowering.
 */
final class CriticalActorStateIntegrationTest {
    @Test void criticalAndSuspendCriticalAcceptScalarActorStateCallbacks() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck("""
                define actor Counter as
                  state { count: int = 0; }
                  critical increment(): void using state.read, state.write {
                    self.handle_state(|s| -> {
                      s.count = s.count + 1;
                    });
                    return;
                  }
                  suspend critical increment_later(): void using state.read, state.write {
                    self.handle_state(|s| -> {
                      s.count = s.count + 1;
                    });
                    return;
                  }
                end
                """));
    }

    @Test void suspendCriticalCannotRetainStateLoanAcrossAwait() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                    async fnc obtain(): int { return 3; }
                    define actor Counter as
                      state { count: int = 0; }
                      suspend critical unsafe(): void {
                        self.handle_state(|s| -> {
                          s.count = s.count + 1;
                          await obtain();
                        });
                        return;
                      }
                    end
                    """));
        assertTrue(error.getMessage().contains("state access")
                        || error.getMessage().contains("suspension"),
                error::getMessage);
    }

    @Test void criticalCannotLeakMapReferenceFromStateCallback() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                    define actor Cache as
                      state { values: Map<string, int>; }
                      critical unsafe(): void {
                        self.handle_state(|s| -> { s.values; });
                        return;
                      }
                    end
                    """));
        assertTrue(error.getMessage().contains("non-scalar actor state key"),
                error::getMessage);
    }

    @Test void criticalDoesNotAuthorizeDirectStateAccess() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                    define actor Counter as
                      state { count: int = 0; }
                      critical unsafe(): void {
                        self.state.count = 9;
                        return;
                      }
                    end
                    """));
        assertTrue(error.getMessage().contains("state"), error::getMessage);
    }
}
