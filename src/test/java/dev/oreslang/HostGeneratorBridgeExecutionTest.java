package dev.oreslang;

import dev.oreslang.nodes.OresEvalRootNode;
import dev.oreslang.nodes.OresHostGeneratorBridge;
import dev.oreslang.runtime.OresContext;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Real Java Polyglot embedder -> scoped handoff -> typed source generator. */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
final class HostGeneratorBridgeExecutionTest {
    private static final String FLAG = "ores.host.generator.experimental";
    private static final String PROGRAM = """
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

    private static Value load(Context context, String text) throws Exception {
        Source source = Source.newBuilder(OresLanguage.ID, text,
                "java-host-generator.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        Value parsed = context.parse(source);
        parsed.execute(OresEvalRootNode.INIT_ONLY_COMMAND);
        return parsed;
    }

    @Test
    void defaultHostGeneratorBridgeRemainsFailClosed() throws Exception {
        String original = System.getProperty(FLAG);
        System.clearProperty(FLAG);
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(true).build()) {
            Value parsed = load(context, PROGRAM);
            assertThrows(SecurityException.class,
                    () -> OresHostGeneratorBridge.invokePublicGenerator(parsed, "numbers"));
            assertThrows(RuntimeException.class,
                    () -> parsed.execute(
                            OresEvalRootNode.INVOKE_PUBLIC_GENERATOR_BRIDGE_COMMAND,
                            "numbers", "forged-ticket"));
        } finally {
            if (original == null) System.clearProperty(FLAG);
            else System.setProperty(FLAG, original);
        }
    }

    @Test
    void javaHostReceivesThreeSourceTypedYieldsAndTerminalCompletion()
            throws Exception {
        String original = System.getProperty(FLAG);
        System.setProperty(FLAG, "true");
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(true).build()) {
            Value parsed = load(context, PROGRAM);
            try (OresContext.SourceHostGenerator generator =
                    OresHostGeneratorBridge.invokePublicGenerator(parsed, "numbers")) {
                for (int expected : new int[]{11, 22, 33}) {
                    var next = generator.nextStep();
                    var step = next.get(10, TimeUnit.SECONDS);
                    assertFalse(step.done());
                    assertEquals(expected, ((Number) step.value()).intValue());
                }
                var last = generator.nextStep().get(10, TimeUnit.SECONDS);
                assertTrue(last.done());
                Thread.sleep(50);
            }
        } finally {
            if (original == null) System.clearProperty(FLAG);
            else System.setProperty(FLAG, original);
        }
    }

    @Test
    void sourceLoweringRefusesUnsupportedGeneratorBeforeAnyHostPull()
            throws Exception {
        String original = System.getProperty(FLAG);
        System.setProperty(FLAG, "true");
        String unsupported = """
                pub async generator fnc numbers(): int {
                  stdio.println("INVALID-guest-start");
                  yield 1;
                  return;
                }
                """;
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(true).build()) {
            Value parsed = load(context, unsupported);
            assertThrows(RuntimeException.class,
                    () -> OresHostGeneratorBridge.invokePublicGenerator(parsed, "numbers"));
        } finally {
            if (original == null) System.clearProperty(FLAG);
            else System.setProperty(FLAG, original);
        }
    }
}
