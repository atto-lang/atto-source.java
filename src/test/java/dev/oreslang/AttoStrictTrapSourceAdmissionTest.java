package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Strict source-only admission. This deliberately does NOT prove typed
 * throw-to-Result guest execution; Java has no such lowering yet.
 */
final class AttoStrictTrapSourceAdmissionTest {
    private static void strictRejects(String source) {
        IllegalArgumentException parserFailure = assertThrows(IllegalArgumentException.class,
                () -> Parser.parseStrictAtto(source));
        assertTrue(parserFailure.getMessage().contains("ATTO_STRICT_TYPED_TRAP_UNSUPPORTED"),
                parserFailure::getMessage);
        IllegalArgumentException compilerFailure = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheckStrictAtto(source));
        assertTrue(compilerFailure.getMessage().contains("ATTO_STRICT_TYPED_TRAP_UNSUPPORTED"),
                compilerFailure::getMessage);
    }

    @Test
    void strictTrapExpressionCannotReuseLegacyHostExceptionTranslation() {
        strictRejects("""
                fnc read_id(): int { return 42; }
                pub fnc main(): int {
                    val result: Result<int, Error> = trap read_id();
                    return 0;
                }
                """);
    }

    @Test
    void strictTrapCallableCannotPublishSanitizedHostErrorAsTypedGuestError() {
        strictRejects("""
                trap fnc read_id(): int { return 42; }
                pub fnc main(): int { return 0; }
                """);
    }

    @Test
    void strictSourceThrowRequiresRealCheckedErrorAndControlLowering() {
        strictRejects("""
                fnc read_id(): int throws Error {
                    throw Error("denied");
                }
                pub fnc main(): int { return 0; }
                """);
    }

    @Test
    void strictLexicalPreflightDoesNotRejectKeywordTextInsideStringOrComment() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheckStrictAtto("""
                // trap and throw in comments are not effects
                pub fnc main(): int {
                    val string label = "trap throw";
                    return 42;
                }
                """));
    }

    @Test
    void explicitNonStrictLegacyTrapIsNotSilentlyReinterpreted() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck("""
                trap fnc legacy_value(): int { return 42; }
                pub fnc main(): void {
                    val Result<int, Error> outcome = legacy_value();
                    return;
                }
                """));
    }
}
