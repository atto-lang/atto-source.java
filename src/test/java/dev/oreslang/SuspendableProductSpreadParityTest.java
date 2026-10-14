package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import dev.oreslang.types.Types;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;

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

    @Test
    void directFunctionBindsStoredRecordByLabelRatherThanInitializerOrder() throws Exception {
        String code = """
                fnc add(int a, int b): int { return a + b; }
                pub routine main(): void {
                    const args = (b: 23, a: 19);
                    stdio.stdout.write(add(...args));
                    return;
                }
                """;
        assertEquals("42", checkedExecution(code));
    }

    @Test
    void directInlineRecordPreservesSourceEffectsAndPermutesOnlyAfterEvaluation() throws Exception {
        String code = """
                fnc first(): int { stdio.stdout.write("A"); return 19; }
                fnc second(): int { stdio.stdout.write("B"); return 23; }
                fnc encode(int a, int b): int { return a * 100 + b; }
                pub routine main(): void {
                    stdio.stdout.write(encode(...(b: second(), a: first())));
                    return;
                }
                """;
        assertEquals("BA1923", checkedExecution(code));
    }

    @Test
    void asyncDirectFunctionBindsStoredRecordAndPreservesEffectiveArity() throws Exception {
        String code = """
                fnc encode(int a, int b): int { return a * 100 + b; }
                fnc encode(int a): int { return a; }
                async fnc forwarded(): int {
                    const args = (b: 23, a: 19);
                    return encode(...args);
                }
                pub routine main(): void {
                    stdio.stdout.write(await forwarded());
                    return;
                }
                """;
        assertEquals("1923", checkedExecution(code));
    }

    @Test
    void staticallyTypedRecordFieldsRetainLexicalOrderAndImmutableSnapshot() {
        LinkedHashMap<String, Types.Type> fields = new LinkedHashMap<>();
        fields.put("zeta", Types.Primitive.STRING);
        fields.put("alpha", Types.Primitive.INT);
        fields.put("middle", Types.Primitive.BOOL);
        var record = new Types.Record(fields, true);
        assertEquals(List.of("zeta", "alpha", "middle"),
                List.copyOf(record.members().keySet()));
        fields.remove("zeta");
        assertEquals(List.of("zeta", "alpha", "middle"),
                List.copyOf(record.members().keySet()));
        assertThrows(UnsupportedOperationException.class,
                () -> record.members().put("delta", Types.Primitive.INT));
        LinkedHashMap<String, Types.Type> bad = new LinkedHashMap<>();
        bad.put("unsafe", null);
        assertThrows(NullPointerException.class, () -> new Types.Record(bad, true));
    }

    @Test
    void recordSpreadChecksMixedScalarTypesByLabelNotByPosition() throws Exception {
        String code = """
                fnc checked(string name, int count): string { return name; }
                pub routine main(): void {
                    const args = (count: 42, name: "matched");
                    stdio.stdout.write(checked(...args));
                    return;
                }
                """;
        assertEquals("matched", checkedExecution(code));
    }

    @Test
    void recordSpreadRejectsMismatchedNamedScalarTypes() {
        String code = """
                fnc checked(string name, int count): string { return name; }
                pub routine main(): void {
                    const args = (count: "not-an-int", name: 42);
                    stdio.stdout.write(checked(...args));
                    return;
                }
                """;
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(code)));
    }

    @Test
    void positionalPrefixAndNamedTailPreserveAbiMapping() throws Exception {
        String code = """
                fnc calculate(int base, int multiplier, int offset): int {
                    return base * multiplier + offset;
                }
                pub routine main(): void {
                    const args = (offset: 2, multiplier: 4);
                    stdio.stdout.write(calculate(10, ...args));
                    return;
                }
                """;
        assertEquals("42", checkedExecution(code));
    }

    @Test
    void asyncPositionalPrefixAndInlineTailKeepLexicalSideEffects() throws Exception {
        String code = """
                fnc base(): int { stdio.stdout.write("B"); return 10; }
                fnc offset(): int { stdio.stdout.write("O"); return 2; }
                fnc multiplier(): int { stdio.stdout.write("M"); return 4; }
                fnc calculate(int base, int multiplier, int offset): int {
                    return base * multiplier + offset;
                }
                async fnc forwarded(): int {
                    return calculate(base(), ...(offset: offset(), multiplier: multiplier()));
                }
                pub routine main(): void {
                    stdio.stdout.write(await forwarded());
                    return;
                }
                """;
        assertEquals("BOM42", checkedExecution(code));
    }

    @Test
    void multipleRecordPacksBindEachParameterExactlyOnce() throws Exception {
        String code = """
                fnc calculate(int a, int b, int c): int { return a * 100 + b * 10 + c; }
                pub routine main(): void {
                    const first = (b: 2, a: 1);
                    const last = (c: 3);
                    stdio.stdout.write(calculate(...first, ...last));
                    return;
                }
                """;
        assertEquals("123", checkedExecution(code));
    }

    @Test
    void recordPackRejectsDuplicateAcrossSpreadsOrPositionalPrefix() {
        String prefix = "fnc calculate(int base, int multiplier, int offset): int { return base * multiplier + offset; }\\n";
        for (String call : new String[] {
                "calculate(10, ...(base: 2, multiplier: 4))",
                "calculate(...(base: 10, offset: 2), ...(offset: 3))",
                "calculate(...(offset: 2, multiplier: 4), 10)",
                "calculate(...(offset: 2, multiplier: 4), ...(base: 10))"
        }) {
            String code = prefix + "pub routine main(): void { stdio.stdout.write("
                    + call + "); return; }";
            assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(code)), call);
        }
    }

    @Test
    void recordPackCannotBypassBorrowedOrStructuralParameterAdmission() {
        String code = """
                fnc receive(&int value, int other): int { return other; }
                pub routine main(): void {
                    const pack = (other: 42, value: 19);
                    stdio.stdout.write(receive(...pack));
                    return;
                }
                """;
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(code)));
    }

    @Test
    void recordLikeReadonlyInferredStructIsNotImplicitlyARecordPack() {
        String code = """
                fnc calculate(int a, int b): int { return a + b; }
                pub routine main(): void {
                    const pack = infer struct { b: 23, a: 19 };
                    stdio.stdout.write(calculate(...pack));
                    return;
                }
                """;
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(code)));
    }

    @Test
    void recordSpreadRejectsUnknownDuplicateAndPositionalTail() {
        String prefix = "fnc add(int a, int b): int { return a + b; }\n";
        for (String call : new String[] {
                "add(...(c: 23, a: 19))",
                "add(...(a: 19, b: 23), 1)",
                "add(19, ...(a: 23))",
                "add(...(a: 19, a: 23))",
                "add(...(a: new Cell(), b: 23))"
        }) {
            String code = prefix + """
                    define class Cell as
                        pub let int value = 7;
                    end
                    """ + "pub routine main(): void { stdio.stdout.write("
                    + call + "); return; }";
            assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(code)), call);
        }
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
