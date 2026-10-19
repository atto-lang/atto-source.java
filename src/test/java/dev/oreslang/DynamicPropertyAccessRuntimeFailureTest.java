package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Runtime negative coverage for computed fixed-product access. */
final class DynamicPropertyAccessRuntimeFailureTest {
    private static void runtimeRejected(String source, String name) {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        Source program = assertDoesNotThrow(() ->
                Source.newBuilder(OresLanguage.ID, source, name)
                        .mimeType(OresLanguage.MIME_TYPE)
                        .build());
        assertThrows(PolyglotException.class, () -> {
            try (Context context = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(false)
                    .build()) {
                context.eval(program);
            }
        });
    }

    @Test
    void dynamicUnknownRecordKeyFailsAtRuntime() {
        runtimeRejected("""
                type Row = {x: int, y: int};

                fnc selector(): string { return "missing"; }

                pub fnc main(): void {
                    const Row row = record{x: 1, y: 2};
                    const string key = selector();
                    const int value = row[key];
                    stdio.println(value);
                    return;
                }
                """, "dynamic-record-missing-key.ores");
    }

    @Test
    void dynamicTupleOutOfRangeFailsAtRuntime() {
        runtimeRejected("""
                fnc selector(): int { return 4; }

                pub fnc main(): void {
                    const pair = tuple(1, 2);
                    const int index = selector();
                    const int value = pair[index];
                    stdio.println(value);
                    return;
                }
                """, "dynamic-tuple-oob.ores");
    }

    @Test
    void dynamicUnknownClassFieldFailsAtRuntime() {
        runtimeRejected("""
                define class Point as
                    pub val int x = 1;
                    pub val int y = 2;
                end

                fnc selector(): string { return "missing"; }

                pub fnc main(): void {
                    const Point point = new Point();
                    const string key = selector();
                    const int value = point[key];
                    stdio.println(value);
                    return;
                }
                """, "dynamic-class-missing-key.ores");
    }

    @Test
    void dynamicUnknownStructWriteFailsAtRuntime() {
        runtimeRejected("""
                fnc selector(): string { return "missing"; }

                pub fnc main(): void {
                    val Point point = infer struct{x: 1, y: 2};
                    const string key = selector();
                    point[key] = 9;
                    return;
                }
                """, "dynamic-struct-write-missing-key.ores");
    }

    @Test
    void dynamicUnknownClassWriteFailsAtRuntime() {
        runtimeRejected("""
                define class Point as
                    pub let int x = 1;
                    pub let int y = 2;
                end

                fnc selector(): string { return "missing"; }

                pub fnc main(): void {
                    val Point point = new Point();
                    const string key = selector();
                    point[key] = 9;
                    return;
                }
                """, "dynamic-class-write-missing-key.ores");
    }

}
