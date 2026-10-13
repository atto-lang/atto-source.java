package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Admission and execution guard for explicit do/return dispatch.
 * A bare match, switch, or select never implicitly chooses statement mode.
 */
final class DispatchExplicitFormsTest {
    @Test
    void rejectBareStatementsAndResultDiscardingDynamicSelect() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc wrong(Symbol x): int {
                    match x over
                        on #ok -> { return 1; }
                        on _ -> { return 0; }
                    end
                }
                """));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc wrong(int x): int {
                    switch x
                        case 1 -> { return 1; }
                        default -> { return 0; }
                    end
                }
                """));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc wrong(): int {
                    select { default: { return 1; } }
                }
                """));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc wrong(Any cases): void {
                    select from cases;
                    return;
                }
                """));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc wrong(Symbol x): int {
                    return do match x over
                        on _ -> { return 1; }
                    end
                }
                """));
    }

    @Test
    void requireEachValueDispatchArmToProduceValue() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc wrong(Symbol x): int {
                    return match x over
                        on #ok -> { return 1; }
                        on _ -> { stdio.println("oops"); }
                    end
                }
                """));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc wrong(int x): int {
                    return switch x
                        case 1 -> { return 1; }
                        default -> { return; }
                    end
                }
                """));
    }

    @Test
    void proceduralDoDispatchProducesCorrectGuestResults() throws Exception {
        String source = """
                fnc byDoMatch(Symbol s): int {
                    do match s over
                        on #ready -> { return 43; }
                        on _ -> { return 1; }
                    end
                }
                fnc byDoSwitch(int n): int {
                    do switch n
                        case 3 -> { return 45; }
                        default -> { return 3; }
                    end
                }
                fnc byDoSelect(): int {
                    val Channel<int> ch = Channel.new<int>(1);
                    writech ch, 46;
                    do select {
                        when readch ch: val n {
                            return n;
                        }
                    }
                    return 4;
                }
                pub fnc main(): void {
                    stdio.println(byDoMatch(#ready));
                    stdio.println(byDoSwitch(3));
                    stdio.println(byDoSelect());
                    return;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Source guest = Source.newBuilder(OresLanguage.ID, source, "dispatch-forms.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context ctx = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(out).build()) {
            ctx.eval(guest);
        }
        assertEquals("43\n45\n46",
                out.toString(StandardCharsets.UTF_8).trim().replace("\r", ""));
    }
}
