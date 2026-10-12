package dev.oreslang;

import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.OresContext;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(25)
final class SourceActorStructuredCleanupGuestTest {
    @Test
    void hostStopUnwindsRealTypedMatchArmDeferAndFinally() throws Exception {
        String program = """
            define serial actor Worker as
              pub static async fnc waitForever(): int {
                val Channel<int> pending = Channel.new<int>(0);
                return await (nb readch pending);
              }
              suspends receive(ActorMail<String> mail): void {
                const result: int = match<ret=int> 7 over {
                  default {
                    defer stdio.println("arm-defer");
                    stdio.println("arm-entered");
                    try {
                      return await Worker.waitForever();
                    } catch (err) {
                      return 0;
                    } finally {
                      stdio.println("finally-once");
                    }
                  }
                };
                stdio.println("published");
                stdio.println(result);
                self.end();
                return;
              }
            end
            pub fnc main(): void { return; }
            """;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(out).build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, program,
                    "structured-actor-cleanup.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build());
            ActorRuntime actors;
            ActorRuntime.ActorCodeExecutor guest;
            context.enter();
            try {
                OresContext current = OresContext.get(null);
                actors = current.actors();
                guest = (ActorRuntime.ActorCodeExecutor)
                        current.linkedCodeUnit("structured-actor-cleanup.ores");
                assertNotNull(guest);
            } finally {
                context.leave();
            }
            var worker = actors.spawnCodeActor(
                    ActorRuntime.ActorKind.SHARED, guest, "Worker");
            worker.send("start");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!out.toString(StandardCharsets.UTF_8).contains("arm-entered")
                    && System.nanoTime() < deadline) Thread.sleep(1);
            assertTrue(out.toString(StandardCharsets.UTF_8).contains("arm-entered"));
            Thread.sleep(50); // pending readch has no writer
            worker.stop();
            assertTrue(worker.awaitTermination(8, TimeUnit.SECONDS));
            assertTrue(worker.failure().isEmpty(), String.valueOf(worker.failure()));
        }
        String result = out.toString(StandardCharsets.UTF_8);
        assertEquals(1, result.lines().filter("finally-once"::equals).count(), result);
        assertEquals(1, result.lines().filter("arm-defer"::equals).count(), result);
        assertFalse(result.contains("published"), result);
    }
}
