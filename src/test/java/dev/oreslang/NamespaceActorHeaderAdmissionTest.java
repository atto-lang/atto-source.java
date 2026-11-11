package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.ActorReceiverBinder;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parser/nominal-admission only: these cases intentionally do not certify
 * external actor method execution or actor-contract conformance.
 */
final class NamespaceActorHeaderAdmissionTest {
    private static Ast.Program parse(String body) {
        return Parser.parse("namespace foo;\n" + body);
    }

    @Test
    void requestedActorHeaderAndPublicReceiverMethodsSurviveParsing() {
        var program = parse("""
                define actor Foo implements ActorXYZ;
                pub mtd (Foo f) init(): f { return f; }
                pub mtd (Foo f) bar(): void { return; }
                """);
        assertEquals("foo", program.namespace());
        var declarations = program.modules().getFirst().declarations();
        var actor = assertInstanceOf(Ast.StandaloneActorDecl.class, declarations.get(0));
        assertEquals("Foo", actor.name());
        assertNull(actor.receiverName(), "semicolon declaration must not fabricate an actor receiver");
        assertEquals(1, actor.implementedContracts().size());
        assertEquals("ActorXYZ", actor.implementedContracts().getFirst().name());
        var init = assertInstanceOf(Ast.ExternalActorMethodDecl.class, declarations.get(1));
        assertEquals("init", init.name());
        assertEquals("Foo", init.actorName());
        assertEquals("f", init.receiverName());
        assertEquals("f", init.returnType().name(), "receiver-return spelling remains unlowered");
        assertEquals(Ast.Visibility.PUBLIC, init.visibility());
        var bar = assertInstanceOf(Ast.ExternalActorMethodDecl.class, declarations.get(2));
        assertEquals("bar", bar.name());
        assertEquals(Ast.Visibility.PUBLIC, bar.visibility());
    }

    @Test
    void actorContractReferenceDoesNotBypassStaticConformanceOrGuestGate() {
        var program = parse("""
                pub define actor Foo implements ActorXYZ;
                pub mtd (Foo f) bar(): void { return; }
                """);
        var error = assertThrows(IllegalArgumentException.class,
                () -> ActorReceiverBinder.bindSourceSet(
                        "verified-name-not-proven@1", Map.of("src/foo.atto", program)));
        assertTrue(error.getMessage().contains("ACTOR_CONTRACT_WITNESS_UNSUPPORTED"),
                error.getMessage());
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(program),
                "parser recognition must never turn into actor execution");
    }

    @Test
    void twoSemicolonActorsDoNotCollideOnAnImplicitReceiverAlias() {
        var program = parse("""
                pub define actor Foo;
                pub define actor Bar;
                pub mtd (Foo f) a(): void { return; }
                pub mtd (Bar b) b(): void { return; }
                """);
        var bindings = ActorReceiverBinder.bindSourceSet(
                "demo@1.0.0", Map.of("src/foo.atto", program));
        assertEquals(2, bindings.size());
        assertTrue(bindings.containsKey(
                new ActorReceiverBinder.SourceSlot("demo@1.0.0", "foo", "Foo", "a", 0)));
        assertTrue(bindings.containsKey(
                new ActorReceiverBinder.SourceSlot("demo@1.0.0", "foo", "Bar", "b", 0)));
    }

    @Test
    void privateActorCannotAcquirePublicExternalApi() {
        var program = parse("""
                define actor Foo;
                pub mtd (Foo f) bar(): void { return; }
                """);
        var error = assertThrows(IllegalArgumentException.class,
                () -> ActorReceiverBinder.bindSourceSet(
                        "demo@1.0.0", Map.of("src/foo.atto", program)));
        assertTrue(error.getMessage().contains("cannot expose private actor"),
                error.getMessage());
    }

    @Test
    void semicolonActorRejectsInheritedAndGenericTypes() {
        assertThrows(IllegalArgumentException.class,
                () -> parse("define actor Foo extends Parent;"));
        assertThrows(IllegalArgumentException.class,
                () -> parse("define actor Foo<T>;"));
    }

    @Test
    void duplicateContractNamesAreNotValidActorHeaders() {
        assertThrows(IllegalArgumentException.class,
                () -> parse("define actor Foo implements ActorXYZ, ActorXYZ;"));
    }

    @Test
    void inheritedActorCannotGainUncheckedExternalSlot() {
        var program = parse("""
                pub define actor Base as end
                pub define actor Foo extends Base as end
                pub mtd (Foo f) bar(): void { return; }
                """);
        var error = assertThrows(IllegalArgumentException.class,
                () -> ActorReceiverBinder.bindSourceSet(
                        "demo@1.0.0", Map.of("src/foo.atto", program)));
        assertTrue(error.getMessage().contains("inherited actor slot coherence"),
                error.getMessage());
    }

    @Test
    void borrowedActorMailPayloadFailsStaticAdmission() {
        var program = parse("""
                pub define actor Foo;
                define mtd (Foo f) receive(ActorMail<borrow int> mail): void { return; }
                """);
        var error = assertThrows(IllegalArgumentException.class,
                () -> ActorReceiverBinder.bindSourceSet(
                        "demo@1.0.0", Map.of("src/foo.atto", program)));
        assertTrue(error.getMessage().contains("ActorMail<T>"), error.getMessage());
    }
}
