package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Narrow exact-source Task -> observer-Future gate. Full first-class Task<T>,
 * Task.from_future and Observable adapters are NOT enabled by these tests.
 */
@Timeout(20)
final class FutureFromInlineTaskLanguageTest {
    @Test
    void inlineTaskRunsOnceAndCanBeAwaitedThroughAnObservation() throws Exception {
        assertEquals("42", run("""
                pub fnc main(): void {
                  let Future<int> result = Future.from_task(
                    task {
                      return 42;
                    }
                  );
                  stdio.stdout.write(await result);
                  return;
                }
                """));
    }

    @Test
    void compilerKeepsExactTaskPayloadAndVoidResultType() throws Exception {
        assertEquals("ok", run("""
                pub fnc main(): void {
                  let Future<void> done = Future.from_task(
                    task {
                      stdio.stdout.write("ok");
                      return;
                    }
                  );
                  await done;
                  return;
                }
                """));
    }

    @Test
    void absentContextNeverGuessesValueProducingTaskType() {
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                    pub fnc main(): void {
                      let unsupported = Future.from_task(task { return 42; });
                      return;
                    }
                    """));
    }

    @Test
    void plainFutureMustNotBeMistakenForAnAuthorizedTaskHandle() {
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                    async fnc number(): int { return 42; }
                    pub fnc main(): void {
                      let Future<int> f = number();
                      let Future<int> invalid = Future.from_task(f);
                      return;
                    }
                    """));
    }

    @Test
    void wrongArityAndCompetingNewConstructorRemainRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                    pub fnc main(): void {
                      let Future<int> bad = Future.from_task();
                      return;
                    }
                    """));
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                    pub fnc main(): void {
                      let Future<int> bad = Future.new(task { return 42; });
                      return;
                    }
                    """));
    }

    @Test
    void staticResultTypeMustMatchTheTaskValue() {
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                    pub fnc main(): void {
                      let Future<int> wrong = Future.from_task(
                        task { return "not an int"; }
                      );
                      return;
                    }
                    """));
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(
                OresLanguage.ID, program, "future-inline-task.ores")
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
