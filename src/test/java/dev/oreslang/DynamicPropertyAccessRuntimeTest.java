package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Guest runtime coverage for computed fixed-product property/index reads and writes. */
final class DynamicPropertyAccessRuntimeTest {
    @Test
    void dynamicAggregateAndClassIndexingExecutesWithCheckedSemantics() {
        String source = """
                type Row = {x: float, y: float};

                define class Point as
                    pub let int x = 1;
                    pub let int y = 2;
                end

                define class InferredPoint as
                    pub let x = 20;
                    pub let y = 30;
                end

                fnc record_get(Row row, string key): float {
                    return row[key];
                }

                fnc tuple_get(Tuple[float,float] pair, int index): float {
                    return pair[index];
                }

                fnc dynamic_key(): string {
                    return "x";
                }

                pub fnc main(): void {
                    const Row row = record{x: 3.0, y: 4.0};
                    stdio.println(record_get(row, "y"));

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
}
