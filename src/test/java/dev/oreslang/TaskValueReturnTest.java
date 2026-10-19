package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class TaskValueReturnTest {

    private static final String NB_SOURCE = """
            pub routine main(): void {
                val int x = 41;
                let Task<int> worker = nb {
                    return x + 1;
                };
                let int result = await worker;
                stdio.stdout.write(result);
                return;
            }
            """;

    private static final String TASK_SOURCE = """
            pub routine main(): void {
                val int x = 41;
                let Task<int> worker = task {
                    return x + 1;
                };
                let int result = await worker;
                stdio.stdout.write(result);
                return;
            }
            """;

    @Test
    void equivalentNbAndTaskExpressionsCompileWithOwnership() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(NB_SOURCE));
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(TASK_SOURCE));
    }

    @Test
    void nbReturnsValueOnRealScheduler() throws Exception {
        assertEquals("42", run(NB_SOURCE));
    }

    @Test
    void taskAliasReturnsValueOnRealScheduler() throws Exception {
        assertEquals("42", run(TASK_SOURCE));
    }

    @Test
    void returningWrongTypeFails() {
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                    pub routine main(): void {
                        let Task<int> job = task {
                            return "not an int";
                        };
                        await job;
                        return;
                    }
                    """));
    }

    @Test
    void missingNonVoidReturnFails() {
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                    pub routine main(): void {
                        let Task<int> job = nb {
                            val int number = 41;
                        };
                        await job;
                        return;
                    }
                    """));
    }

    @Test
    void bareReturnCannotSatisfyTaskInt() {
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                    pub routine main(): void {
                        let Task<int> job = task { return; };
                        await job;
                        return;
                    }
                    """));
    }

    @Test
    void taskVoidRejectsReturnedValue() {
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                    pub routine main(): void {
                        let Task<void> job = task { return 1; };
                        await job;
                        return;
                    }
                    """)));
    }

    @Test
    void taskNameRemainsAvailableAsOrdinaryIdentifier() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc id(int task): int { return task; }
                pub routine main(): void {
                    let int answer = id(42);
                    return;
                }
                """)));
    }

    @Test
    void taskCannotPublishDeferredInitializationToParent() {
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                    fnc bad(): bool {
                        const mut point = struct{foo: string, ready: bool}{foo: "before"};
                        let Task<void> deferred = task {
                            point.ready = true;
                            return;
                        };
                        return point.ready;
                    }
                    """)));
    }

    private static String run(String text) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source program = Source.newBuilder(OresLanguage.ID, text, "task-return.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(program);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
