package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.ActorReceiverBinder;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Namespace actor ingress ABI is parser/static binder metadata only.
 * This suite must NOT be represented as executable actor mail/activation proof.
 */
final class NamespaceActorIngressAdmissionTest {
    private static Ast.Program parse(String body) {
        return Parser.parse("namespace foo;\n" + body);
    }

    private static final String HEADER = "declare pub actor Foo;\n";

    private static Map<ActorReceiverBinder.SourceSlot, ActorReceiverBinder.SourceBinding>
    bind(String program) {
        return ActorReceiverBinder.bindSourceSet(
                "demo@1.0", Map.of("src/foo.atto", parse(program)));
    }

    @Test
    void canonicalExternalInitIsPrivateZeroArgVoidAndExplicitIngress() {
        var program = parse(HEADER + """
                external mtd (Foo f) init(): void { return; }
                pub mtd (Foo f) bar(): void { return; }
                """);
        var decls = program.modules().getFirst().declarations();
        var init = assertInstanceOf(Ast.ExternalActorMethodDecl.class, decls.get(1));
        var bar = assertInstanceOf(Ast.ExternalActorMethodDecl.class, decls.get(2));
        assertTrue(init.runtimeIngress());
        assertEquals(Ast.Visibility.PRIVATE, init.visibility());
        assertEquals("init", init.name());
        assertEquals(0, init.parameters().size());
        assertEquals("void", init.returnType().name());
        assertFalse(bar.runtimeIngress());
        assertEquals(Ast.Visibility.PUBLIC, bar.visibility());
        var indexed = bind(HEADER + """
                external mtd (Foo f) init(): void { return; }
                pub mtd (Foo f) bar(): void { return; }
                """);
        assertTrue(indexed.get(new ActorReceiverBinder.SourceSlot(
                "demo@1.0", "foo", "Foo", "init", 0))
                .nominalBinding().method().runtimeIngress());
        assertFalse(indexed.get(new ActorReceiverBinder.SourceSlot(
                "demo@1.0", "foo", "Foo", "bar", 0))
                .nominalBinding().method().runtimeIngress());
    }

    @Test
    void canonicalReceiveHasExactlyOneActorMailPayloadAndPrivateVisibility() {
        var bound = bind(HEADER + """
                external mtd (Foo f) receive(ActorMail<int> mail): void { return; }
                """);
        assertEquals(1, bound.size());
        var receive = bound.values().iterator().next().nominalBinding().method();
        assertTrue(receive.runtimeIngress());
        assertEquals("receive", receive.name());
        assertEquals("ActorMail", receive.parameters().getFirst().type().name());
    }

    @Test
    void publicInitCannotClaimRuntimeIngress() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> bind(HEADER + """
                        pub mtd (Foo f) init(): void { return; }
                        """));
        assertTrue(error.getMessage().contains("requires explicit private 'external mtd'"),
                error.getMessage());
    }

    @Test
    void privateOrdinaryInitAndReceiveAreNotRuntimeCallbacks() {
        for (String method : new String[]{
                "define mtd (Foo f) init(): void { return; }",
                "define mtd (Foo f) receive(ActorMail<int> m): void { return; }"}) {
            var error = assertThrows(IllegalArgumentException.class,
                    () -> bind(HEADER + method));
            assertTrue(error.getMessage().contains("requires explicit private 'external mtd'"),
                    error.getMessage());
        }
    }

    @Test
    void ingressCannotBePublicOrAUserMethod() {
        assertThrows(IllegalArgumentException.class,
                () -> parse(HEADER
                        + "pub external mtd (Foo f) init(): void { return; }"));
        var ordinary = assertThrows(IllegalArgumentException.class,
                () -> bind(HEADER
                        + "external mtd (Foo f) bar(): void { return; }"));
        assertTrue(ordinary.getMessage().contains("reserved for init/receive"),
                ordinary.getMessage());
    }

    @Test
    void initCannotReturnActorOrAcceptArguments() {
        for (String invalid : new String[]{
                "external mtd (Foo f) init(): Foo { return f; }",
                "external mtd (Foo f) init(int x): void { return; }",
                "external mtd (Foo f) init(): int { return 5; }"}) {
            var error = assertThrows(IllegalArgumentException.class,
                    () -> bind(HEADER + invalid));
            assertTrue(error.getMessage().contains("zero-argument and return void"),
                    error.getMessage());
        }
    }

    @Test
    void receiveCannotAcceptRawOrBorrowedPayloads() {
        for (String invalid : new String[]{
                "external mtd (Foo f) receive(int value): void { return; }",
                "external mtd (Foo f) receive(ActorMail<borrow int> m): void { return; }",
                "external mtd (Foo f) receive(ActorMail<int> m): int { return 2; }",
                "external mtd (Foo f) receive(): void { return; }"}) {
            var error = assertThrows(IllegalArgumentException.class,
                    () -> bind(HEADER + invalid));
            assertTrue(error.getMessage().contains("ActorMail<T>"),
                    error.getMessage());
        }
    }

    @Test
    void noIngressOnInlineActorOrUnresolvedActor() {
        for (String header : new String[]{
                "pub define actor Foo as end\n",
                "declare pub actor Bar;\n"}) {
            var error = assertThrows(IllegalArgumentException.class,
                    () -> bind(header + "external mtd (Foo f) init(): void { return; }"));
            assertTrue(error.getMessage().contains("declared namespace actor")
                            || error.getMessage().contains("has no nominal actor"),
                    error.getMessage());
        }
    }

    @Test
    void ingressAndOrdinaryMethodShareOneNominalSlot() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> bind(HEADER + """
                        external mtd (Foo f) init(): void { return; }
                        define mtd (Foo f) init(): void { return; }
                        """));
        // The static method binder must never produce two valid dispatch
        // candidates for a lifecycle slot, even when one is a forged public API.
        assertTrue(error.getMessage().contains("requires explicit private 'external mtd'")
                        || error.getMessage().contains("duplicate external actor method slot"),
                error.getMessage());
    }

    @Test
    void sourceFixtureCannotBecomeExecutableWithoutActorRuntimeLowering() throws Exception {
        var source = Files.readString(Path.of(
                "contracts/namespace-actor-state-v1/ingress.atto"));
        var program = Parser.parse(source);
        var map = ActorReceiverBinder.bindSourceSet(
                "fixture@1", Map.of("src/ingress.atto", program));
        assertEquals(2, map.size());
        var error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(program));
        assertTrue(error.getMessage().contains("LOWERING_UNSUPPORTED"),
                error.getMessage());
    }

    @Test
    void arbitraryExternalIdentifierMustNotBypassIngressParser() {
        assertThrows(IllegalArgumentException.class,
                () -> parse(HEADER
                        + "external fnc something(): void { return; }"));
    }
}
