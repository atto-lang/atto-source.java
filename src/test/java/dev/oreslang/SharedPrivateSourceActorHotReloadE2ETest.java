package dev.oreslang;

import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.OresContext;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guest-source execution, not a host-only gate or physical sandbox proof.
 * Verify that code-image sharing never aliases actor-owned state across
 * SHARED or PRIVATE actors while one actor changes code generation.
 */
@Timeout(45)
final class SharedPrivateSourceActorHotReloadE2ETest {
    private static final String V1 = """
            define actor Counter as
              let int count;
              on_start(): void {
                self.count = 1;
                return;
              }
              receive(ActorMail<int> mail): void {
                self.count = self.count + mail.value;
                self.send(self.count);
                return;
              }
            end
            pub routine main(): void { return; }
            """;

    private static final String V2 = """
            define actor Counter as
              let int count;
              on_start(): void {
                self.count = 999;
                return;
              }
              receive(ActorMail<int> mail): void {
                if mail.value < 0; do
                  self.count = self.count - mail.value;
                else
                  self.count = self.count + mail.value;
                fi
                self.send(self.count);
                return;
              }
            end
            pub routine main(): void {
              val version = 2;
              return;
            }
            """;

    private static Source guest(String name, String text) throws Exception {
        return Source.newBuilder(OresLanguage.ID, text, name)
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
    }

    private static long next(ActorRuntime.ActorRef<Object> actor) throws Exception {
        var item = actor.outputs().next().get(5, TimeUnit.SECONDS);
        assertFalse(item.done(), "actor output ended during hot reload");
        return assertInstanceOf(Long.class, item.value().value());
    }

    private static void upgradeEventually(
            ActorRuntime runtime,
            ActorRuntime.ActorRef<Object> actor,
            ActorRuntime.ActorCodeExecutor nextImage) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!runtime.upgradeSourceActorSameSchema(actor, nextImage)) {
            if (System.nanoTime() >= deadline) {
                fail("actor never became physically quiescent for code switch");
            }
            Thread.sleep(2);
        }
    }

    @Test
    void realGuestCodeReloadsSharedAndPrivateWithoutCrossActorStateMutation()
            throws Exception {
        for (ActorRuntime.ActorKind kind : List.of(
                ActorRuntime.ActorKind.SHARED,
                ActorRuntime.ActorKind.PRIVATE)) {
            try (Context guest = IsolatePolicy.developer()
                    .restrictedContextBuilder(ExecutionProfile.serverJit()).build()) {
                guest.eval(guest("counter-v1.ores", V1));
                guest.eval(guest("counter-v2.ores", V2));

                OresContext ores;
                ActorRuntime.ActorCodeExecutor v1;
                ActorRuntime.ActorCodeExecutor v2;
                guest.enter();
                try {
                    ores = OresLanguage.currentEnteredContextForHost();
                    v1 = assertInstanceOf(
                            ActorRuntime.ActorCodeExecutor.class,
                            ores.linkedCodeUnit("counter-v1.ores"));
                    v2 = assertInstanceOf(
                            ActorRuntime.ActorCodeExecutor.class,
                            ores.linkedCodeUnit("counter-v2.ores"));
                } finally {
                    guest.leave();
                }

                ActorRuntime runtime = ores.actors();
                assertNotSame(v1.codeImage(), v2.codeImage(),
                        "upgrade must select a distinct compiled code image");
                var a = runtime.spawnCodeActor(
                        kind, ores.isolatePolicy(), v1, "Counter");
                var b = runtime.spawnCodeActor(
                        kind, ores.isolatePolicy(), v1, "Counter");
                var aAddress = a.address();
                var bAddress = b.address();
                assertNotEquals(aAddress, bAddress);
                a.getReady().get(5, TimeUnit.SECONDS);
                b.getReady().get(5, TimeUnit.SECONDS);

                // Compiled code is reusable; per-actor state is not.
                a.send(41L);
                b.send(9L);
                assertEquals(42L, next(a));
                assertEquals(10L, next(b));

                // Upgrade just A. B must continue using the old handler.
                upgradeEventually(runtime, a, v2);
                assertEquals(aAddress, a.address());
                assertEquals(bAddress, b.address());
                a.send(-1L);
                b.send(-1L);
                assertEquals(43L, next(a),
                        "A must use new code on restored state, not rerun on_start");
                assertEquals(9L, next(b),
                        "B must retain its own state and old code generation");

                // Upgrade B independently, without rewinding A.
                upgradeEventually(runtime, b, v2);
                assertEquals(aAddress, a.address());
                assertEquals(bAddress, b.address());
                a.send(-2L);
                b.send(-2L);
                assertEquals(45L, next(a));
                assertEquals(11L, next(b));
                a.stop();
                b.stop();
                assertTrue(a.awaitTermination(5, TimeUnit.SECONDS));
                assertTrue(b.awaitTermination(5, TimeUnit.SECONDS));
                assertTrue(a.failure().isEmpty());
                assertTrue(b.failure().isEmpty());
            }
        }
    }
}
