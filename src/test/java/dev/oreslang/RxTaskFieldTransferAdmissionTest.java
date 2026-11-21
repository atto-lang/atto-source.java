package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task-native reactive admission contract, stacked on Task<T> PR #1468.
 *
 * RX source: ores-truffle-oreslang/rx-ores-tasks, draft PR #1.
 * These tests exercise the real parser/TypeChecker/OwnershipChecker and
 * evaluator; a source-only test in rx-ores-tasks is not a substitute.
 */
final class RxTaskFieldTransferAdmissionTest {

    private static final String MUTABLE_TASK_FIELD = """
            define class TaskCell as
              let Task<int> producer;
              constructor(Task<int> producer) {
                self.producer = rt take producer;
              }
              pub take_task(mut self)(): Task<int> {
                return rt take self.producer;
              }
            end
            """;

    private static final String IMMUTABLE_TASK_FIELD = """
            define class TaskCell as
              val Task<int> producer;
              constructor(Task<int> producer) {
                self.producer = rt take producer;
              }
              pub take_task(mut self)(): Task<int> {
                return rt take self.producer;
              }
            end
            """;

    @Test
    void immutableTaskClassFieldCannotBeExtractedWithRtTake() {
        IllegalArgumentException err = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(IMMUTABLE_TASK_FIELD));
        assertTrue(err.getMessage().contains("immutable/final class field"),
                "expected precise immutable-field rejection; got: " + err.getMessage());
    }

    @Test
    void mutableTaskClassFieldAllowsOneCheckedOwnershipTransfer() {
        String program = MUTABLE_TASK_FIELD + """
                pub routine main(): void {
                  let Task<int> producer = task { return 42; };
                  let TaskCell holder = new TaskCell(rt take producer);
                  let Task<int> child = holder.take_task();
                  let int result = await child;
                  stdio.stdout.write(result);
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
    }

    @Test
    void transferredTaskProducesValueInRealGuestExecution() throws Exception {
        String program = MUTABLE_TASK_FIELD + """
                pub routine main(): void {
                  let Task<int> producer = task { return 42; };
                  let TaskCell holder = new TaskCell(rt take producer);
                  let Task<int> child = holder.take_task();
                  let int result = await child;
                  stdio.stdout.write(result);
                  return;
                }
                """;
        assertEquals("42", run(program));
    }

    @Test
    void sameAffineTaskLocalCannotBeMovedIntoTwoSubscriptions() {
        String program = MUTABLE_TASK_FIELD + """
                pub routine main(): void {
                  let Task<int> producer = task { return 42; };
                  let TaskCell first = new TaskCell(rt take producer);
                  let TaskCell second = new TaskCell(rt take producer);
                  return;
                }
                """;
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(program));
    }

    @Test
    void aSecondMoveFromTheSameClassFieldMustFailClosed() {
        String program = MUTABLE_TASK_FIELD + """
                pub routine main(): void {
                  let Task<int> producer = task { return 42; };
                  let TaskCell holder = new TaskCell(rt take producer);
                  let Task<int> once = holder.take_task();
                  let Task<int> twice = holder.take_task();
                  stdio.stdout.write(await once);
                  stdio.stdout.write(await twice);
                  return;
                }
                """;
        // Verify compiler admission separately: a parse/type failure cannot
        // stand in for a runtime moved-slot enforcement receipt.
        OresCompiler.parseAndTypeCheck(program);
        RuntimeException failure = assertThrows(RuntimeException.class, () -> run(program));
        assertTrue(failure.getMessage() != null
                        && failure.getMessage().contains("already moved"),
                "expected runtime moved-slot denial, got: " + failure);
    }

    @Test
    void readingAClassFieldAfterItsOwnerWasTakenMustFailClosed() {
        String declaration = """
                define class TaskCell as
                  pub let Task<int> producer;
                  constructor(Task<int> producer) {
                    self.producer = rt take producer;
                  }
                  pub take_task(mut self)(): Task<int> {
                    return rt take self.producer;
                  }
                  pub peek_task(): Task<int> {
                    return self.producer;
                  }
                end
                """;
        String program = declaration + """
                pub routine main(): void {
                  let Task<int> producer = task { return 42; };
                  let TaskCell holder = new TaskCell(rt take producer);
                  let Task<int> once = holder.take_task();
                  let Task<int> stolen = holder.peek_task();
                  stdio.stdout.write(await once);
                  stdio.stdout.write(await stolen);
                  return;
                }
                """;
        // Read occurs within the class; fail unless the runtime explicitly
        // rejects this moved slot (not a private-access or parse error).
        OresCompiler.parseAndTypeCheck(program);
        RuntimeException failure = assertThrows(RuntimeException.class, () -> run(program));
        assertTrue(failure.getMessage() != null
                        && failure.getMessage().contains("already moved"),
                "expected read-after-move denial, got: " + failure);
    }

    @Test
    void movedClassFieldMayBeReinitializedWithAUniqueNewTask() throws Exception {
        String declaration = """
                define class TaskCell as
                  let Task<int> producer;
                  constructor(Task<int> producer) {
                    self.producer = rt take producer;
                  }
                  pub take_task(mut self)(): Task<int> {
                    return rt take self.producer;
                  }
                  pub reset_task(mut self)(Task<int> fresh): void {
                    self.producer = rt take fresh;
                    return;
                  }
                end
                """;
        String program = declaration + """
                pub routine main(): void {
                  let Task<int> first = task { return 40; };
                  let TaskCell holder = new TaskCell(rt take first);
                  let Task<int> old = holder.take_task();
                  let Task<int> fresh = task { return 2; };
                  holder.reset_task(rt take fresh);
                  let Task<int> current = holder.take_task();
                  let int a = await old;
                  let int b = await current;
                  stdio.stdout.write(a + b);
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        assertEquals("42", run(program));
    }

    @Test
    void nestedTaskContinuationCannotTakeSameClassFieldTwice() {
        String program = MUTABLE_TASK_FIELD + """
                pub routine main(): void {
                  let Task<int> outer = task {
                    let Task<int> worker = task { return 42; };
                    let TaskCell holder = new TaskCell(rt take worker);
                    let Task<int> first = holder.take_task();
                    let Task<int> second = holder.take_task();
                    return await first + await second;
                  };
                  stdio.stdout.write(await outer);
                  return;
                }
                """;
        OresCompiler.parseAndTypeCheck(program);
        RuntimeException failure = assertThrows(RuntimeException.class, () -> run(program));
        assertTrue(failure.getMessage() != null
                        && failure.getMessage().contains("already moved"),
                "expected suspendable moved-slot denial, got: " + failure);
    }

    private static String run(String code) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, code, "rx-task-field-transfer.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
