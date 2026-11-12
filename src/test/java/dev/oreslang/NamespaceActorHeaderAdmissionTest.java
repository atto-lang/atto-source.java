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
        return Parser.parse("use namespace foo;\n" + body);
    }

    @Test
    void canonicalHeaderIsContextualAndLegacyHeaderStillReadable() {
        assertEquals("foo", parse("declare actor Foo;").namespace());
        assertEquals("foo", Parser.parse("namespace foo;\ndeclare actor Foo;").namespace());
    }

    @Test
    void malformedUseNamespaceHeaderAndOutOfOrderHeaderFailClosed() {
        for (String source : new String[] {
                "use namespace;\ndeclare actor Foo;",
                "use namespace foo.bar;\ndeclare actor Foo;",
                "use namespace foo\ndeclare actor Foo;",
                "use namespace foo;\nuse namespace bar;\ndeclare actor Foo;",
                "use unknown;\ndeclare actor Foo;",
                "declare actor Foo;\nuse namespace foo;"
        }) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(source), source);
        }
    }

    @Test
    void requestedActorHeaderAndPublicReceiverMethodsSurviveParsing() {
        var program = parse("""
                declare actor Foo implements ActorXYZ;
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
    void declareActorIsNominalOnlyAndInlineDefineRemainsASeparateForm() {
        var declared = parse("declare actor Foo;");
        var actor = assertInstanceOf(Ast.StandaloneActorDecl.class,
                declared.modules().getFirst().declarations().getFirst());
        assertEquals(Ast.Visibility.PRIVATE, actor.visibility());
        assertNull(actor.receiverName());

        var implemented = parse("define actor Foo as end");
        assertInstanceOf(Ast.ClassDecl.class,
                implemented.modules().getFirst().declarations().getFirst());
    }

    @Test
    void semicolonDefineActorCannotMasqueradeAsDeclaration() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> parse("define actor Foo;"));
        assertTrue(error.getMessage().contains("declare actor"), error.getMessage());
    }

    @Test
    void visibilityMustFollowDeclare() {
        var publicActor = assertInstanceOf(Ast.StandaloneActorDecl.class,
                parse("declare pub actor Foo;")
                        .modules().getFirst().declarations().getFirst());
        assertEquals(Ast.Visibility.PUBLIC, publicActor.visibility());

        for (String header : new String[]{
                "declare actor Foo;", "declare private actor Foo;"}) {
            var privateActor = assertInstanceOf(Ast.StandaloneActorDecl.class,
                    parse(header).modules().getFirst().declarations().getFirst());
            assertEquals(Ast.Visibility.PRIVATE, privateActor.visibility());
        }

        for (String rejected : new String[]{
                "pub declare actor Foo;", "private declare actor Foo;",
                "pub declare pub actor Foo;"}) {
            var error = assertThrows(IllegalArgumentException.class,
                    () -> parse(rejected));
            assertTrue(error.getMessage().contains("visibility must follow 'declare'"),
                    error.getMessage());
        }
    }

    @Test
    void actorContractReferenceDoesNotBypassStaticConformanceOrGuestGate() {
        var program = parse("""
                declare pub actor Foo implements ActorXYZ;
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
                declare pub actor Foo;
                declare pub actor Bar;
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
                declare actor Foo;
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
                () -> parse("declare actor Foo extends Parent;"));
        assertThrows(IllegalArgumentException.class,
                () -> parse("declare actor Foo<T>;"));
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
                declare pub actor Foo;
                external mtd (Foo f) receive(ActorMail<borrow int> mail): void { return; }
                """);
        var error = assertThrows(IllegalArgumentException.class,
                () -> ActorReceiverBinder.bindSourceSet(
                        "demo@1.0.0", Map.of("src/foo.atto", program)));
        assertTrue(error.getMessage().contains("ActorMail<T>"), error.getMessage());
    }
}
