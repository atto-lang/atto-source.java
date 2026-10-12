package dev.oreslang.nodes;

import dev.oreslang.OresLanguage;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** Real Java Polyglot Value.execute -> scoped host generator -> root guest turns. */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
final class OresHostGeneratorBridgeExecutionTest {
    private static final String SOURCE = """
            pub async generator fnc numbers(): int {
              try {
                yield 11;
                yield 22;
                yield 33;
              } catch (err) {
              } finally {
                val int cleanup_marker = 1;
              }
              return;
            }
            """;

    private static Value initialize(Context context) throws Exception {
        Value parsed = context.parse(Source.newBuilder(
                OresLanguage.ID, SOURCE, "host-generator.ores")
                .mimeType(OresLanguage.MIME_TYPE).build());
        parsed.execute(OresEvalRootNode.INIT_ONLY_COMMAND);
        return parsed;
    }

    @Test
    void realPolyglotHostReceivesThreeCertifiedYieldsAfterHostReturn() throws Exception {
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(true).build()) {
            Value parsed = initialize(context);
            AtomicBoolean hostReturned = new AtomicBoolean();
            try (var session = OresHostGeneratorBridge.openAfterHostExit(
                    parsed, "numbers", () -> hostReturned.set(true))) {
                assertTrue(hostReturned.get(),
                        "generator session must not escape before Value.execute returns");
                for (int expected : new int[]{11, 22, 33}) {
                    var step = session.nextStep().get(5, TimeUnit.SECONDS);
                    assertFalse(step.done());
                    assertEquals(expected, ((Number) step.value()).intValue());
                }
                assertTrue(session.nextStep().get(5, TimeUnit.SECONDS).done());
            }
        }
    }

    @Test
    void unscopedOrMalformedControlCannotStartGenerator() throws Exception {
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(true).build()) {
            Value parsed = initialize(context);
            assertThrows(PolyglotException.class, () -> parsed.execute(
                    OresEvalRootNode.INVOKE_PUBLIC_GENERATOR_BRIDGE_COMMAND,
                    "numbers", "forged-nonce"));
            assertThrows(PolyglotException.class, () -> parsed.execute(
                    OresEvalRootNode.INVOKE_PUBLIC_GENERATOR_BRIDGE_COMMAND,
                    "numbers"));
            assertThrows(PolyglotException.class,
                    () -> OresHostGeneratorBridge.open(parsed, "missing"));
            assertThrows(IllegalArgumentException.class,
                    () -> OresHostGeneratorBridge.open(parsed, ""));

        }
    }
}
