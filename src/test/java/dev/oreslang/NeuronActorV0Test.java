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
                "define neuron untrusted actor X as state { let float weight; } end",
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
        assertThrows(IllegalArgumentException.class, () -> new Ast.ClassDecl(
                neuron.name(), neuron.visibility(), true, neuron.actorKind(),
                neuron.genericParameters(), neuron.parents(), neuron.interfaces(),
                neuron.fields(), neuron.constructor(), neuron.methods(),
                neuron.actorConcurrency(), neuron.stateFields()));
        assertThrows(IllegalArgumentException.class, () -> new Ast.ClassDecl(
                neuron.name(), neuron.visibility(), false, Ast.ActorKind.UNTRUSTED,
                neuron.genericParameters(), neuron.parents(), neuron.interfaces(),
                neuron.fields(), neuron.constructor(), neuron.methods(),
                neuron.actorConcurrency(), neuron.stateFields()));

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

    @Test void protectedReadWriteScopesRemainSealedAstAndCannotExecuteYet() {
        String protectedSource = """
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
        Ast.ClassDecl actor = firstActor(protectedSource);
        assertTrue(actor.fields().isEmpty());
        assertEquals(2, actor.stateFields().size());
        Ast.MethodDecl start = actor.methods().stream()
                .filter(method -> method.name().equals("on_start")).findFirst().orElseThrow();
        Ast.ExprStmt initialization = (Ast.ExprStmt) start.body().getFirst();
        Ast.CallExpr initCall = (Ast.CallExpr) initialization.expression();
        Ast.RuntimeCallExpr write = (Ast.RuntimeCallExpr) initCall.arguments().getFirst();
        assertEquals("$neuron.critical.write", write.operation());
        Ast.LambdaExpr writeLambda = (Ast.LambdaExpr) write.arguments().getFirst();
        assertEquals("s", writeLambda.parameters().getFirst().name());
        assertEquals(2, writeLambda.blockBody().size());

        Ast.MethodDecl forward = actor.methods().stream()
                .filter(method -> method.name().equals("forward")).findFirst().orElseThrow();
        Ast.ReturnStmt returnValue = (Ast.ReturnStmt) forward.body().getFirst();
        Ast.CallExpr readCall = (Ast.CallExpr) returnValue.value();
        Ast.RuntimeCallExpr read = (Ast.RuntimeCallExpr) readCall.arguments().getFirst();
        assertEquals("$neuron.critical.read", read.operation());
        Ast.LambdaExpr readLambda = (Ast.LambdaExpr) read.arguments().getFirst();
        assertEquals("s", readLambda.parameters().getFirst().name());
        assertNotNull(readLambda.expressionBody());

        IllegalArgumentException blocked = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(protectedSource)));
        assertTrue(blocked.getMessage().contains("NEURON_STATE_MIR_UNSUPPORTED"),
                blocked::getMessage);
    }

    @Test void criticalLoanSyntaxRejectsAmbiguousOrForgedModes() {
        String schema = "define neuron actor X as state { let float weight; } ";
        String[] invalid = {
                "pub forward(float x): float { return self.handle_state(critical-take |s| -> x); }",
                "pub forward(float x): float { return self.handle_state(critical-read |s| -> x, 1); }",
                "pub forward(float x): float { return self.handle_state(critical-read || -> x); }",
                "pub forward(float x): float { return self.handle_state(critical-write |s: int| -> x); }",
                "pub forward(float x): float { return self.handle_state(critical-read |mut s| -> x); }"
        };
        for (String member : invalid) {
            String source = schema + member + " end";
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(source), source);
        }
    }

    private static Ast.Program replaceNeuronMethod(Ast.ClassDecl base, Ast.MethodDecl alternate) {
        java.util.List<Ast.MethodDecl> revised = new java.util.ArrayList<>(base.methods());
        int replaced = -1;
        for (int i = 0; i < revised.size(); i++) {
            if (revised.get(i).name().equals(alternate.name())) {
                replaced = i;
                revised.set(i, alternate);
                break;
            }
        }
        assertTrue(replaced >= 0, "must replace an existing neuron hook");
        Ast.ClassDecl mutated = new Ast.ClassDecl(
                base.name(), base.visibility(), base.isAbstract(), base.actorKind(),
                base.genericParameters(), base.parents(), base.interfaces(),
                base.fields(), base.constructor(), revised, base.actorConcurrency(), base.stateFields());
        return new Ast.Program(java.util.List.of(
                new Ast.ModuleDecl(Parser.ROOT_MODULE, java.util.List.of(mutated))));
    }

    private static Ast.MethodDecl rewriteHook(
            Ast.MethodDecl source, Ast.TypeRef receiver, java.util.List<Ast.Param> parameters) {
        return new Ast.MethodDecl(
                source.name(), source.visibility(), source.isStatic(), source.isAbstract(),
                source.async(), source.suspends(), source.structural(), receiver,
                source.genericParameters(), parameters, source.returnType(),
                source.annotations(), source.body(), source.effects());
    }

    @Test void linkedNeuronSignaturesCannotBypassLocalMirAdmission() {
        Ast.ClassDecl protectedActor = firstActor(BASE);
        Ast.Program ordinaryClient = Parser.parse("""
                pub routine main(): void { return; }
                """);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypesWithImportedClassesWithDiagnostics(
                        ordinaryClient,
                        java.util.Map.of("LinearNeuron", protectedActor),
                        java.util.Map.of()));
        assertTrue(failure.getMessage().contains("NEURON_STATE_LINK_UNSUPPORTED"),
                failure::getMessage);
    }

    @Test void forgedHookReceiversAndMutableOrStructuralParametersAreRejected() {
        Ast.ClassDecl base = firstActor(BASE);
        Ast.MethodDecl forward = base.methods().stream()
                .filter(m -> m.name().equals("forward")).findFirst().orElseThrow();
        Ast.MethodDecl receive = base.methods().stream()
                .filter(m -> m.name().equals("receive")).findFirst().orElseThrow();

        var explicitForward = rewriteHook(forward,
                Ast.TypeRef.simple("LinearNeuron"), forward.parameters());
        var mutableInput = rewriteHook(forward, null,
                java.util.List.of(new Ast.Param(Ast.TypeRef.simple("float"),
                        "input", false, true)));
        var structuralInput = rewriteHook(forward, null,
                java.util.List.of(new Ast.Param(Ast.TypeRef.simple("float"),
                        "input", true, false)));
        var explicitReceive = rewriteHook(receive,
                Ast.TypeRef.simple("LinearNeuron"), receive.parameters());
        var mutableMail = rewriteHook(receive, null,
                java.util.List.of(new Ast.Param(
                        new Ast.TypeRef("ActorMail",
                                java.util.List.of(Ast.TypeRef.simple("float")), false),
                        "mail", false, true)));

        for (Ast.MethodDecl bad : java.util.List.of(
                explicitForward, mutableInput, structuralInput, explicitReceive, mutableMail)) {
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> TypeChecker.check(replaceNeuronMethod(base, bad)));
            assertTrue(failure.getMessage().contains(bad.name()), failure::getMessage);
        }
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
