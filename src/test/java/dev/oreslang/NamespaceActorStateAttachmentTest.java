package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.ActorReceiverBinder;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
    private static final String ACTOR = NS + "declare pub actor Foo implements ActorXYZ;\n";
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
    void declaredActorPreservesItsNominalContractWithoutSpawningAnInstance() {
        var decl = assertInstanceOf(Ast.DeclaredActorDecl.class,
                Parser.parse(ACTOR).modules().getFirst().declarations().getFirst());
        assertEquals("Foo", decl.name());
        assertEquals(Ast.ActorKind.SHARED, decl.actorKind());
        assertEquals("ActorXYZ", decl.contracts().getFirst().name());
        assertEquals(Ast.Visibility.PUBLIC, decl.visibility());
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
    void physicalThreeFileCorpusParsesAndAttachesToOneNominalActor() throws IOException {
        Path root = Path.of("contracts", "namespace-actor-state-v1");
        Map<String, Ast.Program> source = new LinkedHashMap<>();
        for (String name : new String[] {"actor.atto", "state.atto", "methods.atto"}) {
            source.put("src/foo/" + name, Parser.parse(Files.readString(root.resolve(name))));
        }
        var methodBindings = ActorReceiverBinder.bindSourceSet("demo@1.0.0", source);
        assertEquals(1, methodBindings.size());
        assertEquals("Foo", methodBindings.keySet().iterator().next().actor());
        assertEquals("bar", methodBindings.keySet().iterator().next().name());
    }

    @Test
    void stateCannotBeTreatedAsOrdinaryExecutableDeclaration() {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(
                        NS + "declare actor Foo;\n"
                                + "define state on actor Foo as { let int x : 5; }")));
        assertTrue(failure.getMessage().contains("DECLARED_ACTOR_LINKING_UNSUPPORTED"),
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
    void bodylessDefineActorRequiresDeclareInstead() {
        rejectsParse(NS + "define actor Foo implements ActorXYZ;",
                "bodyless actor types use 'declare actor Foo'");
    }

    @Test
    void plainDeclareActorWithoutVisibilityIsAValidPrivateNominalDeclaration() {
        var decl = assertInstanceOf(Ast.DeclaredActorDecl.class,
                Parser.parse(NS + "declare actor Foo implements ActorXYZ;")
                        .modules().getFirst().declarations().getFirst());
        assertEquals(Ast.Visibility.PRIVATE, decl.visibility());
        assertEquals(1, decl.contracts().size());
    }

    @Test
    void publicReceiverCannotExposePrivateDeclaredActor() {
        rejectsBinding(NS + "declare actor Foo implements ActorXYZ;",
                NS + "pub mtd (Foo f) bar(): void { return; }",
                "cannot expose private actor");
    }

    @Test
    void duplicateDeclaredActorIsNeverImplicitlyMerged() {
        Map<String, Ast.Program> source = units(ACTOR, ACTOR);
        var failure = assertThrows(IllegalArgumentException.class,
                () -> ActorReceiverBinder.bindSourceSet("demo@1.0.0", source));
        assertTrue(failure.getMessage().contains("duplicate actor/type identity"),
                failure.getMessage());
    }

    @Test
    void declaredAndDefinedActorWithSameNominalNameCannotCoexist() {
        rejectsBinding(ACTOR,
                NS + "define actor Foo as end",
                "duplicate actor/type identity");
    }

    @Test
    void declaredActorDoesNotAllowAnInlineBody() {
        rejectsParse(NS + "declare actor Foo implements ActorXYZ as end",
                "declare actor must end with ';'");
    }

    @Test
    void declaredActorRejectsDuplicatedContracts() {
        rejectsParse(NS + "declare actor Foo implements ActorXYZ, ActorXYZ;",
                "duplicate declared actor contract");
    }

    @Test
    void genericDeclaredActorRequiresDedicatedWitnesses() {
        rejectsParse(NS + "declare actor Foo<T> implements ActorXYZ;",
                "declare actor generics require");
    }

    @Test
    void declaredActorCannotLeakIntoNamedModuleScopes() {
        rejectsParse(NS + "define module other as declare actor Foo; end",
                "namespace-root declaration");
    }

    @Test
    void declarationOnlyActorFailsBeforeAnyGuestExecution() {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(NS + "declare actor Foo;")));
        assertTrue(failure.getMessage().contains("DECLARED_ACTOR_LINKING_UNSUPPORTED"),
                failure.getMessage());
    }

    @Test
    void sourceOnlyStateDeclarationAlsoFailsWithoutActorInstance() {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(
                        NS + "define state on actor Foo as { let int x : 5; }")));
        assertTrue(failure.getMessage().contains("has no local nominal actor")
                || failure.getMessage().contains("ACTOR_STATE_ATTACHMENT_LOWERING_UNSUPPORTED"),
                failure.getMessage());
    }

    @Test
    void actorStateCannotBeDeclaredInsideAnUnrelatedModule() {
        rejectsParse(NS + "define module nope as "
                        + "define state on actor Foo as { let int x : 5; } end",
                "namespace-root declaration");
    }
}
