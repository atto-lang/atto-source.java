package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Actual checked Atto/Ores source: task blocks created by actors dispatch back
 * through actor mailboxes and can await nested tasks without borrowing self.
 */
@Timeout(25)
final class SourceActorTaskExecutionTest {
    @Test
    void checkedSharedActorCanLaunchNestedSourceTasks() throws Exception {
        assertEquals("nested|parent|done", run(actorProgram("actor")));
    }

    @Test
    void checkedPrivateActorCanLaunchNestedSourceTasks() throws Exception {
        assertEquals("nested|parent|done", run(actorProgram("isoactor")));
    }

    @Test
    void actorTaskCannotCaptureSelfOrMailboxAuthority() {
        for (String body : java.util.List.of(
                "self.end();",
                "stdio.stdout.write(mail.value);")) {
            String program = """
                    define actor Worker as
                      receive(ActorMail<String> mail): void {
                        let Future<void> child = task {
                          """ + body + """
                          
                          return;
                        };
                        return;
                      }
                    end
                    pub routine main(): void { return; }
                    """;
            Throwable error = assertThrows(Throwable.class,
                    () -> OresCompiler.parseAndTypeCheck(program));
            String details = String.valueOf(error.getMessage()).toLowerCase();
            assertTrue(details.contains("capture") || details.contains("actor")
                            || details.contains("send") || details.contains("borrow"),
                    "expected fail-closed actor authority rejection: " + error);
        }
    }

    private static String actorProgram(String kind) {
        return "define " + kind + " Worker as\n"
                + """
                    receive(ActorMail<String> mail): void {
                      if mail.value == "start"; do
                        let Future<void> outer = task {
                          let Future<void> nested = task {
                            stdio.stdout.write("nested|");
                            return;
                          };
                          await nested;
                          stdio.stdout.write("parent|");
                          return;
                        };
                      elif mail.value == "finish"; do
                        self.end();
                      fi
                      return;
                    }
                    end

                    pub async routine main(): void {
                      val worker = spawn Worker();
                      await worker.get_ready();
                      worker.send("start");
                      worker.send("finish");
                      await worker.get_done();
                      stdio.stdout.write("done");
                      return;
                    }
                    """;
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(
                OresLanguage.ID, program, "actor-local-task.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
