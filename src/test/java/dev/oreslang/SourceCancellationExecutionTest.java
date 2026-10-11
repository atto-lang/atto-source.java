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

    @Test
    void failingInnerFinallyDoesNotTurnCancellationIntoAnOuterCatchableError()
            throws Exception {
        String source = """
                pub async fnc work(): int {
                  try {
                    try {
                      val Channel<int> gate = Channel.new<int>(0);
                      stdio.println("armed");
                      const unused = await (nb readch gate);
                    } catch (inner) {
                      stdio.println("INVALID-inner-catch");
                    } finally {
                      stdio.println("inner-finally");
                      const failure = 1 % 0;
                    }
                  } catch (outer) {
                    stdio.println("INVALID-outer-catch");
                  } finally {
                    stdio.println("outer-finally");
                  }
                  return 1;
                }
                """;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(true).out(output).build()) {
            OresFuture<?> task = start(context, source);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!output.toString(StandardCharsets.UTF_8).contains("armed")
                    && System.nanoTime() < deadline) Thread.sleep(1);
            assertFalse(task.isDone(), "worker should still await its rendezvous channel");
            assertTrue(task.cancel(false));

            var observed = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> task.get(5, TimeUnit.SECONDS));
            Throwable failure = observed.getCause();
            assertInstanceOf(ArithmeticException.class, failure);
            assertTrue(java.util.Arrays.stream(failure.getSuppressed())
                            .anyMatch(dev.oreslang.runtime.OresScheduler.TaskCancelledException.class::isInstance),
                    "cleanup failure must preserve cancellation as suppressed evidence");
            assertFalse(task.isCancelled(),
                    "an explicitly failed cleanup must not be misreported as clean cancellation");
            assertEquals("""
                    armed
                    inner-finally
                    outer-finally
                    """, output.toString(StandardCharsets.UTF_8));
        }
    }

    @Test
    void cancelledGuestCleanupAwaitsReadyFuturesInLifoOrder() throws Exception {
        String source = """
                pub async fnc work(): int {
                  val Channel<int> deferred = Channel.new<int>(1);
                  writech deferred, 22;
                  defer stdio.println(await (nb readch deferred));
                  try {
                    val Channel<int> gate = Channel.new<int>(0);
                    stdio.println("armed");
                    const unused = await (nb readch gate);
                    stdio.println("INVALID-after-await");
                  } catch (cancelled) {
                    stdio.println("INVALID-catch");
                  } finally {
                    val Channel<int> ready = Channel.new<int>(1);
                    writech ready, 33;
                    const cleaned = await (nb readch ready);
                    stdio.println(cleaned);
                  }
                  return 17;
                }
                """;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(true).out(output).build()) {
            OresFuture<?> task = start(context, source);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!output.toString(StandardCharsets.UTF_8).contains("armed")
                    && System.nanoTime() < deadline) Thread.sleep(1);
            assertTrue(output.toString(StandardCharsets.UTF_8).contains("armed"));
            assertFalse(task.isDone());
            assertTrue(task.cancel(false));
            assertFalse(task.cancel(false), "a second cancellation cannot replay cleanup");
            assertThrows(CancellationException.class,
                    () -> task.get(5, TimeUnit.SECONDS));
            assertEquals("""
                    armed
                    33
                    22
                    """, output.toString(StandardCharsets.UTF_8));
        }
    }

    @Test
    void contextCloseWhileGuestFinallyAwaitsPendingFutureMustFailClosed() throws Exception {
        String source = """
                pub async fnc work(): int {
                  try {
                    val Channel<int> gate = Channel.new<int>(0);
                    stdio.println("armed");
                    const ignored = await (nb readch gate);
                    stdio.println("INVALID-work");
                  } catch (reason) {
                    stdio.println("INVALID-catch");
                  } finally {
                    val Channel<int> cleanup = Channel.new<int>(0);
                    stdio.println("cleanup-armed");
                    const result = await (nb readch cleanup);
                    stdio.println("INVALID-cleanup-completed");
                  }
                  return 42;
                }
                """;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(true).out(output).build();
        try {
            OresFuture<?> task = start(context, source);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!output.toString(StandardCharsets.UTF_8).contains("armed")
                    && System.nanoTime() < deadline) Thread.sleep(1);
            assertTrue(output.toString(StandardCharsets.UTF_8).contains("armed"));
            assertTrue(task.cancel(false));
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!output.toString(StandardCharsets.UTF_8).contains("cleanup-armed")
                    && System.nanoTime() < deadline) Thread.sleep(1);
            assertTrue(output.toString(StandardCharsets.UTF_8).contains("cleanup-armed"),
                    "guest finally must actually suspend before shutdown");
            assertFalse(task.isDone());
            RuntimeException failure = assertThrows(RuntimeException.class, context::close,
                    "context close cannot claim successful teardown with a pending finally");
            assertTrue(failure.toString().contains("unfinished cancellation cleanup"),
                    "incomplete cleanup must remain an explicit shutdown error: " + failure);
            assertFalse(task.isDone(),
                    "context shutdown failure must not fabricate terminal guest cleanup");
            assertEquals("armed\ncleanup-armed\n",
                    output.toString(StandardCharsets.UTF_8));
        } finally {
            // Intentionally unresolved source cleanup: do not treat a second
            // close failure as success or override the test's primary error.
            try { context.close(); } catch (RuntimeException expected) { }
        }
    }
}
