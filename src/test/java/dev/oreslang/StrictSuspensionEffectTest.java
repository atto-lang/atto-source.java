package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class StrictSuspensionEffectTest {
    @Test
    void annotatedSyncFunctionCannotAwait() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        async fnc answer(): int { return 1; }
                        @StrictSuspension
                        fnc bad(): int { return await answer(); }
                        """));
        assertTrue(error.getMessage().contains("requires a suspends or async"),
                error::getMessage);
    }

    @Test
    void annotatedSyncFunctionCannotBlockOnReadch() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        @StrictSuspension
                        fnc bad(Channel<int> ch): int {
                            return readch ch;
                        }
                        """));
        assertTrue(error.getMessage().contains("requires a suspends or async"),
                error::getMessage);
    }

    @Test
    void annotatedSyncFunctionCannotUseBlockingSelect() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        @StrictSuspension
                        fnc bad(Channel<int> ch): void {
                            select {
                                when readch ch: { return; }
                            }
                            return;
                        }
                        """));
        assertTrue(error.getMessage().contains("requires a suspends or async"),
                error::getMessage);
    }

    @Test
    void explicitlySuspendingFunctionCanAwaitUnderStrictMode() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck("""
                async fnc answer(): int { return 1; }
                @StrictSuspension
                suspends fnc okay(): int { return await answer(); }
                """));
    }

    @Test
    void plainCallbackCannotInheritEnclosingSuspendsEffectInStrictMode() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        async fnc answer(): int { return 1; }
                        @StrictSuspension
                        suspends fnc register(): void {
                            val cb = |int value| -> {
                                stdio.println(await answer());
                            };
                            return;
                        }
                        """));
        assertTrue(error.getMessage().contains("requires a suspends or async"),
                error::getMessage);
    }

    @Test
    void unannotatedLegacyBodyIsNotSilentlyMigratedToStrictMode() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck("""
                async fnc answer(): int { return 1; }
                fnc legacy(): int { return await answer(); }
                """));
    }
}
