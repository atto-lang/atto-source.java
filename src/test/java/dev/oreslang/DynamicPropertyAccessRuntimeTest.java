package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Guest runtime coverage for computed fixed-product property/index reads and writes. */
final class DynamicPropertyAccessRuntimeTest {
    @Test
    void awaitedDynamicStructKeyUsesSuspendableComputedWritePath() {
        String source = """
                async fnc select_key(): string {
                    return "x";
                }

                pub async fnc main(): void {
                    const Vec2 seed = infer<Vec2> struct{x: 1, y: 2};
                    val Vec2 current = infer struct{x: 3, y: 4};
                    current[await select_key()] = 9;
                    stdio.println(current["x"]);
                    return;
                }
                """;

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source program = assertDoesNotThrow(() ->
                Source.newBuilder(OresLanguage.ID, source, "dynamic-property-awaited-write.ores")
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

        String text = output.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertEquals("9\n", text);
    }

    @Test
    void dynamicAggregateAndClassIndexingExecutesWithCheckedSemantics() {
        String source = """
                define class Point as
                    pub let int x = 1;
                    pub let int y = 2;
                end

                define class InferredPoint as
                    pub let x = 20;
                    pub let y = 30;
                end

                fnc record_get(string key): float {
                    const row = record{x: 3.0, y: 4.0};
                    return row[key];
                }

                fnc tuple_get(Tuple[float,float] pair, int index): float {
                    return pair[index];
                }

                fnc dynamic_key(): string {
                    return "x";
                }

                pub fnc main(): void {
                    stdio.println(record_get("y"));

                    const pair = tuple(5.0, 6.0);
                    stdio.println(tuple_get(pair, 0));

                    const Vec2 seed = infer<Vec2> struct{x: 7.0, y: 8.0};
                    val Vec2 current = infer struct{x: 9.0, y: 10.0};
                    const field = dynamic_key();
                    current[field] = 11.0;
                    stdio.println(current[field]);

                    val Point point = new Point();
                    const classKey = dynamic_key();
                    point[classKey] = 12;
                    stdio.println(point[classKey]);

                    val InferredPoint inferred = new InferredPoint();
                    inferred[classKey] = 13;
                    stdio.println(inferred[classKey]);
                    return;
                }
                """;

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source program = assertDoesNotThrow(() ->
                Source.newBuilder(OresLanguage.ID, source, "dynamic-property-access-runtime.ores")
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

        String text = output.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertEquals("4.0\n5.0\n11.0\n12\n13\n", text);
    }

    @Test
    void runtimeDerivedKeysCannotReflectMethodsOrCreateUnknownFields() {
        String methodKey = """
                define class Point as
                    pub val float x = 3.0;
                    pub fnc move(): float { return self.x; }
                end

                fnc dynamic_key(): string { return "move"; }

                pub fnc main(): void {
                    const Point point = new Point();
                    const string key = dynamic_key();
                    stdio.println(point[key]);
                    return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(methodKey));
        assertThrows(org.graalvm.polyglot.PolyglotException.class, () -> {
            try (Context context = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(false).build()) {
                context.eval(Source.newBuilder(
                        OresLanguage.ID, methodKey, "dynamic-class-method-key.ores").build());
            }
        });

        String unknownClassWrite = """
                define class MutablePoint as
                    pub let int x = 1;
                end

                fnc dynamic_key(): string { return "missing"; }

                pub fnc main(): void {
                    val MutablePoint point = new MutablePoint();
                    const string key = dynamic_key();
                    point[key] = 7;
                    return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(unknownClassWrite));
        assertThrows(org.graalvm.polyglot.PolyglotException.class, () -> {
            try (Context context = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(false).build()) {
                context.eval(Source.newBuilder(
                        OresLanguage.ID, unknownClassWrite, "dynamic-class-missing-write.ores").build());
            }
        });

        String unknownStructWrite = """
                fnc dynamic_key(): string { return "missing"; }

                pub fnc main(): void {
                    const Vec seed = infer<Vec> struct{x: 1.0};
                    val Vec value = infer struct{x: 2.0};
                    const string key = dynamic_key();
                    value[key] = 3.0;
                    return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(unknownStructWrite));
        assertThrows(org.graalvm.polyglot.PolyglotException.class, () -> {
            try (Context context = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(false).build()) {
                context.eval(Source.newBuilder(
                        OresLanguage.ID, unknownStructWrite, "dynamic-struct-missing-write.ores").build());
            }
        });
    }

}
