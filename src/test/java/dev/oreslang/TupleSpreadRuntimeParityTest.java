package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class TupleSpreadRuntimeParityTest {
    @Test
    void directFunctionSelectsOverloadByExpandedTupleArity() throws Exception {
        String code = """
                fnc choose(int a): int { return a; }
                fnc choose(int a, int b): int { return a + b; }

                pub routine main(): void {
                    const args = (19, 23);
                    stdio.stdout.write(choose(...args));
                    return;
                }
                """;
        assertEquals("42", checkedExecution(code));
    }

    @Test
    void suspendableFunctionExpandsStoredTupleBeforeDirectDispatch() throws Exception {
        String code = """
                fnc choose(int a): int { return a; }
                fnc choose(int a, int b): int { return a + b; }

                async fnc forwarded(): int {
                    const args = (19, 23);
                    return choose(...args);
                }

                pub routine main(): void {
                    stdio.stdout.write(await forwarded());
                    return;
                }
                """;
        assertEquals("42", checkedExecution(code));
    }

    @Test
    void suspendableInlineTupleEvaluatesEachElementOnceInLexicalOrder()
            throws Exception {
        String code = """
                fnc first(): int {
                    stdio.stdout.write("A");
                    return 19;
                }

                fnc second(): int {
                    stdio.stdout.write("B");
                    return 23;
                }

                fnc add(int a, int b): int {
                    return a + b;
                }

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
    void dynamicLengthCollectionsStillCannotSpreadIntoFixedArityCalls() {
        assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc add(int a, int b): int {
                            return a + b;
                        }

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
            context.eval(Source.newBuilder(
                            OresLanguage.ID,
                            code,
                            "tuple-spread-runtime-parity.ores")
                    .mimeType(OresLanguage.MIME_TYPE)
                    .build());
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
