package dev.oreslang;

import dev.oreslang.nodes.OresEvalRootNode;
import dev.oreslang.runtime.ChannelRuntime;
import dev.oreslang.runtime.OresFuture;
import dev.oreslang.runtime.OresScheduler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cancellation must preserve genuinely PENDING lexical cleanup awaits.
 * Unlike pre-buffered channels, these gates can only be released by the host
 * after the guest has entered cancellation-driven finally/defer.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
final class SourcePendingCleanupExecutionTest {
    private static final String NESTED_PENDING_CLEANUP = """
            pub async fnc work(Channel<int> startGate,
                               Channel<int> finallyGate,
                               Channel<int> deferGate): int {
              defer stdio.println(await (nb readch deferGate));
              try {
                stdio.println("armed");
                const ignored = await (nb readch startGate);
                stdio.println("INVALID-resumed-original-work");
              } catch (cancelled) {
                stdio.println("INVALID-caught-cancellation");
              } finally {
                stdio.println("finally-pending");
                const cleaned = await (nb readch finallyGate);
                stdio.println(cleaned);
                stdio.println("finally-done");
              }
              return 17;
            }
            """;

    private static OresFuture<?> start(Context context, String source, Object... parameters)
            throws Exception {
        Source guest = Source.newBuilder(OresLanguage.ID, source, "source-pending-cleanup.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        Object[] arguments = new Object[parameters.length + 2];
        arguments[0] = OresEvalRootNode.INVOKE_PUBLIC_COMMAND;
        arguments[1] = "work";
        System.arraycopy(parameters, 0, arguments, 2, parameters.length);
        Value result = context.parse(guest).execute(arguments);
        assertTrue(result.isHostObject(), "async source invocation must return a host Future");
        return assertInstanceOf(OresFuture.class, result.asHostObject());
    }

    private static String output(ByteArrayOutputStream bytes) {
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static void awaitOutput(ByteArrayOutputStream output, String marker) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!output(output).contains(marker) && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertTrue(output(output).contains(marker), "guest never reached " + marker + "; output: " + output(output));
    }

    @Test
    void cancellationWaitsForTwoGenuinelyPendingCleanupFuturesInLifoOrder() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ChannelRuntime.Channel<Integer> startGate = new ChannelRuntime.Channel<>(0);
             ChannelRuntime.Channel<Integer> finallyGate = new ChannelRuntime.Channel<>(0);
             ChannelRuntime.Channel<Integer> deferGate = new ChannelRuntime.Channel<>(0);
             Context context = Context.newBuilder(OresLanguage.ID)
                     .allowAllAccess(true).out(output).build()) {
            OresFuture<?> task = start(context, NESTED_PENDING_CLEANUP,
                    startGate, finallyGate, deferGate);
            awaitOutput(output, "armed");
            assertFalse(task.isDone());
            assertTrue(task.cancel(false));
            assertFalse(task.cancel(false), "repeated cancellation may not restart cleanup");
            awaitOutput(output, "finally-pending");

            assertThrows(TimeoutException.class, () -> task.get(0, TimeUnit.NANOSECONDS),
                    "cancellation may not publish terminal state while finally awaits a producer");
            assertFalse(task.isCancelled(), "pending cleanup is not terminal cancellation");
            assertFalse(startGate.tryWrite(99), "abandoned normal await must have detached");

            // This is a real producer completion, not an already-ready channel.
            finallyGate.writeAsync(33).get(5, TimeUnit.SECONDS);
            awaitOutput(output, "finally-done");
            assertThrows(TimeoutException.class, () -> task.get(0, TimeUnit.NANOSECONDS),
                    "pending defer must also postpone terminal cancellation");

            deferGate.writeAsync(22).get(5, TimeUnit.SECONDS);
            assertThrows(CancellationException.class, () -> task.get(5, TimeUnit.SECONDS));
            assertEquals("""
                    armed
                    finally-pending
                    33
                    finally-done
                    22
                    """, output(output));
        }
    }

    @Test
    void failingPendingFinallyRetainsCancellationAndDoesNotRunDeferTwice()
            throws Exception {
        String source = """
                pub async fnc work(Channel<int> startGate, Channel<int> cleanupGate): int {
                  defer stdio.println("defer-once");
                  try {
                    stdio.println("armed");
                    const ignored = await (nb readch startGate);
                  } catch (cancelled) {
                    stdio.println("INVALID-caught-cancellation");
                  } finally {
                    stdio.println("finally-pending");
                    const cleaned = await (nb readch cleanupGate);
                    stdio.println("INVALID-finally-completed");
                  }
                  return 17;
                }
                """;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ChannelRuntime.Channel<Integer> startGate = new ChannelRuntime.Channel<>(0);
             ChannelRuntime.Channel<Integer> cleanupGate = new ChannelRuntime.Channel<>(0);
             Context context = Context.newBuilder(OresLanguage.ID)
                     .allowAllAccess(true).out(output).build()) {
            OresFuture<?> task = start(context, source, startGate, cleanupGate);
            awaitOutput(output, "armed");
            assertTrue(task.cancel(false));
            awaitOutput(output, "finally-pending");
            assertFalse(task.isDone(), "cleanup must actually suspend first");

            cleanupGate.close(); // Complete pending read with a real failure.
            ExecutionException reported = assertThrows(ExecutionException.class,
                    () -> task.get(5, TimeUnit.SECONDS));
            assertInstanceOf(ChannelRuntime.ChannelClosedException.class, reported.getCause());
            assertTrue(java.util.Arrays.stream(reported.getCause().getSuppressed())
                            .anyMatch(OresScheduler.TaskCancelledException.class::isInstance),
                    "earlier cancellation must be retained on cleanup failure");
            assertFalse(task.isCancelled(), "cleanup failure must not be classified as clean cancellation");
            assertFalse(task.cancel(false), "terminal failure cannot replay lexical cleanup");
            assertEquals("""
                    armed
                    finally-pending
                    defer-once
                    """, output(output));
        }
    }
}
