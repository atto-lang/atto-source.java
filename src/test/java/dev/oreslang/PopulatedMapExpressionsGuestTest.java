package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checked nonliteral scalar maps, with ordered guest evaluation and no
 * publication of partially built containers.
 */
final class PopulatedMapExpressionsGuestTest {
    private static void checked(String guest) {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(guest));
    }

    private static void rejected(String guest) {
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(guest));
    }

    private static String runGuest(String guest) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        Source source = assertDoesNotThrow(() ->
                Source.newBuilder(OresLanguage.ID, guest, "map-expression-guest.atto")
                        .mimeType(OresLanguage.MIME_TYPE).build());
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(stdout).build()) {
            context.eval(source);
        }
        return stdout.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Test
    void dynamicKeyAndValueCallsEvaluateInSourceOrder() {
        String guest = """
                fnc first_key(): string {
                    stdio.println("key-1");
                    return "alpha";
                }
                fnc first_value(): int {
                    stdio.println("value-1");
                    return 40;
                }
                fnc second_key(): string {
                    stdio.println("key-2");
                    return "beta";
                }
                fnc second_value(): int {
                    stdio.println("value-2");
                    return 2;
                }
                pub fnc main(): void {
                    const m = ~map<string, int>(
                        first_key() => first_value(),
                        second_key() => second_value()
                    );
                    stdio.println(m.size);
                    return;
                }
                """;
        checked(guest);
        assertEquals("key-1\nvalue-1\nkey-2\nvalue-2\n2\n", runGuest(guest));
    }

    @Test
    void typedNamesAndArithmeticProduceStoredValues() {
        String guest = """
                pub fnc main(): void {
                    const string label = "id";
                    const int base = 40;
                    const Map<string, int> values = ~map(
                        label => base + 2,
                        "other" => base
                    );
                    stdio.println(values.size);
                    return;
                }
                """;
        checked(guest);
        assertEquals("2\n", runGuest(guest));
    }

    @Test
    void duplicateComputedKeysFailBeforeAnyMapIsPublished() {
        String guest = """
                fnc repeated(): string { return "dup"; }
                pub fnc main(): void {
                    const m = ~map<string, int>(
                        "dup" => 1,
                        repeated() => 2
                    );
                    stdio.println(m.size);
                    return;
                }
                """;
        checked(guest); // duplicate is dynamic; compile-time proof cannot decide
        assertThrows(PolyglotException.class, () -> runGuest(guest));
    }

    @Test
    void badComputedValueTypeAndBorrowedContainersFailClosed() {
        rejected("""
                fnc invalid(): bool { return true; }
                pub fnc main(): void {
                    const m = ~map<string, int>("bad" => invalid());
                    return;
                }
                """);
        rejected("""
                pub fnc main(): void {
                    const inner = ~map<string, int>("x" => 1);
                    const m = ~map<string, int>(inner => 2);
                    return;
                }
                """);
        rejected("""
                pub fnc main(): void {
                    const m = ~map<string, int>(
                        "a" => 1, "a" => 2
                    );
                    return;
                }
                """);
        rejected("""
                pub fnc main(): void {
                    const m = ~map<string, int>(...unknown);
                    return;
                }
                """);
    }
}
