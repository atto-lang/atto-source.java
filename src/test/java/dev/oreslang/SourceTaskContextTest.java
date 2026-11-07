package dev.oreslang;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/** Task-local context: bound per block, inherited by child tasks and async calls. */
@Timeout(20)
final class SourceTaskContextTest {
    @Test
    void contextIsVisibleInsideTheBlockAndGoneAfterIt() throws Exception {
        assertEquals("abc|none", run("""
                pub async fnc main(): void {
                  task context(#trace_id, "abc") {
                    stdio.stdout.write(task_context(#trace_id).unwrap_or("none"));
                  }
                  stdio.stdout.write("|");
                  stdio.stdout.write(task_context(#trace_id).unwrap_or("none"));
                  return;
                }
                """));
    }

    @Test
    void childTasksAndAwaitedAsyncCallsInheritContext() throws Exception {
        assertEquals("child=req-7|callee=req-7", run("""
                async fnc callee(): String {
                  rt cooperate;
                  return task_context(#request).unwrap_or("none");
                }
                pub async fnc main(): void {
                  task context(#request, "req-7") {
                    let Future<String> child = task {
                      rt cooperate;
                      return task_context(#request).unwrap_or("none");
                    };
                    val String seen = await child;
                    stdio.stdout.write("child=");
                    stdio.stdout.write(seen);
                    stdio.stdout.write("|callee=");
                    stdio.stdout.write(await callee());
                  }
                  return;
                }
                """));
    }

    @Test
    void innerBindingShadowsAndOuterIsRestored() throws Exception {
        assertEquals("inner|outer", run("""
                pub async fnc main(): void {
                  task context(#tenant, "outer") {
                    task context(#tenant, "inner") {
                      stdio.stdout.write(task_context(#tenant).unwrap_or("none"));
                    }
                    stdio.stdout.write("|");
                    stdio.stdout.write(task_context(#tenant).unwrap_or("none"));
                  }
                  return;
                }
                """));
    }

    @Test
    void contextSurvivesAwaitInsideTheBindingTask() throws Exception {
        assertEquals("t1|t1", run("""
                async fnc tick(): int {
                  rt cooperate;
                  return 1;
                }
                pub async fnc main(): void {
                  task context(#trace_id, "t1") {
                    stdio.stdout.write(task_context(#trace_id).unwrap_or("none"));
                    val int ignored = await tick();
                    stdio.stdout.write("|");
                    stdio.stdout.write(task_context(#trace_id).unwrap_or("none"));
                  }
                  return;
                }
                """));
    }

    @Test
    void contextValuesMustBeStrings() {
        PolyglotException error = assertThrows(PolyglotException.class, () -> run("""
                pub async fnc main(): void {
                  task context(#trace_id, 42) {
                  }
                  return;
                }
                """));
        assertTrue(error.getMessage().contains("Symbol key and a String value"), error::getMessage);
    }

    @Test
    void contextHelpersAreNotSourceNames() {
        assertThrows(PolyglotException.class, () -> run("""
                pub async fnc main(): void {
                  $task_context_exit();
                  return;
                }
                """));
    }

    private static String run(String sourceText) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(
                        OresLanguage.ID, sourceText, "source-task-context.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
