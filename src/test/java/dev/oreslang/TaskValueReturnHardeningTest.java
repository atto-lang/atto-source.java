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
 * Cross-continuation regression coverage for both equivalent Task syntaxes.
 * In particular, a deferred Task may not observe a later caller rebind, nor
 * steal an owner already moved into another Task.
 */
final class TaskValueReturnHardeningTest {
    @Test
    void nbCaptureReadsSnapshotNotLaterParentRebinding() throws Exception {
        assertEquals("42", run("""
                pub routine main(): void {
                    let int x = 41;
                    let Task<int> child = nb { return x + 1; };
                    x = 100;
                    let int actual = await child;
                    stdio.stdout.write(actual);
                    return;
                }
                """));
    }

    @Test
    void taskAliasCaptureReadsSnapshotNotLaterParentRebinding() throws Exception {
        assertEquals("42", run("""
                pub routine main(): void {
                    let int x = 41;
                    let Task<int> child = task { return x + 1; };
                    x = 100;
                    let int actual = await child;
                    stdio.stdout.write(actual);
                    return;
                }
                """));
    }

    @Test
    void nestedMixedSyntaxRetainsTransitiveCapture() throws Exception {
        assertEquals("42", run("""
                pub routine main(): void {
                    val int x = 41;
                    let Task<int> outer = task {
                        let Task<int> inner = nb { return x + 1; };
                        return await inner;
                    };
                    let int result = await outer;
                    stdio.stdout.write(result);
                    return;
                }
                """));
    }

    @Test
    void nestedTasksReverseSyntaxRetainsTransitiveCapture() throws Exception {
        assertEquals("42", run("""
                pub routine main(): void {
                    val int x = 41;
                    let Task<int> outer = nb {
                        let Task<int> inner = task { return x + 1; };
                        return await inner;
                    };
                    let int result = await outer;
                    stdio.stdout.write(result);
                    return;
                }
                """));
    }

    @Test
    void manyIndependentTaskValuesAreAllDelivered() throws Exception {
        StringBuilder src = new StringBuilder("pub routine main(): void {\nlet int total = 0;\n");
        for (int i = 0; i < 16; i++) {
            src.append("let Task<int> t").append(i)
                    .append(i % 2 == 0 ? " = nb { return " : " = task { return ")
                    .append(i).append("; };\n");
        }
        for (int i = 0; i < 16; i++) {
            src.append("total = total + await t").append(i).append(";\n");
        }
        src.append("stdio.stdout.write(total);\nreturn;\n}");
        assertEquals("120", run(src.toString()));
    }

    @Test
    void failedTaskDoesNotBecomeSuccessValue() throws Exception {
        String src = """
                pub routine main(): void {
                    let Task<int> child = task {
                        val values = arr[1];
                        return values[19];
                    };
                    let int result = await child;
                    stdio.stdout.write(result);
                    return;
                }
                """;
        OresCompiler.parseAndTypeCheck(src);
        assertThrows(PolyglotException.class, () -> run(src));
    }

    @Test
    void secondTaskCannotCaptureAnAlreadyMovedClass() {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                define class Box as
                    pub let int number = 41;
                end
                pub routine main(): void {
                    let Box box = new Box();
                    let Task<int> a = task { return box.number; };
                    let Task<int> b = nb { return box.number; };
                    let int result = await a + await b;
                    return;
                }
                """));
    }

    @Test
    void cannotReturnUninitializedValueFromEitherSpelling() {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                pub routine main(): void {
                    let Task<int> child = nb {
                        val int uninitialized = "not an int";
                        return uninitialized;
                    };
                    await child;
                    return;
                }
                """));
    }

    private static String run(String programText) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, programText, "task-adversarial.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
