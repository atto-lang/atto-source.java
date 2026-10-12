package dev.oreslang.runtime;

import dev.oreslang.OresLanguage;
import dev.oreslang.nodes.OresEvalRootNode;
import dev.oreslang.nodes.OresHostFutureBridge;
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
                  } catch (cancelled) {
                    stdio.println("INVALID-caught-cancellation");
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
            Value parsed = context.parse(Source.newBuilder(OresLanguage.ID,
                    sourceText, "preflight-pending.ores").build());
            // Use the explicit host-side Future capture. Calling Value.asHostObject()
            // after scheduler dispatch can race Truffle's context admission.
            OresFuture<?> future = OresHostFutureBridge.invokePublicFuture(parsed, "work");
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
