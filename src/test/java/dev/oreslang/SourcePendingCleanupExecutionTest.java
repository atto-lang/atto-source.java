package dev.oreslang;

import dev.oreslang.nodes.OresEvalRootNode;
import dev.oreslang.nodes.OresHostFutureBridge;
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
        // The Future is captured before Truffle Value.execute returns. Never
        // enter Polyglot Value.asHostObject once the source task can execute.
        OresFuture<?> task = OresHostFutureBridge.invokePublicFuture(command, "work");
        return new Started(task, start, fin, defer);
    }


    /**
     * The guest's scheduler runs on a different carrier from the host test.
     * ByteArrayOutputStream is not thread-safe, so polling an unsynchronized
     * buffer would be a data race even when the guest runtime is correct.
     */
    private static final class SafeOutput extends ByteArrayOutputStream {
        @Override public synchronized void write(int value) {
            super.write(value);
        }
        @Override public synchronized void write(byte[] bytes, int off, int len) {
            super.write(bytes, off, len);
        }
        synchronized String snapshot() {
            return toString(StandardCharsets.UTF_8);
        }
    }

    /** Test-only rendezvous probe. Read registrations under their real lock. */
    private static int activeChannelRegistrations(ChannelRuntime.Channel<?> channel)
            throws Exception {
        java.lang.reflect.Field coordinator =
                ChannelRuntime.class.getDeclaredField("COORDINATOR");
        coordinator.setAccessible(true);
        java.util.concurrent.locks.ReentrantLock lock =
                (java.util.concurrent.locks.ReentrantLock) coordinator.get(null);
        java.lang.reflect.Field registrations =
                ChannelRuntime.Channel.class.getDeclaredField("registrations");
        registrations.setAccessible(true);
        lock.lock();
        try {
            return ((java.util.Collection<?>) registrations.get(channel)).size();
        } finally {
            lock.unlock();
        }
    }

    private static void awaitChannelRegistration(ChannelRuntime.Channel<?> channel)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (activeChannelRegistrations(channel) == 1) return;
            Thread.sleep(1);
        }
        assertEquals(1, activeChannelRegistrations(channel),
                "guest direct blocking operation never registered with its channel");
    }

    private static String output(SafeOutput bytes) {
        return bytes.snapshot();
    }

    private static void awaitOutput(SafeOutput output, String marker) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!output(output).contains(marker) && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertTrue(output(output).contains(marker), "guest never reached " + marker + "; output: " + output(output));
    }

    @Test
    void cancellationWaitsForTwoGenuinelyPendingCleanupFuturesInLifoOrder() throws Exception {
        SafeOutput output = new SafeOutput();
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
        SafeOutput output = new SafeOutput();
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

    @Test
    void cancelledDirectBlockingReadRevokesItsOwnRendezvousRegistration()
            throws Exception {
        String source = """
                pub async fnc work(): int {
                  try {
                    stdio.println("armed");
                    const ignored = readch gates.startGate;
                    stdio.println("INVALID-read-resumed");
                  } catch (cancelled) {
                    stdio.println("INVALID-caught");
                  } finally {
                    stdio.println("cleanup-once");
                  }
                  return 17;
                }
                """;
        SafeOutput output = new SafeOutput();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                     .allowAllAccess(true).out(output).build()) {
            Started started = start(context, source);
            awaitOutput(output, "armed");
            awaitChannelRegistration(started.startGate());
            assertTrue(started.task().cancel(false));
            assertThrows(CancellationException.class,
                    () -> started.task().get(5, TimeUnit.SECONDS));
            assertEquals(0, activeChannelRegistrations(started.startGate()),
                    "cancellation must revoke the owned pending blocking receive");
            assertFalse(started.startGate().tryWrite(99),
                    "late producer must not deliver into an abandoned read");
            assertEquals("armed\ncleanup-once\n", output(output));
        }
    }

    @Test
    void cancelledDirectBlockingWriteRevokesItsOwnRendezvousRegistration()
            throws Exception {
        String source = """
                pub async fnc work(): int {
                  try {
                    stdio.println("armed");
                    writech gates.startGate, 77;
                    stdio.println("INVALID-write-resumed");
                  } catch (cancelled) {
                    stdio.println("INVALID-caught");
                  } finally {
                    stdio.println("cleanup-once");
                  }
                  return 17;
                }
                """;
        SafeOutput output = new SafeOutput();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                     .allowAllAccess(true).out(output).build()) {
            Started started = start(context, source);
            awaitOutput(output, "armed");
            awaitChannelRegistration(started.startGate());
            assertTrue(started.task().cancel(false));
            assertThrows(CancellationException.class,
                    () -> started.task().get(5, TimeUnit.SECONDS));
            assertEquals(0, activeChannelRegistrations(started.startGate()),
                    "cancellation must revoke the owned pending blocking send");
            assertTrue(started.startGate().tryRead().isEmpty(),
                    "late reader must not consume an abandoned channel write");
            assertEquals("armed\ncleanup-once\n", output(output));
        }
    }


    @Test
    void repeatedHostAsyncLaunchesKeepInitialHostAndSchedulerCarrierAuthorized()
            throws Exception {
        // A production host invokes async work while still inside the
        // public interop call. The scheduler can begin a guest turn before
        // that call returns. This must not make Truffle reject the original
        // host thread when its language becomes multithreaded.
        for (int iteration = 0; iteration < 16; iteration++) {
            SafeOutput output = new SafeOutput();
            try (Context context = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(true).out(output).build()) {
                Started started = start(context, NESTED_PENDING_CLEANUP);
                awaitOutput(output, "armed");
                assertTrue(started.task().cancel(false),
                        "cancel was not admitted at iteration " + iteration);
                awaitOutput(output, "finally-pending");
                started.finallyGate().writeAsync(33).get(5, TimeUnit.SECONDS);
                awaitOutput(output, "finally-done");
                started.deferGate().writeAsync(22).get(5, TimeUnit.SECONDS);
                assertThrows(CancellationException.class,
                        () -> started.task().get(5, TimeUnit.SECONDS));
            }
        }
    }

}
