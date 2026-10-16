package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class PrivateAbstractActorHookTest {

    @Test
    void privateAbstractAllowsOverrideButOnlyBaseCanInvokeVirtualSlot() throws Exception {
        String source = """
                define abstract class Framework as
                  private abstract receive(int value): int;
                  pub deliver(int value): int {
                    return self.receive(value);
                  }
                end
                define class Worker extends Framework as
                  private receive(int value): int {
                    return value + 7;
                  }
                end
                pub routine main(): void {
                  val worker = new Worker();
                  stdio.stdout.write(worker.deliver(5));
                  return;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = IsolatePolicy.developer()
                .restrictedContextBuilder(ExecutionProfile.serverJit()).out(output).build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, source, "private-abstract.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build());
        }
        assertEquals("12", output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void derivedCodeCannotCallItsOwnPrivateAbstractOverride() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define abstract class Framework as
                          private abstract receive(int value): int;
                        end
                        define class Worker extends Framework as
                          private receive(int value): int { return value; }
                          pub bypass(): int { return self.receive(9); }
                        end
                        """)));
        assertTrue(failure.getMessage().contains("private abstract method"), failure.getMessage());
    }

    @Test
    void outsideCodeCannotCallPrivateAbstractOverride() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define abstract class Framework as
                          private abstract receive(int value): int;
                        end
                        define class Worker extends Framework as
                          private receive(int value): int { return value; }
                        end
                        pub fnc bypass(Worker worker): int {
                          return worker.receive(9);
                        }
                        """)));
        assertTrue(failure.getMessage().contains("private abstract method"), failure.getMessage());
    }

    @Test
    void overrideCannotPublishPrivateAbstractSlot() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define abstract class Framework as
                          private abstract receive(int value): int;
                        end
                        define class Worker extends Framework as
                          pub receive(int value): int { return value; }
                        end
                        """)));
        assertTrue(failure.getMessage().contains("cannot be made public"), failure.getMessage());
    }

    @Test
    void privateAbstractStillRequiresConcreteImplementation() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define abstract class Framework as
                          private abstract receive(int value): int;
                        end
                        define class Incomplete extends Framework as
                        end
                        """)));
        assertTrue(failure.getMessage().contains("unresolved abstract method"), failure.getMessage());
    }

    @Test
    void actorExplicitSelfIsReceiverNotMailboxArgumentOrSeparateOverload() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define actor Worker as
                  on_start(self): void { return; }
                  receive(self, ActorMail<String> mail): void {
                    stdio.println(mail.value);
                    return;
                  }
                end
                pub routine main(): void {
                  val worker = spawn Worker();
                  worker.send("hello");
                  return;
                }
                """)));
        IllegalArgumentException duplicate = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Worker as
                          receive(self, ActorMail<String> mail): void { return; }
                          receive(ActorMail<String> mail): void { return; }
                        end
                        """)));
        assertTrue(duplicate.getMessage().contains("already has arity 1"), duplicate.getMessage());
    }

    @Test
    void actorExplicitSelfReceivesOnlyThroughMailboxRuntime() throws Exception {
        String source = """
                define actor Worker as
                  receive(self, ActorMail<String> mail): void {
                    self.send(mail.value);
                    self.end();
                    return;
                  }
                end
                pub async routine main(): void {
                  val worker = spawn Worker();
                  await worker.get_ready();
                  worker.send("delivered");
                  val output = (await worker.outputs.next()).value.unwrap();
                  stdio.stdout.write(output.value);
                  await worker.get_done();
                  return;
                }
                """;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, source, "actor-explicit-self.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build());
        }
        assertEquals("delivered", output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void actorReceiveIsNeverDirectlyCallableWithExplicitSelf() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Worker as
                          receive(self, ActorMail<String> mail): void {
                            self.receive(mail);
                            return;
                          }
                        end
                        """)));
        assertTrue(failure.getMessage().contains("reserved actor lifecycle hook")
                        || failure.getMessage().contains("runtime-managed"), failure.getMessage());
    }
}
