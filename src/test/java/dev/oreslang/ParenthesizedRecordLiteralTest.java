package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** First executable stage: a parenthesized labeled product is one ordinary value. */
final class ParenthesizedRecordLiteralTest {
    @Test
    void labeledParenthesesAreReadonlyRecordValuesNotPositionalTuples() throws Exception {
        String code = """
                pub routine main(): void {
                    const r = (b: 22, a: 20);
                    stdio.stdout.write(r.a + r.b);
                    return;
                }
                """;
        TypeChecker.check(Parser.parse(code));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, code, "record-literal.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build());
        }
        assertEquals("42", output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void unspreadRecordIsOneArgumentAndPreservesReadonlyThroughGenerics() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc identity<T>(T value): int {
                    return 42;
                }
                pub routine main(): void {
                    const result = identity((b: 22, a: 20));
                    stdio.stdout.write(result);
                    return;
                }
                """)));
    }

    @Test
    void unspreadReadonlyRecordCannotEraseReadonlyCapability() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc total({a: int, b: int} values): int {
                            return values.a + values.b;
                        }
                        pub routine main(): void {
                            const result = total((b: 22, a: 20));
                            stdio.stdout.write(result);
                            return;
                        }
                        """)));
        assertTrue(error.getMessage().contains("readonly capability"), error.getMessage());
    }

    @Test
    void recordSpreadRemainsRejectedUntilCheckedNamedCallPlanIsIntegrated() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc total(int a, int b): int {
                            return a + b;
                        }
                        pub routine main(): void {
                            stdio.stdout.write(total(...(b: 22, a: 20)));
                            return;
                        }
                        """)));
        assertTrue(error.getMessage().contains("statically known tuple"), error.getMessage());
    }

    @Test
    void duplicateLiteralLabelsAreRejectedBeforeExecution() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub routine main(): void {
                            const r = (a: 1, a: 2);
                            return;
                        }
                        """)));
        assertTrue(failure.getMessage().contains("duplicate"), failure.getMessage());
    }

    @Test
    void recordCannotSmuggleAFunctionScopedBorrowIntoOwnedProduct() {
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub routine main(): void {
                            const int v = 42;
                            const r = (value: rt borrow v);
                            return;
                        }
                        """)));
    }

    @Test
    void recordAndTupleArgumentFormsNeverImplicitlyExpand() {
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc add(int a, int b): int { return a + b; }
                        pub routine main(): void {
                            stdio.stdout.write(add((19, 23)));
                            return;
                        }
                        """)));
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc add(int a, int b): int { return a + b; }
                        pub routine main(): void {
                            stdio.stdout.write(add((a: 19, b: 23)));
                            return;
                        }
                        """)));
    }

    @Test
    void spreadOfRuntimeMapIsNotAcceptedAsAClosedProduct() {
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc add(int a, int b): int { return a + b; }
                        pub routine main(): void {
                            const values = [19, 23];
                            stdio.stdout.write(add(...values));
                            return;
                        }
                        """)));
    }

    @Test
    void ordinaryUnlabeledTupleStillHasPositionalSemantics() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc add(int a, int b): int { return a + b; }
                pub routine main(): void {
                    const xs = (19, 23);
                    stdio.stdout.write(add(...xs));
                    return;
                }
                """)));
    }
}
