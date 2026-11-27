package dev.oreslang;

import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.OresContext;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
final class SourceActorNativeStorageTest {
    @Test
    void nativeProfileRejectsUntrustedGuestActorBeforeArenaCreation() throws Exception {
        try (Context context = Context.newBuilder(OresLanguage.ID).allowAllAccess(false).build()) {
            context.initialize(OresLanguage.ID);
            ActorRuntime actors;
            context.enter();
            try {
                actors = OresContext.get(null).actors();
                actors.enableNativeActorStorage();
            } finally { context.leave(); }
            var failure = assertThrows(org.graalvm.polyglot.PolyglotException.class,
                    () -> context.eval(Source.newBuilder(OresLanguage.ID, """
                            define untrusted actor Worker as
                              receive(ActorMail<String> mail): void { self.end(); return; }
                            end
                            pub async routine main(): void {
                              val worker = spawn Worker();
                              await worker.get_ready();
                              return;
                            }
                            """, "native-untrusted-rejected.ores").build()));
            assertTrue(failure.getMessage().contains("not an untrusted isolate adapter"),
                    failure.getMessage());
            assertEquals(0, actors.nativeActorArenasCreated());
        }
    }

    @Test
    void realGuestPrivateAndSharedFieldsUseDistinctNativeArenas() throws Exception {
        for (String kind : new String[] {"actor", "isoactor"}) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (Context context = Context.newBuilder(OresLanguage.ID).allowAllAccess(false)
                    .out(output).build()) {
                context.initialize(OresLanguage.ID);
                ActorRuntime actors;
                context.enter();
                try {
                    actors = OresContext.get(null).actors();
                    actors.enableNativeActorStorage();
                } finally { context.leave(); }
                String program = """
                        define %s Counter as
                          let int count;
                          on_start(): void { self.count = 0; return; }
                          receive(ActorMail<String> mail): void {
                            self.count = self.count + 1;
                            self.send(self.count);
                            self.end();
                            return;
                          }
                        end
                        pub async routine main(): void {
                          val left = spawn Counter();
                          val right = spawn Counter();
                          await left.get_ready();
                          await right.get_ready();
                          left.send("go");
                          right.send("go");
                          val a = (await left.outputs.next()).value.unwrap();
                          val b = (await right.outputs.next()).value.unwrap();
                          stdio.stdout.write(a.value);
                          stdio.stdout.write("|");
                          stdio.stdout.write(b.value);
                          await left.get_done();
                          await right.get_done();
                          return;
                        }
                        """.formatted(kind);
                context.eval(Source.newBuilder(OresLanguage.ID, program, "native-fields.ores").build());
                assertEquals("1|1", output.toString(StandardCharsets.UTF_8));
                assertEquals(2, actors.nativeActorArenasCreated());
                assertEquals(0, actors.nativeActorMemoryBytes());
            }
        }
    }

    @Test
    void realGuestSameKindPeerAdvancesAfterOtherActorPhysicalDone() throws Exception {
        // Guest-native storage proof using the currently compiled Java actor
        // syntax, not a replacement for the canonical cross-backend .ores blob.
        // Both actors are simultaneously live and share the actor definition,
        // never the native field allocation.
        for (String kind : new String[] {"actor", "isoactor"}) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (Context context = Context.newBuilder(OresLanguage.ID).allowAllAccess(false)
                    .out(output).build()) {
                context.initialize(OresLanguage.ID);
                ActorRuntime actors;
                context.enter();
                try {
                    actors = OresContext.get(null).actors();
                    actors.enableNativeActorStorage();
                } finally { context.leave(); }
                String program = """
                        define %s Counter as
                          let int count;
                          on_start(): void { self.count = 0; return; }
                          receive(ActorMail<String> mail): void {
                            if mail.value == "inc"; do
                              self.count = self.count + 1;
                              self.send(self.count);
                            fi
                            if mail.value == "stop"; do
                              self.end();
                            fi
                            return;
                          }
                        end
                        pub async routine main(): void {
                          val a = spawn Counter();
                          val b = spawn Counter();
                          await a.get_ready();
                          await b.get_ready();
                          a.send("inc");
                          b.send("inc");
                          val firstA = (await a.outputs.next()).value.unwrap();
                          val firstB = (await b.outputs.next()).value.unwrap();
                          stdio.stdout.write(firstA.value);
                          stdio.stdout.write("|");
                          stdio.stdout.write(firstB.value);
                          a.send("stop");
                          // This promise must not settle until A is physically
                          // quiescent and its native actor storage is reclaimed.
                          await a.get_done();
                          b.send("inc");
                          val secondB = (await b.outputs.next()).value.unwrap();
                          stdio.stdout.write("|");
                          stdio.stdout.write(secondB.value);
                          b.send("stop");
                          await b.get_done();
                          return;
                        }
                        """.formatted(kind);
                context.eval(Source.newBuilder(OresLanguage.ID,
                        program, "native-same-kind-survivor.ores").build());
                assertEquals("1|1|2", output.toString(StandardCharsets.UTF_8),
                        "B must retain private mutable guest state after A reaches done");
                assertEquals(2, actors.nativeActorArenasCreated(),
                        "simultaneous same-kind actors must allocate distinct native arenas");
                assertEquals(0, actors.nativeActorMemoryBytes(),
                        "both actors must physically refund their native storage");
            }
        }
    }
}

