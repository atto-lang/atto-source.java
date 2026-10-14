package dev.oreslang;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Both bare and explicitly prefixed source dispatch statements are valid.
 * 'do' is an optional procedural spelling for match and switch, while
 * select keeps its existing explicit-do arm-return behavior.
 *
 * This must not accidentally make legacy exclusive matches ordered:
 * run the separate ExclusiveAdtMatchSafetyTest to verify their semantics.
 */
final class ExplicitDispatchStatementSyntaxTest {
    @Test
    void bothMatchStatementSpellingsParse() {
        for (String prefix : new String[]{"", "do "}) {
            assertDoesNotThrow(() -> Parser.parse("""
                    fnc classify(int n): int {
                        %smatch n over
                            on 1 -> { return 1; }
                            on _ -> { return 2; }
                        end
                    }
                    """.formatted(prefix)), "ordered " + prefix + "match");

            assertDoesNotThrow(() -> Parser.parse("""
                    fnc classify(int n): int {
                        %smatch n
                            1 -> { return 1; }
                            else -> { return 2; }
                        end
                    }
                    """.formatted(prefix)), "exclusive " + prefix + "match");
        }
    }

    @Test
    void bothSwitchStatementSpellingsParse() {
        for (String prefix : new String[]{"", "do "}) {
            assertDoesNotThrow(() -> Parser.parse("""
                    fnc classify(int n): int {
                        %sswitch n
                            case 1 -> { return 1; }
                            default -> { return 2; }
                        end
                    }
                    """.formatted(prefix)), prefix + "switch");
        }
    }

    @Test
    void bothStaticSelectStatementSpellingsParse() {
        for (String prefix : new String[]{"", "do "}) {
            assertDoesNotThrow(() -> Parser.parse("""
                    fnc test(): void {
                        %sselect {
                            default: { return; }
                        }
                    }
                    """.formatted(prefix)), prefix + "select");
        }
    }

    @Test
    void malformedDispatchFormsStillFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc classify(int n): int {
                    do match n over
                        1 -> { return 1; }
                    end
                }
                """), "ordered match requires on");

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc test(): void {
                    cb select { default: { return; } }
                }
                """), "cb select is not do select");

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc test(): void {
                    do select from cases;
                }
                """), "dynamic select is an expression");
    }
}
