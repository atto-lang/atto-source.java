package dev.oreslang;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Timeout(20)
final class ActorScopedTaskExecutionTest {

    @Test
    void trustedActorScopeJoinsChildBeforeParentContinues() throws Exception {
        assertEquals("child:parent", run("""
                define actor Worker as
                  receive(ActorMail<String> mail): void {
                    nb scope {
                      do nb {
                        stdio.stdout.write("child");
                      };
                    }
                    stdio.stdout.write(":parent");
                    self.end();
                    return;
                  }
                end

                pub async routine main(): void {
                  val worker = spawn Worker();
                  await worker.get_ready();
                  worker.send("run");
                  await worker.get_done();
                  return;
                }
                """, "actor-scoped-task-trusted.ores"));
    }


    @Test
    void nestedActorScopesDrainGrandchildBeforeParentContinues() throws Exception {
        assertEquals("nested:parent", run("""
                define actor Worker as
                  receive(ActorMail<String> mail): void {
                    nb scope {
                      do nb {
                        nb scope {
                          do nb {
                            stdio.stdout.write("nested");
                          };
                        }
                      };
                    }
                    stdio.stdout.write(":parent");
                    self.end();
                    return;
                  }
                end

                pub async routine main(): void {
                  val worker = spawn Worker();
                  await worker.get_ready();
                  worker.send("run");
                  await worker.get_done();
                  return;
                }
                """, "actor-scoped-task-nested.ores"));
    }

    @Test
    void untrustedActorScopePreservesTimedTerminalAuthorityAndDrains() throws Exception {
        assertEquals("done", run("""
                define untrusted actor Worker as
                  receive(ActorMail<String> mail): void {
                    nb scope {
                      let child = nb {
                        return;
                      };
                    }
                    self.end();
                    return;
                  }
                end

                pub async routine main(): void {
                  val worker = spawn Worker();
                  await worker.get_ready();
                  worker.send("run");
                  await worker.get_done();
                  stdio.stdout.write("done");
                  return;
                }
                """, "actor-scoped-task-untrusted.ores"));
    }

    private static String run(String sourceText, String name) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, sourceText, name)
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
