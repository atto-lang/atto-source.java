package dev.oreslang;

import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.nodes.OresEvalRootNode;
import dev.oreslang.runtime.ActorFrameTestProbe;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Authored compiler/evaluator integration check using the Java backend's
 * currently supported legacy .ores source extension.
 *
 * This is NOT a canonical .atto actor A/B stack-overflow proof, nor an
 * independently switchable interpreter stack. It requires stepful Maven CI.
 */
// Host-owned instrumentation captures are intentional; never use this factory path in guest lowering.
final class GuestScalarActorInvocationLanguageTest {
    @Test
    void evaluatorBindsPrimitiveCallArgumentsFromActorFrameStorage() throws Exception {
        Source source = Source.newBuilder(OresLanguage.ID, """
                fnc add4(int a, int b, int c, int d): int {
                  return a + b + c + d;
                }

                pub fnc invoke(): int {
                  return add4(1, 2, 3, 4);
                }

                pub routine main(): void {
                  return;
                }
                """, "typed-actor-call-frame.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        var config = new ActorRuntime.DispatcherConfig(1, 1, 8);
        // Context creation and language initialization install supervisor-only
        // retirement hooks. Keep that work on the host, never inside actor code.
        // The actual guest evaluation still executes on the actor carrier.
        try (Context context = IsolatePolicy.developer()
                .restrictedContextBuilder(ExecutionProfile.serverJit())
                .build();
             ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            context.initialize(OresLanguage.ID);
            var unit = context.parse(source);
            unit.execute(OresEvalRootNode.LINK_ONLY_COMMAND);
            unit.execute(OresEvalRootNode.INIT_ONLY_COMMAND);
            AtomicInteger peak = new AtomicInteger();
            var actor = runtime.<String>spawnSharedTrusted(factory -> (message, turn) -> {
                // Invoke the checked synchronous function on this actor, not
                // the VM root-main scheduler (which requires a host wait).
                assertEquals(10L, unit.execute(
                        OresEvalRootNode.INVOKE_PUBLIC_COMMAND, "invoke").asLong());
                peak.set(ActorFrameTestProbe.currentPeakBytes());
                turn.self().stop();
            });
            actor.send("execute");
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty(),
                    () -> "compiler-backed legacy guest failed: " + actor.failure());
            assertTrue(peak.get() >= 16 + 4 * 9,
                    "the four typed scalar guest args must occupy a real actor frame");
        }
    }
}
