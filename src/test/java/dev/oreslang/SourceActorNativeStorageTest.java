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
                            stdio.stdout.write(self.count);
                            self.end();
                            return;
                          }
                        end
                        pub async routine main(): void {
                          val left = spawn Counter();
                          await left.get_ready();
                          left.send("go");
                          await left.get_done();
                          stdio.stdout.write("|");
                          val right = spawn Counter();
                          await right.get_ready();
                          right.send("go");
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
    void realGuestOverlappingActorsKeepNativeFieldStateIndependent() throws Exception {
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

            // Both actor states exist BEFORE either receives a message.
            // A shared process-wide/native slot would produce "12" or "21",
            // while two independent actor fields must each print one.
            context.eval(Source.newBuilder(OresLanguage.ID, """
                    define actor Counter as
                      let int count;
                      on_start(): void { self.count = 0; return; }
                      receive(ActorMail<String> mail): void {
                        self.count = self.count + 1;
                        stdio.stdout.write(self.count);
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
                      await left.get_done();
                      await right.get_done();
                      return;
                    }
                    """, "native-overlapping-actors.ores").build());
            assertEquals("11", output.toString(StandardCharsets.UTF_8));
            assertEquals(2, actors.nativeActorArenasCreated());
            assertEquals(0, actors.nativeActorMemoryBytes(),
                    "both physical retirements must refund their native field budgets");
        }
    }
}
