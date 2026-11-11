package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.ActorReceiverBinder;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Namespace-attached state is compile-time metadata, never a global variable
 * or an independently callable state container.
 */
final class NamespaceActorStateAttachmentTest {
    private static final String NS = "namespace foo;\n";
    private static final String ACTOR = NS + "define actor Foo implements ActorXYZ;\n";
    private static final String STATE = NS
            + "define state on actor Foo as { let int x : 5; val bool ready : true; }";

    private static Map<String, Ast.Program> units(String actor, String state) {
        Map<String, Ast.Program> map = new LinkedHashMap<>();
        map.put("src/actor.atto", Parser.parse(actor));
        map.put("src/state.atto", Parser.parse(state));
        return map;
    }

    private static void rejectsBinding(String actor, String state, String message) {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> ActorReceiverBinder.bindSourceSet("demo@1.0.0", units(actor, state)));
        assertTrue(failure.getMessage().contains(message), failure.getMessage());
    }

    private static void rejectsParse(String source, String message) {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(source));
        assertTrue(failure.getMessage().contains(message), failure.getMessage());
    }

    @Test
    void semicolonActorPreservesItsNominalContractWithoutSpawningAnInstance() {
        var decl = assertInstanceOf(Ast.ClassDecl.class,
                Parser.parse(ACTOR).modules().getFirst().declarations().getFirst());
        assertEquals("Foo", decl.name());
        assertEquals(Ast.ActorKind.SHARED, decl.actorKind());
        assertEquals("ActorXYZ", decl.interfaces().getFirst().name());
        assertEquals(0, decl.fields().size());
    }

    @Test
    void standaloneAttachedStatePreservesMutabilityAndScalarInitializers() {
        var state = assertInstanceOf(Ast.ActorStateAttachmentDecl.class,
                Parser.parse(STATE).modules().getFirst().declarations().getFirst());
        assertEquals("Foo", state.actorName());
        assertEquals(2, state.fields().size());
        assertEquals(Ast.BindingKind.LET, state.fields().getFirst().bindingKind());
        assertEquals("int", state.fields().getFirst().type().name());
        assertEquals(Ast.BindingKind.VAL, state.fields().get(1).bindingKind());
        assertEquals("bool", state.fields().get(1).type().name());
    }

    @Test
    void manifestScopedTwoFileActorAndStateAreStaticallyValid() {
        assertEquals(0,
                ActorReceiverBinder.bindSourceSet("demo@1.0.0", units(ACTOR, STATE)).size());
    }

    @Test
    void topLevelPubMtdWithoutDefinePreservesNominalMethodBinding() {
        var methods = ActorReceiverBinder.bindSourceSet("demo@1.0.0",
                units(ACTOR, NS + "pub mtd (Foo f) bar(): void { return; }"));
        assertEquals(1, methods.size());
        assertEquals("bar", methods.keySet().iterator().next().name());
    }

    @Test
    void stateCannotBeTreatedAsOrdinaryExecutableDeclaration() {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(
                        NS + "define actor Foo;\n"
                                + "define state on actor Foo as { let int x : 5; }")));
        assertTrue(failure.getMessage().contains("ACTOR_STATE_ATTACHMENT_LOWERING_UNSUPPORTED"),
                failure.getMessage());
    }

    @Test
    void noOtherNamespaceCanClaimAnActorState() {
        rejectsBinding(ACTOR,
                "namespace foreign; define state on actor Foo as { let int x : 5; }",
                "has no local nominal actor");
    }

    @Test
    void ordinaryClassCannotAcquireActorState() {
        rejectsBinding(NS + "define class Foo as end",
                NS + "define state on actor Foo as { let int x : 5; }",
                "has no local nominal actor");
    }

    @Test
    void duplicateStateAttachmentsAcrossFilesAreRejected() {
        Map<String, Ast.Program> source = units(ACTOR, STATE);
        source.put("src/state2.atto", Parser.parse(STATE));
        var failure = assertThrows(IllegalArgumentException.class,
                () -> ActorReceiverBinder.bindSourceSet("demo@1.0.0", source));
        assertTrue(failure.getMessage().contains("duplicate attached actor state schema"),
                failure.getMessage());
    }

    @Test
    void legacyFieldsCannotMixWithAttachedActorState() {
        rejectsBinding(NS + "define actor Foo as let int x = 4; end",
                NS + "define state on actor Foo as { let int x : 5; }",
                "cannot mix with legacy actor fields");
    }

    @Test
    void inheritedActorStateNeedsExplicitLayoutWitness() {
        rejectsBinding(NS + "define actor Parent as end\n"
                        + "define actor Foo extends Parent as end",
                NS + "define state on actor Foo as { let int x : 5; }",
                "needs checked layout coherence");
    }

    @Test
    void stateCannotBePublicOrExternallyExportedAsAContainer() {
        rejectsParse(NS + "define pub state on actor Foo as { let int x : 5; }",
                "do not accept declaration modifiers");
    }

    @Test
    void noDuplicateStateFieldsCanHideBehindOrderChanges() {
        rejectsParse(NS + "define state on actor Foo as { let int x : 5; let int x : 7; }",
                "duplicate attached actor state field");
    }

    @Test
    void wrongScalarDefaultCannotBecomeAnOpaqueRuntimeValue() {
        rejectsParse(NS + "define state on actor Foo as { let int x : true; }",
                "type-matched literal defaults");
    }

    @Test
    void noArbitraryCallCanInitializeActorStateAtNamespaceLoad() {
        rejectsParse(NS + "define state on actor Foo as { let int x : load_secret(); }",
                "type-matched literal defaults");
    }

    @Test
    void structuralStateRequiresSeparateOwnershipLowering() {
        rejectsParse(NS + "define state on actor Foo as { let Map<int, int> x : 5; }",
                "supports only int/bool scalar fields");
    }

    @Test
    void actorStateCannotBeDeclaredInsideAnUnrelatedModule() {
        rejectsParse(NS + "define module nope as "
                        + "define state on actor Foo as { let int x : 5; } end",
                "namespace-root declaration");
    }
}
