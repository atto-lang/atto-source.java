package dev.oreslang.runtime;

import dev.oreslang.OresLanguage;
import dev.oreslang.nodes.OresEvalRootNode;
import dev.oreslang.nodes.OresHostGeneratorBridge;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(value = 35, unit = TimeUnit.SECONDS)
final class HostPolyglotGeneratorBridgeTest {
    private static final String SOURCE = """
            async fnc pending(): int { return 44; }
            async generator fnc numbers(): int {
              try {
                yield 11;
                yield 22;
                yield 33;
                yield await pending();
              } catch (err) {
              } finally {
                val int cleanup_marker = 1;
              }
              return;
            }
            """;

    @Test
    void realPolyglotJavaHostReceivesThreeYieldsAndCancelsFourth()
            throws Exception {
        Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(true).build();
        try {
            Value parsed = context.parse(Source.newBuilder(
                    OresLanguage.ID, SOURCE, "host-generator.ores").build());
            OresFuture<Integer> sourcePending = new OresFuture<>();
            HostGeneratorSession session = OresHostGeneratorBridge.open(
                    parsed, "numbers", Map.of("pending", sourcePending));

            for (int expected : new int[]{11, 22, 33}) {
                var future = session.nextStep();
                var value = future.get(5, TimeUnit.SECONDS);
                assertFalse(value.done());
                assertEquals(expected, ((Number) value.value()).intValue());
            }
            var fourth = session.nextStep();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!session.awaitingSourceFuture() && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertTrue(session.awaitingSourceFuture(),
                    "real Truffle root turn did not reach pending fourth await");
            assertFalse(fourth.isDone());
            assertTrue(fourth.cancel(false));
            session.seal();
            OresContextLifecycle.prepareForDisposal(context);
            assertTrue(session.isQuiescent());
            assertEquals(1, session.cleanupRuns());
            assertEquals(1, ((Number) session.cleanupBinding("cleanup_marker")).intValue());
            assertFalse(sourcePending.isDone(),
                    "guest cancellation must not steal host-owned pending source Future");
            context.close();
        } finally {
            try { context.close(); } catch (RuntimeException expected) { }
        }
    }

    @Test
    void forgedGeneratorControlCommandCannotMintSession() throws Exception {
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(true).build()) {
            Value parsed = context.parse(Source.newBuilder(
                    OresLanguage.ID, SOURCE, "forged-generator.ores").build());
            RuntimeException failure = assertThrows(RuntimeException.class,
                    () -> parsed.execute(
                            OresEvalRootNode.OPEN_PUBLIC_GENERATOR_BRIDGE_COMMAND,
                            "numbers", "unscoped-forgery"));
            assertTrue(failure.toString().contains("host generator")
                            || failure.toString().contains("SecurityException"),
                    failure.toString());
        }
    }
}
