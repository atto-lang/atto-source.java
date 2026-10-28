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
}
