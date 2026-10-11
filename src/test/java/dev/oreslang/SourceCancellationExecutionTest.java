package dev.oreslang;

import dev.oreslang.nodes.OresEvalRootNode;
import dev.oreslang.runtime.OresFuture;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises actual parsed, typechecked, suspended Oreslang source tasks. */
@Timeout(value = 25, unit = TimeUnit.SECONDS)
final class SourceCancellationExecutionTest {
    private static final String PROGRAM = """
            pub async fnc work(): int {
              try {
                defer stdio.println("outer-defer");
                try {
                  defer stdio.println("inner-defer");
                  val Channel<int> gate = Channel.new<int>(0);
                  stdio.println("armed");
                  const unused = await (nb readch gate);
                  stdio.println("INVALID-after-await");
                } catch (inner) {
                  stdio.println("INVALID-inner-catch");
                } finally {
                  stdio.println("inner-finally");
                }
              } catch (outer) {
                stdio.println("INVALID-outer-catch");
              } finally {
                stdio.println("outer-finally");
              }
              return 17;
            }
            """;

    private static OresFuture<?> start(Context context, String source) throws Exception {
        Source guest = Source.newBuilder(OresLanguage.ID, source, "source-cancel.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        Value result = context.parse(guest).execute(
                OresEvalRootNode.INVOKE_PUBLIC_COMMAND, "work");
        assertTrue(result.isHostObject(), "async source result must be a runtime Future");
        return assertInstanceOf(OresFuture.class, result.asHostObject());
    }

    @Test
    void cancelledNestedGuestDeferAndFinallyDrainOnceWithoutEnteringCatch() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(true).out(output).build()) {
            OresFuture<?> task = start(context, PROGRAM);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!output.toString(StandardCharsets.UTF_8).contains("armed")
                    && System.nanoTime() < deadline) Thread.sleep(1);
            assertTrue(output.toString(StandardCharsets.UTF_8).contains("armed"),
                    "source did not reach its pending channel receive");
            assertFalse(task.isDone());
            assertTrue(task.cancel(false));
            assertThrows(CancellationException.class,
                    () -> task.get(5, TimeUnit.SECONDS));

            assertEquals("""
                    armed
                    inner-defer
                    inner-finally
                    outer-defer
                    outer-finally
                    """, output.toString(StandardCharsets.UTF_8));
        }
    }
}
