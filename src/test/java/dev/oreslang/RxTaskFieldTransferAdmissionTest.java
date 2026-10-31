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
