package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
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
 * Executable guest-source acceptance for the first scalar-only, Copy-safe
 * immutable aggregate spread slice. This is not general record spread parity.
 */
final class ImmutableAggregateScalarSpreadRuntimeTest {
    private static String execute(String source) {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source program = assertDoesNotThrow(() ->
                Source.newBuilder(OresLanguage.ID, source, "aggregate-scalar-spread.ores")
                        .mimeType(OresLanguage.MIME_TYPE)
                        .build());
        assertDoesNotThrow(() -> {
            try (Context context = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(false)
                    .out(output)
                    .build()) {
                context.eval(program);
            }
        });
        return output.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Test
    void inferredStructAndRecordExtendWithoutMutatingOriginal() {
        assertEquals("5\n5\n42\nhello\n", execute("""
                pub fnc main(): void {
                    const original = infer struct{foo: 5};
                    const next = infer struct{...original, bar: "hello"};
                    const row = record{...next, foo: 42};
                    stdio.println(original.foo);
                    stdio.println(next.foo);
                    stdio.println(row.foo);
                    stdio.println(row.bar);
                    return;
                }
                """));
    }

    @Test
    void multipleSpreadsAndExplicitOverrideRespectRightmostRule() {
        assertEquals("20\n42\n1\n", execute("""
                pub fnc main(): void {
                    const first = record{x: 1, y: 2};
                    const second = record{y: 20, z: true};
                    const merged = record{...first, ...second, x: 42};
                    stdio.println(merged.y);
                    stdio.println(merged.x);
                    stdio.println(first.x);
                    return;
                }
                """));
    }

    @Test
    void aggregateElementsEvaluateExactlyOnceInLexicalOrder() {
        assertEquals("base\\nfirst\\ninline\\nlast\\n4\\n1\\n", execute("""
                fnc mark(string label, int value): int {
                    stdio.println(label);
                    return value;
                }

                pub fnc main(): void {
                    const base = record{x: mark("base", 1)};
                    const next = record{
                        x: mark("first", 2),
                        ...record{x: mark("inline", 3)},
                        x_after: mark("last", 4)
                    };
                    stdio.println(next.x_after);
                    stdio.println(base.x);
                    return;
                }
                """));
    }

    @Test
    void spreadCanOverrideNamedInitializerBeforeIt() {
        assertEquals("6\\n5\\n", execute("""
                pub fnc main(): void {
                    const base = record{x: 5, y: 6};
                    const next = record{x: 1, ...base};
                    stdio.println(next.y);
                    stdio.println(next.x);
                    return;
                }
                """));
    }

    @Test
    void nestedOrMoveOnlySourcesRejectBeforeExecution() {
        String nested = """
                fnc fail(): int {
                    const first = record{inner: record{x: 1}};
                    const second = record{...first};
                    return second.inner.x;
                }
                """;
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(nested)));
        assertThrows(IllegalArgumentException.class, () -> OwnershipChecker.check(Parser.parse(nested)));
    }

    @Test
    void explicitShapedStructSpreadRemainsUnsupported() {
        String source = """
                fnc fail(): int {
                    const first = record{x: 1};
                    const second = struct{x: int}{...first};
                    return second.x;
                }
                """;
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck(source));
    }

    @Test
    void nonaggregateAndUnknownSourceReject() {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                fnc fail(): void {
                    const next = record{...42};
                    return;
                }
                """));
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                fnc fail(): void {
                    const next = infer struct{...missing};
                    return;
                }
                """));
    }

    @Test
    void explicitlyRepeatedFieldRejectsEvenAcrossSpread() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc fail(): void {
                    const row = record{foo: 1};
                    const bad = record{foo: 2, ...row, foo: 3};
                    return;
                }
                """));
    }
}
