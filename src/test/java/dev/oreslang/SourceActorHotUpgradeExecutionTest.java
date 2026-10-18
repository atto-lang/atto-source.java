package dev.oreslang;

import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.OresContext;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
final class SourceActorHotUpgradeExecutionTest {
    private static Source source(String name, String body) throws Exception {
        return Source.newBuilder(OresLanguage.ID, body, name)
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
    }

    private static long nextLong(ActorRuntime.ActorRef<Object> ref) throws Exception {
        var step = ref.outputs().next().get(5, TimeUnit.SECONDS);
        assertFalse(step.done(), "actor output unexpectedly ended");
        Object value = step.value().value();
        return assertInstanceOf(Long.class, value);
    }

    @Test
    void realEvaluatorActorKeepsRefAndStateWhileReceiveCodeChanges() throws Exception {
        String v1 = """
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

                pub routine main(): void {
                  return;
                }
                """;
        String v2 = """
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

        try (Context polyglot = IsolatePolicy.developer()
                .restrictedContextBuilder(ExecutionProfile.serverJit())
                .build()) {
            polyglot.eval(source("counter-v1.ores", v1));
            polyglot.eval(source("counter-v2.ores", v2));

            OresContext ores;
            ActorRuntime.ActorCodeExecutor first;
            ActorRuntime.ActorCodeExecutor second;
            polyglot.enter();
            try {
                ores = OresLanguage.currentEnteredContextForHost();
                first = assertInstanceOf(
                        ActorRuntime.ActorCodeExecutor.class,
                        ores.linkedCodeUnit("counter-v1.ores"));
                second = assertInstanceOf(
                        ActorRuntime.ActorCodeExecutor.class,
                        ores.linkedCodeUnit("counter-v2.ores"));
            } finally {
                polyglot.leave();
            }

            ActorRuntime runtime = ores.actors();
                ActorRuntime.ActorRef<Object> ref = runtime.spawnCodeActor(
                        ActorRuntime.ActorKind.SHARED,
                        ores.isolatePolicy(),
                        first,
                        "Counter");
                var originalRef = ref;
                var originalAddress = ref.address();
                ref.getReady().get(5, TimeUnit.SECONDS);

                ref.send(41L);
                assertEquals(42L, nextLong(ref));

                assertTrue(runtime.upgradeSourceActorSameSchema(ref, second));
                assertSame(originalRef, ref);
                assertEquals(originalAddress, ref.address());
                // Candidate on_start must NOT run during restore. If it did,
                // count would become 999 instead of remaining 42.
                ref.send(-1L);
                assertEquals(43L, nextLong(ref));
                ref.send(-2L);
                assertEquals(45L, nextLong(ref));

                ref.stop();
                assertTrue(ref.awaitTermination(5, TimeUnit.SECONDS));
                assertTrue(ref.failure().isEmpty());
        }
    }

    @Test
    void realEvaluatorRejectsStateWireKindAndIdenticalCodeDrift() throws Exception {
        String base = """
                define actor Counter as
                  let int count;
                  on_start(): void { self.count = 7; return; }
                  receive(ActorMail<int> mail): void {
                    self.count = self.count + mail.value;
                    self.send(self.count);
                    return;
                  }
                end
                pub routine main(): void { return; }
                """;
        String badState = """
                define actor Counter as
                  let string count;
                  on_start(): void { self.count = "seven"; return; }
                  receive(ActorMail<int> mail): void { return; }
                end
                pub routine main(): void { return; }
                """;
        String badWire = """
                define actor Counter as
                  let int count;
                  on_start(): void { self.count = 7; return; }
                  receive(ActorMail<string> mail): void { return; }
                end
                pub routine main(): void { return; }
                """;
        String badKind = """
                define isoactor Counter as
                  let int count;
                  on_start(): void { self.count = 7; return; }
                  receive(ActorMail<int> mail): void { return; }
                end
                pub routine main(): void { return; }
                """;

        try (Context polyglot = IsolatePolicy.developer()
                .restrictedContextBuilder(ExecutionProfile.serverJit())
                .build()) {
            polyglot.eval(source("base.ores", base));
            polyglot.eval(source("same-ast-different-file.ores", base));
            polyglot.eval(source("bad-state.ores", badState));
            polyglot.eval(source("bad-wire.ores", badWire));
            polyglot.eval(source("bad-kind.ores", badKind));

            OresContext ores;
            polyglot.enter();
            try {
                ores = OresLanguage.currentEnteredContextForHost();
            } finally {
                polyglot.leave();
            }
            ActorRuntime runtime = ores.actors();
            ActorRuntime.ActorCodeExecutor first = (ActorRuntime.ActorCodeExecutor)
                    ores.linkedCodeUnit("base.ores");
            ActorRuntime.ActorRef<Object> ref = runtime.spawnCodeActor(
                        ActorRuntime.ActorKind.SHARED,
                        ores.isolatePolicy(),
                        first,
                        "Counter");
                ref.getReady().get(5, TimeUnit.SECONDS);

                assertThrows(IllegalArgumentException.class, () ->
                        runtime.upgradeSourceActorSameSchema(
                                ref,
                                (ActorRuntime.ActorCodeExecutor)
                                        ores.linkedCodeUnit("same-ast-different-file.ores")));
                assertThrows(SecurityException.class, () ->
                        runtime.upgradeSourceActorSameSchema(
                                ref,
                                (ActorRuntime.ActorCodeExecutor)
                                        ores.linkedCodeUnit("bad-state.ores")));
                assertThrows(SecurityException.class, () ->
                        runtime.upgradeSourceActorSameSchema(
                                ref,
                                (ActorRuntime.ActorCodeExecutor)
                                        ores.linkedCodeUnit("bad-wire.ores")));
                assertThrows(SecurityException.class, () ->
                        runtime.upgradeSourceActorSameSchema(
                                ref,
                                (ActorRuntime.ActorCodeExecutor)
                                        ores.linkedCodeUnit("bad-kind.ores")));

                ref.send(1L);
                assertEquals(8L, nextLong(ref));
                ref.stop();
                assertTrue(ref.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
