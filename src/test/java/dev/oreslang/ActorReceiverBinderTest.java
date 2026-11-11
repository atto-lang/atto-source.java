package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.ActorReceiverBinder;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Static nominal resolution only: no ActorContext or runnable external mtd. */
final class ActorReceiverBinderTest {
    private static Map<ActorReceiverBinder.Slot, ActorReceiverBinder.Binding> bind(String source) {
        return ActorReceiverBinder.bind(Parser.parse(source));
    }

    private static String module(String body) {
        return "define module workers as\n" + body + "\nend\n";
    }

    private static String actor() {
        return "  pub define actor Worker as worker;\n";
    }

    private static String method(String name, String args, String result) {
        return "  define pub mtd " + name + "(Worker w)(" + args
                + "): " + result + " { return; }\n";
    }

    private static void rejects(String source, String diagnostic) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> bind(source));
        assertTrue(error.getMessage().contains(diagnostic), error.getMessage());
    }

    @Test
    void standaloneActorBindsItsOwnExternalMethodWithoutExecution() {
        var bindings = bind(module(actor() + method("get_count", "", "int")));
        var key = new ActorReceiverBinder.Slot("workers", "Worker", "get_count", 0);
        assertEquals(1, bindings.size());
        var resolved = bindings.get(key);
        assertInstanceOf(Ast.StandaloneActorDecl.class, resolved.nominalActor());
        assertEquals("w", resolved.method().receiverName());
    }

    @Test
    void receiverCanPrecedeActorDeclarationInTheSameModule() {
        var bindings = bind(module(method("get_count", "", "int") + actor()));
        assertEquals(1, bindings.size());
    }

    @Test
    void legacyActorClassMayBeBoundWithoutSynthesizingAnOrdinaryFunction() {
        var bindings = bind(module("  pub define actor Worker as end\n"
                + method("get_count", "", "int")));
        var binding = bindings.values().iterator().next();
        assertInstanceOf(Ast.ClassDecl.class, binding.nominalActor());
    }

    @Test
    void actorIdentityIsModuleLocalEvenWhenAnotherModuleHasTheSameActorName() {
        var bindings = bind(module(actor() + method("get_count", "", "int"))
                + "define module sibling as\n"
                + "  pub define actor Worker as worker;\n"
                + "  define pub mtd ping(Worker w)(): void { return; }\n"
                + "end\n");
        assertEquals(2, bindings.size());
        assertTrue(bindings.containsKey(new ActorReceiverBinder.Slot(
                "workers", "Worker", "get_count", 0)));
        assertTrue(bindings.containsKey(new ActorReceiverBinder.Slot(
                "sibling", "Worker", "ping", 0)));
    }

    @Test
    void externalReceiverCannotUseActorFromAnotherModule() {
        rejects("define module owners as\n" + actor() + "end\n"
                + module(method("get_count", "", "int")),
                "declared in the same module");
    }

    @Test
    void missingActorCannotBecomeAnImportedOrImplicitActor() {
        rejects(module(method("get_count", "", "int")),
                "has no nominal actor");
    }

    @Test
    void ordinaryClassCannotMasqueradeAsActorReceiver() {
        rejects(module("  define class Worker as end\n"
                + method("get_count", "", "int")),
                "has no nominal actor");
    }

    @Test
    void duplicateMethodSlotRejectsTypeBasedOverloading() {
        rejects(module(actor()
                + method("update", "int value", "void")
                + method("update", "String value", "int")),
                "duplicate external actor method slot");
    }

    @Test
    void distinctMethodAritiesRemainDistinctStaticSlots() {
        assertEquals(2, bind(module(actor()
                + method("update", "int value", "void")
                + method("update", "int value, int other", "void"))).size());
    }

    @Test
    void inClassMethodCannotAlsoBeImplementedAsExternalReceiver() {
        rejects(module("  pub define actor Worker as\n"
                + "    pub get_count(): int { return 1; }\n"
                + "  end\n"
                + method("get_count", "", "int")),
                "conflicts with an in-class actor method");
    }

    @Test
    void twoNominalActorsCannotClaimTheSameTypeName() {
        rejects(module(actor()
                + "  pub define actor Worker as another;\n"),
                "duplicate actor/type identity");
    }

    @Test
    void twoStandaloneActorsCannotClaimTheSameReceiverAlias() {
        rejects(module(actor()
                + "  pub define actor Other as worker;\n"),
                "duplicate standalone actor receiver binding");
    }

    @Test
    void publicReceiveMustNotBePromotedToMailboxApi() {
        rejects(module(actor()
                + "  define pub mtd receive(Worker w)(ActorMail<int> mail): void { return; }\n"),
                "external receive must be private");
    }

    @Test
    void validPrivateReceiveHookShapeIsBoundButNotExecutable() {
        assertEquals(1, bind(module(actor()
                + "  define mtd receive(Worker w)(ActorMail<int> mail): void { return; }\n")).size());
    }

    @Test
    void wrongMailboxPayloadIsRejectedBeforeRuntimeLowering() {
        rejects(module(actor()
                + "  define mtd receive(Worker w)(int value): void { return; }\n"),
                "external receive must be private");
    }

    @Test
    void publicLifecycleHookDoesNotGainExecutionAuthority() {
        rejects(module(actor()
                + "  define pub mtd on_start(Worker w)(): void { return; }\n"),
                "external lifecycle method");
    }

    @Test
    void privateLifecycleHookHasAnExplicitStaticSlot() {
        assertEquals(1, bind(module(actor()
                + "  define mtd on_start(Worker w)(): void { return; }\n")).size());
    }

    @Test
    void cannotEmitMutableOrExecutableBoundSlotMap() {
        var bindings = bind(module(actor() + method("get_count", "", "int")));
        assertThrows(UnsupportedOperationException.class, bindings::clear);
    }

    @Test
    void bindingDoesNotBypassTheExecutableTypeCheckerGate() {
        String source = module(actor() + method("get_count", "", "int"));
        assertEquals(1, bind(source).size());
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(error.getMessage().contains("STANDALONE_ACTOR_LOWERING_UNSUPPORTED")
                        || error.getMessage().contains("EXTERNAL_ACTOR_RECEIVER_LOWERING_UNSUPPORTED"),
                error.getMessage());
    }

    @Test
    void unboundExternalMethodIsRejectedBeforeLoweringGate() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(module(method("get_count", "", "int")))));
        assertTrue(error.getMessage().contains("has no nominal actor"),
                error.getMessage());
    }
    @Test
    void portableFixturesReachRealParserAndNominalBinder() throws IOException {
        Path root = Path.of("contracts", "actor-receiver-binding-v1");
        assertEquals(1, bind(Files.readString(root.resolve(
                "accept_nominal_binding.atto"))).size());

        Map<String, String> invalid = Map.of(
                "reject_missing_actor.atto", "has no nominal actor",
                "reject_cross_module_receiver.atto", "declared in the same module",
                "reject_duplicate_external_slot.atto", "duplicate external actor method slot",
                "reject_public_receive.atto", "external receive must be private",
                "reject_in_class_collision.atto", "conflicts with an in-class actor method");
        for (Map.Entry<String, String> entry : invalid.entrySet()) {
            String source = Files.readString(root.resolve(entry.getKey()));
            rejects(source, entry.getValue());
        }
    }


    @Test
    void publicExternalMethodCannotExposePrivateNominalActor() {
        rejects(module("  define actor Worker as worker;\n"
                + method("get_count", "", "int")),
                "cannot expose private actor");
    }

    @Test
    void standaloneActorCannotImpersonateReservedSymbolType() {
        rejects(module("  pub define actor Symbol as symbol;\n"),
                "Symbol is a reserved nominal built-in");
    }


    @Test
    void bindingMetadataCannotBeForgedFromAnOrdinaryClass() {
        Ast.Program parsed = Parser.parse(module(
                "  define class Worker as end\n"
                + method("get_count", "", "int")));
        var declarations = parsed.modules().getFirst().declarations();
        Ast.ClassDecl ordinary = assertInstanceOf(Ast.ClassDecl.class, declarations.getFirst());
        var method = assertInstanceOf(Ast.ExternalActorMethodDecl.class, declarations.get(1));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new ActorReceiverBinder.Binding(ordinary, method));
        assertTrue(error.getMessage().contains("nominal actor declaration"), error.getMessage());
    }

    @Test
    void bindingMetadataCannotBeForgedAcrossNominalActorTypes() {
        Ast.Program parsed = Parser.parse(module(
                "  pub define actor Other as other;\n"
                + method("get_count", "", "int")));
        var declarations = parsed.modules().getFirst().declarations();
        Ast.StandaloneActorDecl other = assertInstanceOf(
                Ast.StandaloneActorDecl.class, declarations.getFirst());
        var method = assertInstanceOf(Ast.ExternalActorMethodDecl.class, declarations.get(1));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new ActorReceiverBinder.Binding(other, method));
        assertTrue(error.getMessage().contains("actor identity must match"), error.getMessage());
    }


}
