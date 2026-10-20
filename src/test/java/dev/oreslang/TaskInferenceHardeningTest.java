package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

final class TaskInferenceHardeningTest {
    @Test
    void inferIntFromTaskBlockAndExecute() throws Exception {
        assertEquals("42", run("""
                pub routine main(): void {
                    val int x = 41;
                    val worker = task { return x + 1; };
                    let int actual = await worker;
                    stdio.stdout.write(actual);
                    return;
                }
                """));
    }

    @Test
    void inferIntFromNbBlockAndExecute() throws Exception {
        assertEquals("42", run("""
                pub routine main(): void {
                    val worker = nb {
                        val int base = 40;
                        return base + 2;
                    };
                    let int actual = await worker;
                    stdio.stdout.write(actual);
                    return;
                }
                """));
    }

    @Test
    void inferIntWithTwoEquivalentConditionalReturns() throws Exception {
        assertEquals("42", run("""
                pub routine main(): void {
                    val worker = task {
                        val bool choose = true;
                        if choose then
                            return 42;
                        else
                            return 42;
                        fi
                    };
                    let int actual = await worker;
                    stdio.stdout.write(actual);
                    return;
                }
                """));
    }

    @Test
    void inferVoidWhenNoReturnValueExists() throws Exception {
        assertEquals("ok", run("""
                pub routine main(): void {
                    val worker = task {
                        stdio.stdout.write("ok");
                        return;
                    };
                    await worker;
                    return;
                }
                """));
    }

    @Test
    void nestedTaskReturnMustNotContaminateOuterVoidInference() throws Exception {
        assertEquals("42", run("""
                pub routine main(): void {
                    val outer = task {
                        val inner = nb { return 42; };
                        let int value = await inner;
                        stdio.stdout.write(value);
                    };
                    await outer;
                    return;
                }
                """));
    }

    @Test
    void inferNestedValueProducingTask() throws Exception {
        assertEquals("42", run("""
                pub routine main(): void {
                    val outer = task {
                        val inner = nb { return 41; };
                        return await inner + 1;
                    };
                    let int actual = await outer;
                    stdio.stdout.write(actual);
                    return;
                }
                """));
    }

    @Test
    void rejectMismatchedReturnBranchesWithoutExpectedType() {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                pub routine main(): void {
                    val worker = task {
                        val bool choose = true;
                        if choose then
                            return 41;
                        else
                            return "wrong";
                        fi
                    };
                    await worker;
                    return;
                }
                """));
    }

    @Test
    void rejectUnannotatedTaskWithMissingReturnPath() {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                pub routine main(): void {
                    val worker = nb {
                        val bool choose = true;
                        if choose then
                            return 41;
                        fi
                    };
                    await worker;
                    return;
                }
                """));
    }

    @Test
    void rejectReturningBorrowAcrossTaskBoundary() {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                pub routine main(): void {
                    let int original = 41;
                    val worker = task { return rt borrow original; };
                    await worker;
                    return;
                }
                """));
    }

    @Test
    void inferResultRemainsMoveOnlyAcrossTwoTaskCaptures() {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                pub routine main(): void {
                    val produced = task { return 42; };
                    val first = task { return await produced; };
                    val second = task { return await produced; };
                    await first;
                    await second;
                    return;
                }
                """));
    }

    private static String run(String text) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, text, "task-inference.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(out).build()) {
            context.eval(source);
        }
        return out.toString(StandardCharsets.UTF_8);
    }
}
