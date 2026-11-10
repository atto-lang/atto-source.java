package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class NeuronActorV0Test {
    private static final String BASE = """
            define neuron actor LinearNeuron as
              let float weight;
              on_start(): void { self.weight = 2.0; return; }
              pub forward(float input): float { return self.weight * input; }
              receive(ActorMail<float> mail): void {
                self.send(self.forward(mail.value));
                self.end();
                return;
              }
            end
            """;

    private static Ast.ClassDecl neuron(String text) {
        Ast.Program parsed = Parser.parse(text);
        return (Ast.ClassDecl) parsed.modules().getFirst().declarations().getFirst();
    }

    @Test void neutronRoleIsPreservedWithoutReplacingActorTransport() {
        Ast.ClassDecl klass = neuron(BASE);
        assertEquals(Ast.ActorKind.SHARED, klass.actorKind());
        assertEquals(Ast.ActorRole.NEURON, klass.actorConcurrency().role());
        assertEquals(Ast.ActorTransport.GENERIC, klass.actorConcurrency().transport());
        assertEquals(1, klass.actorConcurrency().messageConcurrency());
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(BASE)));
        assertEquals(Ast.ActorRole.GENERAL,
                neuron(BASE.replace("define neuron actor", "define actor")).actorConcurrency().role());
    }

    @Test void scalarHooksMustBePresentAndExact() {
        String[][] rejected = {
                {"pub forward(float input): float { return self.weight * input; }",
                    "", "forward"},
                {"pub forward(float input): float", "forward(float input): float", "forward"},
                {"pub forward(float input): float", "pub async forward(float input): float", "forward"},
                {"pub forward(float input): float", "pub forward(int input): float", "forward"},
                {"pub forward(float input): float", "pub forward(float input): int", "forward"},
                {"pub forward(float input): float", "pub forward(float input, float extra): float", "forward"},
                {"receive(ActorMail<float> mail): void", "receive(ActorMail<int> mail): void", "receive"},
                {"receive(ActorMail<float> mail): void", "receive(ActorMail<float> mail, float x): void", "receive"}
        };
        for (String[] c : rejected) {
            String source = c[1].isEmpty()
                    ? BASE.replace(c[0] + " { return self.weight * input; }", "")
                    : BASE.replace(c[0], c[1]);
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(source)), source);
            assertTrue(error.getMessage().contains(c[2]), error::getMessage);
        }
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(BASE.replace(
                        "receive(ActorMail<float> mail): void {\n                self.send(self.forward(mail.value));\n                self.end();\n                return;\n              }", ""))));
    }

    @Test void malformedRoleAndConflictingTransportFailClosed() {
        for (String declaration : new String[] {
                "define neuron class X as end",
                "define neuron neuron actor X as end",
                "define http neuron actor X as end",
                "define neuron tcp actor X as end",
                "define neuron isoactor X as end"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse(declaration), declaration);
        }
        assertDoesNotThrow(() -> Parser.parse("""
                pub routine main(): void {
                  val int neuron = 1;
                  return;
                }
                """));
        assertDoesNotThrow(() -> Parser.parse("""
                define module neurons as
                  """ + BASE + """
                end
                """));
    }

    @Test void guestCompilesAndExecutesScalarNeuronWithRealActorMailbox() throws Exception {
        // Distinct from parser and host-only math. This must run actual guest source.
        String program = BASE + """
                pub async routine main(): void {
                  val neuron = spawn LinearNeuron();
                  await neuron.get_ready();
                  neuron.send(3.0);
                  val output = (await neuron.outputs.next()).value.unwrap();
                  if output.value != 6.0; do
                    rt fail("bad neuron output");
                  fi
                  stdio.stdout.write("neuron-ok");
                  await neuron.get_done();
                  return;
                }
                """;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "neuron-v0.atto")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        assertEquals("neuron-ok", output.toString(StandardCharsets.UTF_8));
    }
}
