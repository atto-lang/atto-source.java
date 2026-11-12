package dev.oreslang;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fail-closed frontend admission until physical actor arenas, sealed state,
 * checked critical loans and runtime lowering are implemented together.
 * Parser errors are NOT native actor execution or loan correctness proof.
 */
final class SealedActorStateAdmissionTest {
    private static void rejected(String source, String expected) {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> Parser.parse(source));
        assertTrue(failure.getMessage().contains(expected), failure::getMessage);
    }

    @Test void isolatedActorDoesNotSilentlyBecomeLegacyIsoactor() {
        rejected("""
                define isolated actor Ledger as
                end
                """, "arena isolation and sealed state-loan lowering");
    }

    @Test void typedStateSchemaDoesNotBecomeAnOrdinaryActorField() {
        var program = Parser.parse("""
                define actor Ledger as
                  state { let int phase = 0; }
                end
                """);
        var actor = (dev.oreslang.ast.Ast.ClassDecl) program.modules().getFirst()
                .declarations().getFirst();
        assertTrue(actor.fields().isEmpty(), "sealed metadata cannot be ordinary actor fields");
        assertEquals(1, actor.sealedStateFields().size());
        assertEquals("phase", actor.sealedStateFields().getFirst().name());
        var failure = assertThrows(IllegalArgumentException.class,
                () -> dev.oreslang.compiler.OresCompiler.analyze(program));
        assertTrue(failure.getMessage().contains("ACTOR_SEALED_STATE_LOWERING_UNSUPPORTED"));
        var direct = assertThrows(IllegalArgumentException.class,
                () -> dev.oreslang.types.TypeChecker.checkTypesWithDiagnostics(program));
        assertTrue(direct.getMessage().contains("ACTOR_SEALED_STATE_LOWERING_UNSUPPORTED"));
        var linked = assertThrows(IllegalArgumentException.class,
                () -> dev.oreslang.types.TypeChecker.checkTypesWithLinkedMetadataWithDiagnostics(
                        program, java.util.Map.of(), java.util.Map.of(), java.util.Map.of()));
        assertTrue(linked.getMessage().contains("ACTOR_SEALED_STATE_LOWERING_UNSUPPORTED"));
    }

    @Test void sealedStateRejectsDuplicateAndMixedLayouts() {
        rejected("""
                define actor Ledger as
                  state { let int x = 1; }
                  state { let int y = 2; }
                end
                """, "duplicate sealed actor state");
        rejected("""
                define actor Ledger as
                  let int legacy = 1;
                  state { let int x = 2; }
                end
                """, "cannot mix ordinary actor fields");
        rejected("""
                define actor Ledger as
                  state { let int x = 1; }
                  let int legacy = 2;
                end
                """, "cannot mix ordinary actor fields");
        rejected("""
                define actor Ledger as
                  state { pub let int x = 1; }
                end
                """, "requires private 'let'");
        rejected("""
                define actor Ledger as
                  state { let int x = 1; let int x = 2; }
                end
                """, "duplicate sealed actor state field");
    }

    @Test void sealedStateRejectsDynamicInitializersAndUnsafeValues() {
        rejected("""
                define actor Ledger as
                  state { let int x = acquire_secret(); }
                end
                """, "initializer must be a literal");
        rejected("""
                define actor Ledger as
                  state { let Map<int,int> x = 1; }
                end
                """, "requires scalar int/bool fields");
        rejected("""
                define actor Ledger as
                  state {}
                end
                """, "at least one field");
        // Literal AST kind is not a type: wrong scalar slots must fail
        // before actor allocation or the future physical state lowering.
        rejected("""
                define actor Ledger as
                  state { let int x = true; }
                end
                """, "initializer type must match int");
        rejected("""
                define actor Ledger as
                  state { let bool ready = 42; }
                end
                """, "initializer type must match bool");
        rejected("""
                define actor Ledger as
                  state { let int x = 1.5; }
                end
                """, "initializer type must match int");
        rejected("""
                define actor Ledger as
                  state { let bool ready = "true"; }
                end
                """, "initializer type must match bool");
        assertDoesNotThrow(() -> Parser.parse("""
                define actor Ledger as
                  state { let int count = 0; let bool ready = false; }
                end
                """));
    }

    @Test void userCannotSpoofSealedStateAuthorityWithActorMethod() {
        rejected("""
                define actor Ledger as
                  handle_state(): void { return; }
                end
                """, "actor handle_state is compiler-owned");
        rejected("""
                define actor Ledger as
                  pub fnc handle_state(): void { return; }
                end
                """, "actor handle_state is compiler-owned");
    }

    @Test void criticalIsPreservedAsAstEffectAndCannotRunAsOrdinaryMethod() {
        for (String source : java.util.List.of(
                """
                define actor Ledger as
                  critical increment(): void { return; }
                end
                """,
                """
                define actor Ledger as
                  state { let int count = 0; }
                  critical increment(): void { return; }
                end
                """)) {
            var parsed = Parser.parse(source);
            var actor = (dev.oreslang.ast.Ast.ClassDecl) parsed.modules()
                    .getFirst().declarations().getFirst();
            assertEquals(1, actor.methods().size());
            assertTrue(actor.methods().getFirst().critical(),
                    "critical must survive as typed AST metadata");
            assertFalse(actor.methods().getFirst().name().equals("critical"),
                    "critical must never degrade into an ordinary member name");
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> dev.oreslang.compiler.OresCompiler.analyze(parsed));
            assertTrue(failure.getMessage().contains("CRITICAL_ACTOR_STATE_LOWERING_UNSUPPORTED"));
        }
        rejected("""
                define actor Ledger as
                  critical receive(ActorMail<int> mail): void { return; }
                end
                """, "critical actor method cannot define runtime lifecycle");
        rejected("""
                define actor Ledger as
                  critical suspends increment(): void { return; }
                end
                """, "expected method name");
    }

    @Test void criticalStateLoanLambdaPreservesMutableAlias() {
        var parsed = Parser.parse("""
                define actor Ledger as
                  state { let int count = 0; }
                  critical increment(): void {
                    self.handle_state(|mut s| -> {
                      s.count = s.count + 1;
                    });
                    return;
                  }
                end
                """);
        var actor = (dev.oreslang.ast.Ast.ClassDecl) parsed.modules()
                .getFirst().declarations().getFirst();
        var method = actor.methods().getFirst();
        assertTrue(method.critical(), "critical effect cannot be erased");
        var call = assertInstanceOf(dev.oreslang.ast.Ast.CallExpr.class,
                assertInstanceOf(dev.oreslang.ast.Ast.ExprStmt.class,
                        method.body().getFirst()).expression());
        var lambda = assertInstanceOf(dev.oreslang.ast.Ast.LambdaExpr.class,
                call.arguments().getFirst());
        assertEquals(1, lambda.parameters().size());
        assertTrue(lambda.parameters().getFirst().mutable(),
                "explicit mut loan alias must be carried into checked MIR");
        assertEquals("s", lambda.parameters().getFirst().name());
        var failure = assertThrows(IllegalArgumentException.class,
                () -> dev.oreslang.compiler.OresCompiler.analyze(parsed));
        assertTrue(failure.getMessage().contains("CRITICAL_ACTOR_STATE_LOWERING_UNSUPPORTED"));
    }

    @Test void criticalAstForgeryCannotBypassCheckedOrLinkedEntryPoints() {
        var source = Parser.parse("""
                define actor Ledger as
                  safe_name(): void { return; }
                end
                """);
        var module = source.modules().getFirst();
        var actor = (dev.oreslang.ast.Ast.ClassDecl) module.declarations().getFirst();
        var original = actor.methods().getFirst();
        var critical = new dev.oreslang.ast.Ast.MethodDecl(
                original.name(), original.visibility(), original.isStatic(),
                original.isAbstract(), original.async(), original.suspends(),
                original.structural(), original.explicitReceiverType(),
                original.genericParameters(), original.parameters(), original.returnType(),
                original.annotations(), original.body(), original.effects(), true);
        var injected = new dev.oreslang.ast.Ast.ClassDecl(
                actor.name(), actor.visibility(), actor.isAbstract(), actor.actorKind(),
                actor.genericParameters(), actor.parents(), actor.interfaces(),
                actor.fields(), actor.constructor(), java.util.List.of(critical),
                actor.actorConcurrency(), actor.sealedStateFields());
        var forged = new dev.oreslang.ast.Ast.Program(
                source.namespace(), source.imports(), java.util.List.of(
                        new dev.oreslang.ast.Ast.ModuleDecl(
                                module.name(), module.annotations(), module.contracts(),
                                java.util.List.of(injected))));
        var failure = assertThrows(IllegalArgumentException.class,
                () -> dev.oreslang.compiler.OresCompiler.analyze(forged));
        assertTrue(failure.getMessage().contains("CRITICAL_ACTOR_STATE_LOWERING_UNSUPPORTED"));
        var linked = assertThrows(IllegalArgumentException.class,
                () -> dev.oreslang.types.TypeChecker.checkTypesWithLinkedMetadataWithDiagnostics(
                        source, java.util.Map.of("Ledger", injected),
                        java.util.Map.of(), java.util.Map.of()));
        assertTrue(linked.getMessage().contains("CRITICAL_ACTOR_STATE_LOWERING_UNSUPPORTED"));
    }

    @Test void directAstCannotForgeCompilerOwnedHandleStateIntrinsic() {
        // Parser's reserved-name check is not enough: AST expansion and
        // linked compiler APIs can construct methods without re-parsing.
        var program = Parser.parse("""
                define actor Ledger as
                  safe_name(): void { return; }
                end
                """);
        var module = program.modules().getFirst();
        var actor = (dev.oreslang.ast.Ast.ClassDecl) module.declarations().getFirst();
        var method = actor.methods().getFirst();
        var forged = new dev.oreslang.ast.Ast.MethodDecl(
                "handle_state", method.visibility(), method.isStatic(),
                method.isAbstract(), method.async(), method.suspends(),
                method.structural(), method.explicitReceiverType(),
                method.genericParameters(), method.parameters(), method.returnType(),
                method.annotations(), method.body(), method.effects());
        var injected = new dev.oreslang.ast.Ast.ClassDecl(
                actor.name(), actor.visibility(), actor.isAbstract(), actor.actorKind(),
                actor.genericParameters(), actor.parents(), actor.interfaces(),
                actor.fields(), actor.constructor(), java.util.List.of(forged),
                actor.actorConcurrency(), actor.sealedStateFields());
        var forgedProgram = new dev.oreslang.ast.Ast.Program(
                program.namespace(), program.imports(), java.util.List.of(
                        new dev.oreslang.ast.Ast.ModuleDecl(
                                module.name(), module.annotations(), module.contracts(),
                                java.util.List.of(injected))));

        var direct = assertThrows(IllegalArgumentException.class,
                () -> dev.oreslang.types.TypeChecker.checkTypesWithDiagnostics(forgedProgram));
        assertTrue(direct.getMessage().contains("ACTOR_STATE_INTRINSIC_FORGED"));
        var linked = assertThrows(IllegalArgumentException.class,
                () -> dev.oreslang.types.TypeChecker.checkTypesWithLinkedMetadataWithDiagnostics(
                        forgedProgram, java.util.Map.of(), java.util.Map.of(), java.util.Map.of()));
        assertTrue(linked.getMessage().contains("ACTOR_STATE_INTRINSIC_FORGED"));
        var analyze = assertThrows(IllegalArgumentException.class,
                () -> dev.oreslang.compiler.OresCompiler.analyze(forgedProgram));
        assertTrue(analyze.getMessage().contains("ACTOR_STATE_INTRINSIC_FORGED"));
        var linkedActor = assertThrows(IllegalArgumentException.class,
                () -> dev.oreslang.types.TypeChecker.checkTypesWithLinkedMetadataWithDiagnostics(
                        program, java.util.Map.of("Ledger", injected),
                        java.util.Map.of(), java.util.Map.of()));
        assertTrue(linkedActor.getMessage().contains("ACTOR_STATE_INTRINSIC_FORGED"));
    }

    @Test void ordinaryActorMethodsAndLegacySpellingRemainAsBefore() {
        assertDoesNotThrow(() -> Parser.parse("""
                define actor Ledger as
                  pub handle_stateful(): void { return; }
                end
                define isoactor Legacy as
                  pub process(): void { return; }
                end
                """));
    }
}
