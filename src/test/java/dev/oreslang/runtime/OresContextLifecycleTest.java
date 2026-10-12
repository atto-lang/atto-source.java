package dev.oreslang.runtime;

import dev.oreslang.OresLanguage;
import dev.oreslang.nodes.OresEvalRootNode;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verify the host two-phase path, not outer Polyglot direct-close behavior.
 * Failed preflight must leave the outer host context able to be entered.
 */
@Timeout(value = 35, unit = TimeUnit.SECONDS)
final class OresContextLifecycleTest {
    @Test
    void closeWhenQuiescentRetiresOuterPolyglotContext() throws Exception {
        Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(true).build();
        context.eval(Source.newBuilder(OresLanguage.ID,
                "pub routine main(): void { return; }", "preflight-ready.ores").build());
        OresContextLifecycle.closeWhenQuiescent(context);
        assertThrows(IllegalStateException.class,
                () -> context.parse(Source.newBuilder(
                        OresLanguage.ID, "pub routine main(): void { return; }",
                        "after-close.ores").build()),
                "outer context must be disposed only after successful preflight");
    }

    @Test
    void unresolvedGuestFinallyFailsPreflightWithoutDisposingOuterContext()
            throws Exception {
        String sourceText = """
                pub async fnc work(): int {
                  try {
                    val Channel<int> work = Channel.new<int>(0);
                    stdio.println("work-pending");
                    const result = await (nb readch work);
                  } catch (ignored) {
                    stdio.println("INVALID-catch");
                  } finally {
                    val Channel<int> cleanup = Channel.new<int>(0);
                    stdio.println("cleanup-pending");
                    const result = await (nb readch cleanup);
                  }
                  return 17;
                }
                """;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(true).out(output).build();
        try {
            Value value = context.parse(Source.newBuilder(OresLanguage.ID,
                    sourceText, "preflight-pending.ores").build())
                    .execute(OresEvalRootNode.INVOKE_PUBLIC_COMMAND, "work");
            OresFuture<?> future = assertInstanceOf(OresFuture.class, value.asHostObject());
            awaitOutput(output, "work-pending");
            assertTrue(future.cancel(false));
            awaitOutput(output, "cleanup-pending");
            assertFalse(future.isDone());

            RuntimeException incomplete = assertThrows(RuntimeException.class,
                    () -> OresContextLifecycle.prepareForDisposal(context));
            assertTrue(incomplete.toString().contains("unfinished cancellation cleanup"),
                    incomplete.toString());
            assertFalse(future.isDone());
            // The host API did NOT invoke Polyglot Context.close() on failed
            // preflight. Parsing can still reenter the outer Truffle context,
            // even though guest runtime task admission has been sealed.
            assertDoesNotThrow(() -> context.parse(Source.newBuilder(
                    OresLanguage.ID, "pub routine main(): void { return; }",
                    "after-failed-preflight.ores").build()));
            assertEquals("work-pending\ncleanup-pending\n",
                    output.toString(StandardCharsets.UTF_8));
        } finally {
            try { context.close(); } catch (RuntimeException expected) { }
        }
    }

    @Test
    void guestFinallyCanResumeAfterFailedHostPreflightAndDisposeOnRetry()
            throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        IsolatePolicy policy = IsolatePolicy.developer()
                .withCapabilities(IsolatePolicy.Capability.JAVA_INTEROP);
        Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(true)
                .arguments(OresLanguage.ID,
                        policy.applicationArguments(ExecutionProfile.serverJit()))
                .out(output).build();
        boolean disposed = false;
        try {
            String sourceText = """
                    define module Cleanup as
                      pub let Channel<int> gate = Channel.new<int>(1);
                    end
                    pub fnc cleanup_gate(): Channel<int> {
                      return Cleanup.gate;
                    }
                    pub async fnc work(): int {
                      try {
                        val Channel<int> workGate = Channel.new<int>(0);
                        stdio.println("work-entered");
                        const result = await (nb readch workGate);
                      } catch (ignored) {
                        stdio.println("INVALID-work-catch");
                      } finally {
                        stdio.println("finally-entered");
                        const cleanupValue = await (nb readch Cleanup.gate);
                        stdio.println("cleanup-result");
                      }
                      return 17;
                    }
                    """;
            Value guest = context.parse(Source.newBuilder(
                    OresLanguage.ID, sourceText, "retry-guest-finally.ores").build());
            Value exposed = guest.execute(OresEvalRootNode.INVOKE_PUBLIC_COMMAND,
                    "cleanup_gate");
            assertTrue(exposed.isHostObject(), "host must observe the guest-owned channel");
            Value secondLookup = guest.execute(
                    OresEvalRootNode.INVOKE_PUBLIC_COMMAND, "cleanup_gate");
            assertTrue(secondLookup.isHostObject());
            assertSame(exposed.asHostObject(), secondLookup.asHostObject(),
                    "static module fields must retain one identity per context");
            @SuppressWarnings("unchecked")
            ChannelRuntime.Channel<Long> hostAccessibleGate =
                    (ChannelRuntime.Channel<Long>) assertInstanceOf(
                            ChannelRuntime.Channel.class, exposed.asHostObject());
            Value result = guest.execute(OresEvalRootNode.INVOKE_PUBLIC_COMMAND, "work");
            OresFuture<?> task = assertInstanceOf(OresFuture.class,
                    result.asHostObject());
            awaitOutput(output, "work-entered");
            assertTrue(task.cancel(false), "cancel the owned source task");
            awaitOutput(output, "finally-entered");
            assertFalse(task.isDone());

            RuntimeException failure = assertThrows(RuntimeException.class,
                    () -> OresContextLifecycle.prepareForDisposal(context));
            assertTrue(failure.toString().contains("unfinished cancellation cleanup"),
                    failure.toString());
            assertFalse(task.isDone(), "failed preflight cannot settle cleanup");

            // The host supplies a buffered channel value through the
            // already-created guest channel object. No guest function, new
            // async task, or scheduler admission is invoked after sealing.
            assertTrue(hostAccessibleGate.tryWrite(77L));
            assertEquals(0, hostAccessibleGate.size(),
                    "authorized source cleanup must consume the original guest channel");
            assertThrows(java.util.concurrent.CancellationException.class,
                    () -> task.get(5, TimeUnit.SECONDS));
            awaitOutput(output, "cleanup-result");
            assertEquals("work-entered\nfinally-entered\ncleanup-result\n",
                    output.toString(StandardCharsets.UTF_8));

            OresContextLifecycle.closeWhenQuiescent(context);
            disposed = true;
            assertThrows(IllegalStateException.class,
                    () -> context.parse(Source.newBuilder(OresLanguage.ID,
                            "pub routine main(): void { return; }",
                            "post-retirement.ores").build()));
        } finally {
            if (!disposed) {
                try { context.close(); } catch (RuntimeException ignored) { }
            }
        }
    }

    @Test
    void publicChannelExportRequiresExplicitJavaInteropCapability() throws Exception {
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(true).build()) {
            Value source = context.parse(Source.newBuilder(
                    OresLanguage.ID, """
                    define module Internal as
                      pub let Channel<int> gate = Channel.new<int>(1);
                    end
                    pub fnc channel(): Channel<int> {
                      return Internal.gate;
                    }
                    """, "channel-export-denied.ores").build());
            RuntimeException denied = assertThrows(RuntimeException.class,
                    () -> source.execute(
                            OresEvalRootNode.INVOKE_PUBLIC_COMMAND, "channel"));
            assertTrue(denied.toString().contains("JAVA_INTEROP"),
                    "default policy must reject mutable host channel export: " + denied);
        }
    }

    private static void awaitOutput(ByteArrayOutputStream output, String marker)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!output.toString(StandardCharsets.UTF_8).contains(marker)
                && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertTrue(output.toString(StandardCharsets.UTF_8).contains(marker),
                "guest did not reach " + marker);
    }
}
