package dev.oreslang.nodes;

import dev.oreslang.OresLanguage;
import dev.oreslang.runtime.OresFuture;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.PolyglotException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real Polyglot boundary test: no source task may enter its physical CONTROL
 * carrier until the public host Value.execute invocation has exited.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
final class OresHostFutureDeferredDispatchExecutionTest {
    private static final String SOURCE = """
            pub async fnc work(): int {
              stdio.println("source-entered");
              return 21;
            }
            """;

    private static Value parseAndInitialize(Context context) throws java.io.IOException {
        Value value = context.parse(Source.newBuilder(OresLanguage.ID, SOURCE,
                        "host-deferred-entry.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build());
        value.execute(OresEvalRootNode.INIT_ONLY_COMMAND);
        return value;
    }

    @Test
    void controlSourceCannotBeginBeforeHostExecuteReturns() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(true).out(output).build()) {
            Value parsed = parseAndInitialize(context);
            OresFuture<Number> future =
                    OresHostFutureBridge.invokePublicFutureAfterHostExit(
                            parsed, "work", () -> assertEquals("",
                                    output.toString(StandardCharsets.UTF_8),
                                    "host return probe must run before CONTROL guest entry"));
            assertEquals(21L, future.get(5, TimeUnit.SECONDS).longValue());
            assertEquals("source-entered\n", output.toString(StandardCharsets.UTF_8));
        }
    }

    @Test
    void failedHostReturnAbandonsGuestWithoutExecutingSourceBody() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(true).out(output).build()) {
            Value parsed = parseAndInitialize(context);
            IllegalStateException failure = new IllegalStateException("host exit lost");
            assertSame(failure, assertThrows(IllegalStateException.class,
                    () -> OresHostFutureBridge.invokePublicFutureAfterHostExit(
                            parsed, "work", () -> { throw failure; })));
            assertEquals("", output.toString(StandardCharsets.UTF_8),
                    "failed host handoff must not leave an orphaned producer");
        }
    }

    @Test
    void invalidFutureBridgeTargetStartsNoSourceProducer() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(true).out(output).build()) {
            Value parsed = parseAndInitialize(context);
            PolyglotException denied = assertThrows(PolyglotException.class,
                    () -> OresHostFutureBridge.invokePublicFuture(parsed, "noSuchMethod"));
            assertTrue(denied.getMessage().contains("noSuchMethod"));
            assertEquals("", output.toString(StandardCharsets.UTF_8));
        }
    }
}
