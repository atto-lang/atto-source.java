package dev.oreslang;

import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
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
final class GuestScalarActorInvocationLanguageTest {
    @Test
    void evaluatorBindsPrimitiveCallArgumentsFromActorFrameStorage() throws Exception {
        Source source = Source.newBuilder(OresLanguage.ID, """
                fnc add4(int a, int b, int c, int d): int {
                  return a + b + c + d;
                }

                pub routine main(): void {
                  stdio.stdout.write(add4(1, 2, 3, 4));
                  return;
                }
                """, "typed-actor-call-frame.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        var config = new ActorRuntime.DispatcherConfig(1, 1, 8);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            AtomicInteger peak = new AtomicInteger();
            var actor = runtime.<String>spawnShared(factory -> (message, turn) -> {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                try (Context context = IsolatePolicy.developer()
                        .restrictedContextBuilder(ExecutionProfile.serverJit())
                        .out(output)
                        .build()) {
                    context.eval(source);
                }
                assertEquals("10", output.toString(StandardCharsets.UTF_8));
                peak.set(ActorRuntime.currentActorFrameArena().peakBytes());
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
