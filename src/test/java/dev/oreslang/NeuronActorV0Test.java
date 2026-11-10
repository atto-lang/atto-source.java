package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.ast.AnnotationExpander;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Protected state syntax must never silently become plain mutable actor fields. */
final class NeuronActorV0Test {
    private static final String BASE = """
            define neuron actor LinearNeuron as
              state {
                let float weight;
                let float bias;
              }

              pub forward(float input): float { return input; }
              receive(ActorMail<float> mail): void {
                self.send(self.forward(mail.value));
                self.end();
                return;
              }
            end
            """;

    private static Ast.ClassDecl firstActor(String text) {
        Ast.Program program = Parser.parse(text);
        return (Ast.ClassDecl) program.modules().getFirst().declarations().getFirst();
    }

    @Test void neuronStateRemainsSealedMetadataThroughAstExpansion() {
        Ast.Program parsed = Parser.parse(BASE);
        Ast.ClassDecl klass = firstActor(BASE);
        assertEquals(Ast.ActorKind.SHARED, klass.actorKind());
        assertEquals(Ast.ActorRole.NEURON, klass.actorConcurrency().role());
        assertEquals(Ast.ActorTransport.GENERIC, klass.actorConcurrency().transport());
        assertTrue(klass.fields().isEmpty(), "neuron state must NOT flatten into actor fields");
        assertEquals(2, klass.stateFields().size());
        assertEquals("weight", klass.stateFields().get(0).name());
        assertEquals("bias", klass.stateFields().get(1).name());
        assertEquals(Ast.TypeRef.simple("float"), klass.stateFields().get(0).type());
        assertEquals(Ast.BindingKind.LET, klass.stateFields().get(1).bindingKind());

        Ast.Program expanded = AnnotationExpander.expand(parsed);
        Ast.ClassDecl after = (Ast.ClassDecl) expanded.modules().getFirst().declarations().getFirst();
        assertEquals(klass.stateFields(), after.stateFields());
        assertTrue(after.fields().isEmpty());
    }

    @Test void sourceFailsClosedUntilCriticalStateLoansAreImplemented() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(BASE)));
        assertTrue(failure.getMessage().contains("NEURON_STATE_MIR_UNSUPPORTED"),
                failure::getMessage);
    }

    @Test void malformedSchemasRejectAtSyntaxStage() {
        String[] rejected = {
                "define neuron actor X as end",
                "define neuron actor X as state {} end",
                "define neuron actor X as let float weight; state { let float bias; } end",
                "define neuron actor X as state { let float weight; } state { let float bias; } end",
                "define neuron actor X as state { let float weight; let float weight; } end",
                "define neuron actor X as state { val float weight; } end",
                "define neuron actor X as state { let weight = 1.0; } end",
                "define neuron actor X as state { pub let float weight; } end",
                "define neuron actor X as state { state { let float weight; } } end",
                "define neuron class X as end",
                "define neuron isoactor X as end",
                "define http neuron actor X as end",
                "define neuron tcp actor X as end",
                "define actor X as state { let float weight; } end",
                "define neuron actor X as pub forward(float x): float { return x; } state { let float weight; } end"
        };
        for (String bad : rejected) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(bad), bad);
        }
    }

    @Test void badScalarHooksRejectBeforeMirAdmission() {
        String[][] negatives = {
                {"pub forward(float input): float", "forward(float input): float", "forward"},
                {"pub forward(float input): float", "pub async forward(float input): float", "forward"},
                {"pub forward(float input): float", "pub forward(int input): float", "forward"},
                {"pub forward(float input): float", "pub forward(float input): int", "forward"},
                {"receive(ActorMail<float> mail): void", "receive(ActorMail<int> mail): void", "receive"},
                {"receive(ActorMail<float> mail): void", "pub receive(ActorMail<float> mail): void", "receive"}
        };
        for (String[] neg : negatives) {
            String bad = BASE.replace(neg[0], neg[1]);
            assertNotEquals(BASE, bad);
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(bad)), bad);
            assertTrue(failure.getMessage().contains(neg[2]), failure::getMessage);
        }
    }

    @Test void rawAstProducersCannotBypassProtectedStateInvariants() {
        Ast.ClassDecl neuron = firstActor(BASE);
        assertThrows(IllegalArgumentException.class, () -> new Ast.ClassDecl(
                neuron.name(), neuron.visibility(), neuron.isAbstract(), neuron.actorKind(),
                neuron.genericParameters(), neuron.parents(), neuron.interfaces(),
                neuron.fields(), neuron.constructor(), neuron.methods(),
                neuron.actorConcurrency(), java.util.List.of()));
        Ast.FieldDecl exposed = new Ast.FieldDecl("weight", Ast.Visibility.PUBLIC,
                Ast.BindingKind.LET, Ast.TypeRef.simple("float"), (Ast.Expr) null);
        assertThrows(IllegalArgumentException.class, () -> new Ast.ClassDecl(
                neuron.name(), neuron.visibility(), neuron.isAbstract(), neuron.actorKind(),
                neuron.genericParameters(), neuron.parents(), neuron.interfaces(),
                neuron.fields(), neuron.constructor(), neuron.methods(),
                neuron.actorConcurrency(), java.util.List.of(exposed)));
        assertThrows(IllegalArgumentException.class, () -> new Ast.ClassDecl(
                neuron.name(), neuron.visibility(), neuron.isAbstract(), neuron.actorKind(),
                neuron.genericParameters(), neuron.parents(), neuron.interfaces(),
                neuron.fields(), neuron.constructor(), neuron.methods(),
                neuron.actorConcurrency(),
                java.util.List.of(neuron.stateFields().getFirst(), neuron.stateFields().getFirst())));
    }

    @Test void existingOrdinaryActorAndContextualIdentifierStillParse() {
        Ast.ClassDecl ordinary = firstActor("""
                define actor Ordinary as
                  let float weight;
                end
                """);
        assertEquals(Ast.ActorRole.GENERAL, ordinary.actorConcurrency().role());
        assertTrue(ordinary.stateFields().isEmpty());

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
}
