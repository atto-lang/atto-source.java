package dev.oreslang;

import dev.oreslang.ast.Ast;
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
    void inlineSelfIsOneCanonicalReceiverForActorsAndOrdinaryClasses() {
        Ast.Program program = Parser.parse("""
                define class Ordinary as
                  pub zero(self): void { return; }
                  pub read(self, int value): int { return value; }
                  pub implicit(int value): int { return value; }
                  pub change(mut self, int value): void { return; }
                end
                define actor Worker as
                  on_start(self): void { return; }
                  receive(self, ActorMail<String> mail): void { return; }
                end
                """);
        Ast.ClassDecl ordinary = (Ast.ClassDecl) program.modules().getFirst().declarations().getFirst();
        Ast.ClassDecl actor = (Ast.ClassDecl) program.modules().getFirst().declarations().get(1);
        assertEquals(0, ordinary.methods().get(0).arity());
        assertEquals(1, ordinary.methods().get(1).arity());
        assertNull(ordinary.methods().get(1).explicitReceiverType());
        assertEquals(ordinary.methods().get(1).arity(), ordinary.methods().get(2).arity());
        assertEquals(1, ordinary.methods().get(3).arity());
        assertTrue(ordinary.methods().get(3).explicitReceiverType().isBorrow());
        assertTrue(ordinary.methods().get(3).explicitReceiverType().mutableBorrow());
        assertEquals(0, actor.methods().get(0).arity());
        assertEquals(1, actor.methods().get(1).arity());
    }

    @Test
    void inlineSelfAndImplicitSelfDuplicateTheSameClassSlotInEitherOrder() {
        for (String methods : new String[] {
                "pub read(self, int x): int { return x; } pub read(int x): int { return x; }",
                "pub read(int x): int { return x; } pub read(self, int x): int { return x; }"
        }) {
            IllegalArgumentException duplicate = assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse("define class C as "
                            + methods + " end")));
            assertTrue(duplicate.getMessage().contains("already has arity 1"),
                    duplicate.getMessage());
        }
    }

    @Test
    void inlineReceiverRejectsIllegalStaticOrTrailingForms() {
        for (String parameters : new String[] {
                "self,", "mut self,", "int x, self", "int x, mut self"
        }) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(
                    "define class C as pub wrong(" + parameters
                            + "): void { return; } end"), parameters);
        }
        for (String parameters : new String[] {"self", "self, int x", "mut self, int x"}) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(
                    "define class C as pub static fnc wrong(" + parameters
                            + "): void { return; } end"), parameters);
        }
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
    void intermediateClassCannotWidenPrivateAbstractInvocationAuthority() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define abstract class Framework as
                          private abstract deliver(int value): int;
                        end
                        define abstract class Intermediate extends Framework as
                          private deliver(int value): int { return value; }
                        end
                        define class PublicWorker extends Intermediate as
                          pub deliver(int value): int { return value; }
                        end
                        """)));
        assertTrue(failure.getMessage().contains("cannot be made public"), failure.getMessage());
    }

    @Test
    void implicitIteratorCannotCallInheritedPrivateAbstractOverride() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define abstract class Framework as
                          private abstract [Symbol.iterator](): Array<int>;
                        end
                        define class Worker extends Framework as
                          private [Symbol.iterator](): Array<int> {
                            return arr[1, 2];
                          }
                          pub bypass(): void {
                            for const item of self do
                              stdio.stdout.write(item);
                            done
                            return;
                          }
                        end
                        """)));
        assertTrue(failure.getMessage().contains("private abstract method"), failure.getMessage());
    }

    @Test
    void actorReceiveMemberValueIsNotExportable() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Worker as
                          receive(ActorMail<String> mail): void {
                            val handler = self.receive;
                            return;
                          }
                        end
                        """)));
        assertTrue(failure.getMessage().contains("runtime-managed")
                        || failure.getMessage().contains("direct-call-only"),
                failure.getMessage());
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
    void privateAbstractOverrideMustPreserveTheOriginalSignature() {
        IllegalArgumentException changedArgument = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define abstract class Framework as
                          private abstract receive(int value): int;
                        end
                        define class Worker extends Framework as
                          private receive(bool value): int { return 1; }
                        end
                        """)));
        assertTrue(changedArgument.getMessage().contains("incompatible private abstract"),
                changedArgument.getMessage());

        IllegalArgumentException changedReturn = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define abstract class Framework as
                          private abstract receive(int value): int;
                        end
                        define class Worker extends Framework as
                          private receive(int value): bool { return true; }
                        end
                        """)));
        assertTrue(changedReturn.getMessage().contains("incompatible private abstract"),
                changedReturn.getMessage());
    }

    @Test
    void concreteIntermediateCannotLaunderAnIncompatibleGrandchildOverride() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define abstract class Framework as
                          private abstract receive(int value): int;
                        end
                        define class Middle extends Framework as
                          private receive(int value): int { return value; }
                        end
                        define class Worker extends Middle as
                          private receive(bool value): int { return 1; }
                        end
                        """)));
        assertTrue(failure.getMessage().contains("original superclass signature"),
                failure.getMessage());
    }

    @Test
    void uncheckedGenericPrivateAbstractOverridesFailClosed() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define abstract class Framework<T> as
                          private abstract receive(T value): void;
                        end
                        define class Worker extends Framework<int> as
                          private receive(int value): void { return; }
                        end
                        """)));
        assertTrue(failure.getMessage().contains("checked type substitution"),
                failure.getMessage());
    }

    @Test
    void onStartIsAnUncallableActorRuntimeHook() {
        IllegalArgumentException direct = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Worker as
                          on_start(): void { return; }
                          receive(ActorMail<String> mail): void {
                            self.on_start();
                            return;
                          }
                        end
                        """)));
        assertTrue(direct.getMessage().contains("reserved actor lifecycle hook")
                        || direct.getMessage().contains("runtime-managed"), direct.getMessage());

        IllegalArgumentException reference = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Worker as
                          on_start(): void { return; }
                          receive(ActorMail<String> mail): void {
                            val callback = self.on_start;
                            return;
                          }
                        end
                        """)));
        assertTrue(reference.getMessage().contains("runtime-managed")
                        || reference.getMessage().contains("direct-call-only"), reference.getMessage());
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

    @Test
    void intermediateAbstractRedeclarationCannotStealOriginalCallAuthority() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define abstract class Framework as
                          private abstract receive(int value): int;
                          pub deliver(int value): int { return self.receive(value); }
                        end
                        define abstract class Intermediate extends Framework as
                          private abstract receive(int value): int;
                          pub illegalCall(int value): int { return self.receive(value); }
                        end
                        define class Worker extends Intermediate as
                          private receive(int value): int { return value; }
                        end
                        """)));
        assertTrue(failure.getMessage().contains("private abstract method"), failure.getMessage());
        assertTrue(failure.getMessage().contains("Framework.receive"), failure.getMessage());
    }

    @Test
    void originalSuperclassCanDispatchAcrossAbstractRedeclaration() throws Exception {
        String source = """
                define abstract class Framework as
                  private abstract receive(int value): int;
                  pub deliver(int value): int { return self.receive(value); }
                end
                define abstract class Intermediate extends Framework as
                  private abstract receive(int value): int;
                end
                define class Worker extends Intermediate as
                  private receive(int value): int { return value + 7; }
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
            context.eval(Source.newBuilder(OresLanguage.ID, source, "private-abstract-multilevel.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build());
        }
        assertEquals("12", output.toString(StandardCharsets.UTF_8));
    }

}
