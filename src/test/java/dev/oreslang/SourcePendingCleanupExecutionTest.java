package dev.oreslang;

import dev.oreslang.nodes.OresEvalRootNode;
import dev.oreslang.runtime.ChannelRuntime;
import dev.oreslang.runtime.OresFuture;
import dev.oreslang.runtime.OresScheduler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;
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
    private static final String GATES = """
            define module gates as
              pub val Channel<int> startGate = Channel.new<int>(0);
              pub val Channel<int> finallyGate = Channel.new<int>(0);
              pub val Channel<int> deferGate = Channel.new<int>(0);
            end

            pub fnc exposeStartGate(): Channel<int> { return gates.startGate; }
            pub fnc exposeFinallyGate(): Channel<int> { return gates.finallyGate; }
            pub fnc exposeDeferGate(): Channel<int> { return gates.deferGate; }
            """;

    private static final String NESTED_PENDING_CLEANUP = """
            pub async fnc work(): int {
              defer stdio.println(await (nb readch gates.deferGate));
              try {
                stdio.println("armed");
                const ignored = await (nb readch gates.startGate);
                stdio.println("INVALID-resumed-original-work");
              } catch (cancelled) {
                stdio.println("INVALID-caught-cancellation");
              } finally {
                stdio.println("finally-pending");
                const cleaned = await (nb readch gates.finallyGate);
                stdio.println(cleaned);
                stdio.println("finally-done");
              }
              return 17;
            }
            """;

    private record Started(OresFuture<?> task,
                           ChannelRuntime.Channel<Integer> startGate,
                           ChannelRuntime.Channel<Integer> finallyGate,
                           ChannelRuntime.Channel<Integer> deferGate) { }

    @SuppressWarnings("unchecked")
    private static Started start(Context context, String source) throws Exception {
        Source guest = Source.newBuilder(OresLanguage.ID, GATES + source,
                "source-pending-cleanup.ores").mimeType(OresLanguage.MIME_TYPE).build();
        Value command = context.parse(guest);
        command.execute(OresEvalRootNode.INIT_ONLY_COMMAND);
        // Export the guest-created channels before the task starts; after that
        // Java only completes channel futures and never enters guest code.
        ChannelRuntime.Channel<Integer> start =
                (ChannelRuntime.Channel<Integer>) command.execute(
                        OresEvalRootNode.INVOKE_PUBLIC_COMMAND, "exposeStartGate").asHostObject();
        ChannelRuntime.Channel<Integer> fin =
                (ChannelRuntime.Channel<Integer>) command.execute(
                        OresEvalRootNode.INVOKE_PUBLIC_COMMAND, "exposeFinallyGate").asHostObject();
        ChannelRuntime.Channel<Integer> defer =
                (ChannelRuntime.Channel<Integer>) command.execute(
                        OresEvalRootNode.INVOKE_PUBLIC_COMMAND, "exposeDeferGate").asHostObject();
        assertSame(fin, command.execute(OresEvalRootNode.INVOKE_PUBLIC_COMMAND,
                "exposeFinallyGate").asHostObject(),
                "reading a module field must reuse its exact process-local channel identity");
        Value result = command.execute(OresEvalRootNode.INVOKE_PUBLIC_COMMAND, "work");
        assertTrue(result.isHostObject(), "async source invocation must return a host Future");
        return new Started(assertInstanceOf(OresFuture.class, result.asHostObject()),
                start, fin, defer);
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

    @RepeatedTest(12)
    void cancellationWaitsForTwoGenuinelyPendingCleanupFuturesInLifoOrder() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                     .allowAllAccess(true).out(output).build()) {
            Started started = start(context, NESTED_PENDING_CLEANUP);
            OresFuture<?> task = started.task();
            ChannelRuntime.Channel<Integer> startGate = started.startGate();
            ChannelRuntime.Channel<Integer> finallyGate = started.finallyGate();
            ChannelRuntime.Channel<Integer> deferGate = started.deferGate();
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
    void onlyOriginalTrustedEmbedderAndActualCarrierHaveConcurrentThreadAdmission()
            throws Exception {
        OresLanguage language = new OresLanguage();
        java.lang.reflect.Field owner = OresLanguage.class.getDeclaredField(
                "trustedEmbedderThread");
        owner.setAccessible(true);
        owner.set(language, Thread.currentThread());
        Thread unrelated = Thread.ofPlatform().unstarted(() -> { });
        assertTrue(language.isThreadAccessAllowed(Thread.currentThread(), false),
                "original embedding thread remains entitled to unwrap an async Future");
        assertFalse(language.isThreadAccessAllowed(unrelated, false),
                "unregistered host thread cannot enter an occupied guest context");
        try (OresScheduler scheduler = new OresScheduler(1)) {
            assertEquals(17, scheduler.start(resume -> {
                assertTrue(language.isThreadAccessAllowed(Thread.currentThread(), false),
                        "scheduler-owned carrier remains admitted");
                assertFalse(language.isThreadAccessAllowed(unrelated, false),
                        "carrier's thread-local authority may not be projected to another thread");
                return OresScheduler.done(17);
            }).get(5, TimeUnit.SECONDS));
        }
        owner.set(language, null);
        assertFalse(language.isThreadAccessAllowed(Thread.currentThread(), false),
                "adversarial mode must not inherit trusted host re-entry authority");
        assertTrue(language.isThreadAccessAllowed(Thread.currentThread(), true),
                "sequential single-thread access is unaffected");
    }

    @Test
    void failingPendingFinallyRetainsCancellationAndDoesNotRunDeferTwice()
            throws Exception {
        String source = """
                pub async fnc work(): int {
                  defer stdio.println("defer-once");
                  try {
                    stdio.println("armed");
                    const ignored = await (nb readch gates.startGate);
                  } catch (cancelled) {
                    stdio.println("INVALID-caught-cancellation");
                  } finally {
                    stdio.println("finally-pending");
                    const cleaned = await (nb readch gates.finallyGate);
                    stdio.println("INVALID-finally-completed");
                  }
                  return 17;
                }
                """;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                     .allowAllAccess(true).out(output).build()) {
            Started started = start(context, source);
            OresFuture<?> task = started.task();
            ChannelRuntime.Channel<Integer> cleanupGate = started.finallyGate();
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
