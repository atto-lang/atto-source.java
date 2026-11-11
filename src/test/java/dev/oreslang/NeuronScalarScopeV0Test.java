package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.NeuronScalarScopeV0;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * These are preflight parser/AST tests, NOT guest execution or AOT receipts.
 * The normal TypeChecker must continue to deny all neuron guest lowering.
 */
final class NeuronScalarScopeV0Test {
    private static final String SOURCE = """
            define neuron actor LinearNeuron as
              state {
                let float weight;
                let float bias;
              }
              on_start(): void {
                self.handle_state(critical-write |s| -> {
                  s.weight = 2.0;
                  s.bias = 1.0;
                });
                return;
              }
              pub forward(float input): float {
                return self.handle_state(
                  critical-read |s| -> s.weight * input + s.bias
                );
              }
              receive(ActorMail<float> mail): void {
                self.send(self.forward(mail.value));
                self.end();
                return;
              }
            end
            """;

    private static Ast.ClassDecl actor(String source) {
        Ast.Program parsed = Parser.parse(source);
        return (Ast.ClassDecl) parsed.modules().getFirst().declarations().getFirst();
    }

    private static void rejected(String source) {
        Ast.Program parsed = Parser.parse(source);
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> TypeChecker.check(parsed));
        assertTrue(failure.getMessage().contains("NEURON_STATE_SCOPE_UNSUPPORTED"),
                failure::getMessage);
        IllegalArgumentException directly = assertThrows(
                IllegalArgumentException.class,
                () -> NeuronScalarScopeV0.preflight(actor(source)));
        assertTrue(directly.getMessage().contains("NEURON_STATE_SCOPE_UNSUPPORTED"),
                directly::getMessage);
    }

    @Test void closedScalarScopeAdmitsExactReadWritePreflightButStillDeniesGuest() {
        var plan = NeuronScalarScopeV0.preflight(actor(SOURCE));
        assertTrue(plan.hasCriticalScopes());
        assertEquals("LinearNeuron", plan.actorName());
        assertEquals(NeuronScalarScopeV0.Mode.WRITE, plan.initialization().mode());
        assertEquals(List.of("weight", "bias"), plan.initialization().writes());
        assertEquals(List.of(), plan.initialization().reads());
        assertEquals(NeuronScalarScopeV0.Mode.READ, plan.inference().mode());
        assertEquals(List.of("weight", "bias"), plan.inference().reads());
        assertEquals(List.of(), plan.inference().writes());

        var denied = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(SOURCE)));
        assertTrue(denied.getMessage().contains("NEURON_STATE_MIR_UNSUPPORTED"),
                denied::getMessage);
    }

    @Test void rejectsUninitializedSelfReadBeforeFirstAssignment() {
        rejected(SOURCE.replace("s.weight = 2.0;", "s.weight = s.weight + 1.0;"));
        rejected(SOURCE.replace("s.weight = 2.0;", "s.weight = s.bias + 1.0;"));
    }

    @Test void rejectsMissingAndDuplicateScalarInitialization() {
        rejected(SOURCE.replace("s.bias = 1.0;", ""));
        rejected(SOURCE.replace("s.bias = 1.0;", "s.weight = 1.0;"));
    }

    @Test void rejectsEffectfulInitializersAndUnverifiedCalls() {
        rejected(SOURCE.replace("s.weight = 2.0;", "s.weight = random();"));
        rejected(SOURCE.replace("s.bias = 1.0;", "s.bias = self.generate_bias();"));
        rejected(SOURCE.replace("s.weight * input + s.bias", "foo(s.weight)"));
    }

    @Test void rejectsStateAliasEscapeAndSuspendingRead() {
        rejected(SOURCE.replace("s.weight * input + s.bias", "s"));
        rejected(SOURCE.replace("s.weight * input + s.bias", "await foo()"));
        rejected(SOURCE.replace("s.weight * input + s.bias", "self.send(s.weight)"));
    }

    @Test void rejectsUnsupportedArithmeticAndUnknownFieldProjections() {
        rejected(SOURCE.replace("s.weight * input + s.bias", "s.weight / input"));
        rejected(SOURCE.replace("s.weight * input + s.bias", "s.unknown * input"));
    }

    @Test void rejectsStateViewParameterShadowing() {
        rejected(SOURCE.replace("critical-read |s| -> s.weight * input + s.bias",
                "critical-read |input| -> input.weight * input + input.bias"));
    }

    @Test void rejectsMissingExplicitReturnFromOnStart() {
        // AttoLang method bodies do not implicitly return the last
        // expression. A protected state scope must exit through a known,
        // explicit return so a future MIR emitter can attach cleanup.
        rejected(SOURCE.replaceFirst("return;", ""));
    }

    @Test void rejectsIncompleteCriticalScopePairs() {
        int writeStart = SOURCE.indexOf("self.handle_state(critical-write");
        int writeEnd = SOURCE.indexOf("});", writeStart) + 3;
        assertTrue(writeStart > 0 && writeEnd > writeStart, "write fixture must exist");
        String noWrite = SOURCE.substring(0, writeStart)
                + "log_start();" + SOURCE.substring(writeEnd);
        rejected(noWrite);

        int readStart = SOURCE.indexOf("return self.handle_state(");
        int readEnd = SOURCE.indexOf(");", readStart) + 2;
        assertTrue(readStart > 0 && readEnd > readStart, "read fixture must exist");
        String noRead = SOURCE.substring(0, readStart)
                + "return input;" + SOURCE.substring(readEnd);
        rejected(noRead);
    }

    @Test void rejectsEscapingStateViaProceduralWriteBodies() {
        rejected(SOURCE.replace("s.bias = 1.0;", "return s;"));
        rejected(SOURCE.replace("s.bias = 1.0;", "await maybe_suspend();"));
        rejected(SOURCE.replace("s.bias = 1.0;", "self.send(s.weight);"));
    }

    @Test void rejectsCriticalStateScopesInjectedIntoReceiveOrOtherHelpers() {
        String injected = SOURCE.replace(
                "self.send(self.forward(mail.value));",
                "self.handle_state(critical-read |s| -> s.weight);"
                        + " self.send(self.forward(mail.value));");
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(injected),
                "critical receive scope must fail at parser admission");
        String nested = SOURCE.replace("self.send(self.forward(mail.value));",
                "self.send(self.handle_state(critical-read |s| -> s.weight));");
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(nested),
                "nested critical scope inside receive is also forbidden");
    }

    @Test void rejectsOversizedScalarExpressionTreeWithoutStackBlowup() {
        String expression = "s.weight";
        for (int i = 0; i < 70; i++) {
            expression = "(" + expression + " + 1.0)";
        }
        rejected(SOURCE.replace("s.weight * input + s.bias", expression));
    }

    @Test void ordinaryNeuronDeclarationStillRemainsFailClosedWithoutScopePair() {
        String bare = """
                define neuron actor LinearNeuron as
                  state { let float weight; }
                  pub forward(float input): float { return input; }
                  receive(ActorMail<float> mail): void { self.end(); return; }
                end
                """;
        var plan = NeuronScalarScopeV0.preflight(actor(bare));
        assertFalse(plan.hasCriticalScopes());
        var denied = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(bare)));
        assertTrue(denied.getMessage().contains("NEURON_STATE_MIR_UNSUPPORTED"),
                denied::getMessage);
    }
}
