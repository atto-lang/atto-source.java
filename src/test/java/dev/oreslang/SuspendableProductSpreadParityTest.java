package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A fixed tuple spread must have the same effective arity and lexical
 * evaluation order in the direct and continuation-based function paths.
 * Named record spread remains fail-closed pending checked field binding.
 */
final class SuspendableProductSpreadParityTest {
    @Test
    void asyncFunctionExpandsStoredTupleIntoDirectOverload() throws Exception {
        String code = """
                fnc add(int a, int b): int { return a + b; }
                async fnc forwarded(): int {
                    const args = (19, 23);
                    return add(...args);
                }
                pub routine main(): void {
                    stdio.stdout.write(await forwarded());
                    return;
                }
                """;
        assertEquals("42", checkedExecution(code));
    }

    @Test
    void asyncFunctionExpandsInlineTupleAndEvaluatesEffectsOnceInSourceOrder() throws Exception {
        String code = """
                fnc first(): int { stdio.stdout.write("A"); return 19; }
                fnc second(): int { stdio.stdout.write("B"); return 23; }
                fnc add(int a, int b): int { return a + b; }
                async fnc forwarded(): int {
                    return add(...(first(), second()));
                }
                pub routine main(): void {
                    stdio.stdout.write(await forwarded());
                    return;
                }
                """;
        assertEquals("AB42", checkedExecution(code));
    }

    @Test
    void asyncFunctionUsesExpandedArityRatherThanSyntacticCount() throws Exception {
        String code = """
                fnc one(int a): int { return a; }
                fnc one(int a, int b): int { return a + b; }
                async fnc forwarded(): int { return one(...(19, 23)); }
                pub routine main(): void {
                    stdio.stdout.write(await forwarded());
                    return;
                }
                """;
        assertEquals("42", checkedExecution(code));
    }

    @Test
    void asyncFunctionStillRejectsDynamicLengthCallSpread() {
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc add(int a, int b): int { return a + b; }
                        async fnc forwarded(): int {
                            const values = [19, 23];
                            return add(...values);
                        }
                        """)));
    }

    private static String checkedExecution(String code) throws Exception {
        var parsed = Parser.parse(code);
        var typed = TypeChecker.check(parsed);
        assertDoesNotThrow(() -> OwnershipChecker.check(typed));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, code, "suspendable-product-spread.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build());
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
