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
}
